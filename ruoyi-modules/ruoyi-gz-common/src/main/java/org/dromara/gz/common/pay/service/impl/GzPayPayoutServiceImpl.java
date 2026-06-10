package org.dromara.gz.common.pay.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.bo.GzPayPayoutQueryBo;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayPayoutTransactionVO;
import org.dromara.gz.common.pay.enums.PayoutStatus;
import org.dromara.gz.common.pay.mapper.GzPayPayoutCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayPayoutService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.TransferQueryResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.TransferRequest;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.TransferResult;
import org.dromara.gz.common.pay.service.internal.PayoutNoGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 反向打款服务实现（GZ-PAY-105，ADR-0006，doc/10 §14 / doc/11 §4.8）。
 *
 * <p><b>双重幂等</b>（ADR-0006 §5）：① 1:1 业务单查重（建单前 countActiveByBusinessOrderNo &gt; 0 → 返回已有单，
 * 防店员重复点「打款」）；② out_payout_no UNIQUE + batch_id UNIQUE 兜底（重复受理命中唯一约束按幂等处理，
 * 不二次转账）；③ version 乐观锁状态推进。<b>主动查单优先</b>（ADR-0006 §3）：SnailJob 周期扫 processing
 * 态查单推进 success/failed，回调为辅（gz_pay_payout_callback_log 存档）。</p>
 *
 * <p><b>事务边界</b>：{@link #initiatePayout} 建单 + 受理单笔事务；查单扫描循环不开外层事务（单条异常隔离，
 * 一条坏单不卡死整批，与 PAY-102 同范式）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzPayPayoutServiceImpl implements IGzPayPayoutService {

    /** 查单单轮上限（防雪崩，与 PAY-102 SCAN_LIMIT 同口径） */
    private static final int SCAN_LIMIT = 100;

    private final GzPayPayoutTransactionMapper payoutMapper;
    private final GzPayPayoutCallbackLogMapper payoutCallbackLogMapper;
    private final PayoutNoGenerator payoutNoGenerator;
    private final IWechatPayClient wechatPayClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzPayPayoutTransactionVO initiatePayout(InitiateBo req) {
        // ① 1:1 业务单查重（ADR-0006 §5）：已有非 failed/cancelled 单 → 返回已有单，不重复发起
        GzPayPayoutTransaction existing = payoutMapper.selectByActiveBusinessOrderNo(req.businessOrderNo());
        if (existing != null) {
            log.info("[gz-payout] business_order_no={} 已有活跃打款单 out_payout_no={} status={} → 幂等返回已有单（不重复发起）",
                req.businessOrderNo(), existing.getOutPayoutNo(), existing.getStatus());
            return payoutMapper.selectVoById(existing.getId());
        }

        // ② 建单（created）。out_payout_no UNIQUE 兜底并发：撞 UNIQUE 说明并发已建 → 重查返回（幂等）
        String outPayoutNo = payoutNoGenerator.generate();
        GzPayPayoutTransaction entity = GzPayPayoutTransaction.builder()
            .outPayoutNo(outPayoutNo)
            .businessType(req.businessType())
            .businessOrderNo(req.businessOrderNo())
            .userId(req.userId())
            .receiverOpenid(req.receiverOpenid())
            .amountCent(req.amountCent())
            .status(PayoutStatus.CREATED)
            .version(0)
            .build();
        try {
            payoutMapper.insert(entity);
        } catch (DuplicateKeyException dup) {
            // 并发：out_payout_no 或 business_order_no UNIQUE 命中 → 重查活跃单返回（不二次转账）
            GzPayPayoutTransaction concurrent = payoutMapper.selectByActiveBusinessOrderNo(req.businessOrderNo());
            if (concurrent != null) {
                log.warn("[gz-payout] 并发建单撞 UNIQUE business_order_no={} → 幂等返回 out_payout_no={}",
                    req.businessOrderNo(), concurrent.getOutPayoutNo());
                return payoutMapper.selectVoById(concurrent.getId());
            }
            throw dup;
        }

        // ③ 调商家转账受理。受理成功 → created→processing（写 payout_id + batch_id）；受理失败 → created→failed
        TransferRequest transferReq = new TransferRequest(
            outPayoutNo, req.receiverOpenid(), req.amountCent(), req.transferRemark(), req.businessOrderNo());
        try {
            TransferResult result = wechatPayClient.transferToUserWallet(transferReq);
            int affected = payoutMapper.markProcessing(entity.getId(), entity.getVersion(),
                result.payoutId(), result.batchId());
            if (affected == 0) {
                log.warn("[gz-payout] markProcessing affected=0 out_payout_no={}（并发推进/version 漂移，幂等跳过）", outPayoutNo);
            }
            log.info("[gz-payout] initiatePayout out_payout_no={} amount_cent={} → processing payout_id={} batch_id={}",
                outPayoutNo, req.amountCent(), result.payoutId(), result.batchId());
        } catch (Exception ex) {
            // 受理失败（mock 注入 / real 抛 SDK 异常）→ 标 failed（旁路，可重试重置 created）
            payoutMapper.markCreatedFailed(entity.getId(), "受理失败：" + ex.getMessage());
            log.warn("[gz-payout] transferToUserWallet 受理失败 out_payout_no={} → failed: {}", outPayoutNo, ex.getMessage());
        }
        return payoutMapper.selectVoById(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzPayPayoutTransactionVO retryPayout(String businessOrderNo, String transferRemark) {
        // 该业务单当前活跃单（非 failed/cancelled）若存在 → 不重试（已在途/已成，幂等防重复转账）
        GzPayPayoutTransaction active = payoutMapper.selectByActiveBusinessOrderNo(businessOrderNo);
        if (active != null) {
            log.info("[gz-payout] retry business_order_no={} 已有活跃单 out_payout_no={} status={} → 不重试（幂等返回）",
                businessOrderNo, active.getOutPayoutNo(), active.getStatus());
            return payoutMapper.selectVoById(active.getId());
        }
        // 取该业务单最近一条 failed 单重置 failed→created（清 fail_reason/payout_id/batch_id）
        GzPayPayoutTransaction failed = payoutMapper.selectByOutPayoutNo(
            // selectByActiveBusinessOrderNo 排除 failed，单独取 failed 单：用 business_order_no 直查最新
            findLatestFailedOutPayoutNo(businessOrderNo));
        if (failed == null || !PayoutStatus.FAILED.equals(failed.getStatus())) {
            throw new org.dromara.common.core.exception.ServiceException("无可重试的失败打款单（business_order_no=" + businessOrderNo + "）");
        }
        int reset = payoutMapper.retryFailedToCreated(failed.getId());
        if (reset == 0) {
            // 并发：已被其他重试/查单改动 → 重查活跃单幂等返回
            GzPayPayoutTransaction concurrent = payoutMapper.selectByActiveBusinessOrderNo(businessOrderNo);
            return concurrent != null ? payoutMapper.selectVoById(concurrent.getId()) : payoutMapper.selectVoById(failed.getId());
        }
        // 重新发起一轮受理（复用同一 out_payout_no 单，version 已 +1）
        GzPayPayoutTransaction reloaded = payoutMapper.selectById(failed.getId());
        TransferRequest transferReq = new TransferRequest(
            reloaded.getOutPayoutNo(), reloaded.getReceiverOpenid(), reloaded.getAmountCent(),
            transferRemark, businessOrderNo);
        try {
            TransferResult result = wechatPayClient.transferToUserWallet(transferReq);
            int affected = payoutMapper.markProcessing(reloaded.getId(), reloaded.getVersion(),
                result.payoutId(), result.batchId());
            if (affected == 0) {
                log.warn("[gz-payout] retry markProcessing affected=0 out_payout_no={}（并发，幂等跳过）", reloaded.getOutPayoutNo());
            }
            log.info("[gz-payout] retryPayout out_payout_no={} → processing payout_id={} batch_id={}",
                reloaded.getOutPayoutNo(), result.payoutId(), result.batchId());
        } catch (Exception ex) {
            payoutMapper.markCreatedFailed(reloaded.getId(), "重试受理失败：" + ex.getMessage());
            log.warn("[gz-payout] retryPayout 受理失败 out_payout_no={} → failed: {}", reloaded.getOutPayoutNo(), ex.getMessage());
        }
        return payoutMapper.selectVoById(reloaded.getId());
    }

    /** 取该业务单最近一条 failed 单的 out_payout_no（重试用）；无则 null。 */
    private String findLatestFailedOutPayoutNo(String businessOrderNo) {
        GzPayPayoutTransaction failed = payoutMapper.selectOne(
            Wrappers.<GzPayPayoutTransaction>lambdaQuery()
                .eq(GzPayPayoutTransaction::getBusinessOrderNo, businessOrderNo)
                .eq(GzPayPayoutTransaction::getStatus, PayoutStatus.FAILED)
                .orderByDesc(GzPayPayoutTransaction::getId)
                .last("LIMIT 1"));
        return failed == null ? null : failed.getOutPayoutNo();
    }

    @Override
    public GzPayPayoutTransactionVO queryAndAdvanceByBusinessOrderNo(String businessOrderNo) {
        GzPayPayoutTransaction active = payoutMapper.selectByActiveBusinessOrderNo(businessOrderNo);
        if (active == null) {
            return null;
        }
        if (PayoutStatus.PROCESSING.equals(active.getStatus())) {
            // 主动查单一次推进（与 scanAndQuery 同款单条逻辑）
            try {
                queryOne(active.getId());
            } catch (Exception ex) {
                log.warn("[gz-payout] queryAndAdvance business_order_no={} 查单异常: {}", businessOrderNo, ex.getMessage());
            }
        }
        return payoutMapper.selectVoById(active.getId());
    }

    @Override
    public TableDataInfo<GzPayPayoutTransactionVO> selectPageList(GzPayPayoutQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzPayPayoutTransaction> wrapper = Wrappers.<GzPayPayoutTransaction>lambdaQuery()
            .eq(StrUtil.isNotBlank(query.getStatus()), GzPayPayoutTransaction::getStatus, query.getStatus())
            .eq(StrUtil.isNotBlank(query.getOutPayoutNo()), GzPayPayoutTransaction::getOutPayoutNo, query.getOutPayoutNo())
            .eq(StrUtil.isNotBlank(query.getBusinessOrderNo()), GzPayPayoutTransaction::getBusinessOrderNo, query.getBusinessOrderNo())
            .eq(StrUtil.isNotBlank(query.getPayoutId()), GzPayPayoutTransaction::getPayoutId, query.getPayoutId())
            .orderByDesc(GzPayPayoutTransaction::getId);
        Page<GzPayPayoutTransactionVO> page = payoutMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    @Override
    public QueryResult scanAndQuery() {
        // cron 无登录态 → 全租户扫（与 PAY-102 scanAndReconcile 同思路）
        return TenantHelper.ignore(() -> {
            List<Long> ids = payoutMapper.selectProcessingIds(SCAN_LIMIT);
            int success = 0;
            int failed = 0;
            int pending = 0;
            int skipped = 0;
            for (Long id : ids) {
                try {
                    QueryOutcome outcome = queryOne(id);
                    switch (outcome) {
                        case SUCCESS -> success++;
                        case FAILED -> failed++;
                        case PENDING -> pending++;
                        default -> skipped++;
                    }
                } catch (Exception ex) {
                    // 单条异常隔离：一条坏单不卡死整批，下轮重试
                    skipped++;
                    log.warn("[gz-payout] 查单单条异常 payout_id={} → skip: {}", id, ex.getMessage());
                }
            }
            log.info("[gz-payout] scanAndQuery 完成：扫描 {} / 到账 {} / 失败 {} / 保持 processing {} / 跳过 {}",
                ids.size(), success, failed, pending, skipped);
            return new QueryResult(ids.size(), success, failed, pending, skipped);
        });
    }

    /**
     * 查单推进单条 processing 单（ADR-0006 §3）：查 transfer_state → SUCCESS/FAIL 推进终态 + 存档查单 body。
     */
    private QueryOutcome queryOne(Long id) {
        GzPayPayoutTransaction payout = payoutMapper.selectById(id);
        if (payout == null || !PayoutStatus.PROCESSING.equals(payout.getStatus())) {
            // 行不存在 / 已被并发推进终态 → 跳过（幂等）
            return QueryOutcome.SKIPPED;
        }
        TransferQueryResult qr = wechatPayClient.queryTransferByOutNo(payout.getOutPayoutNo());
        // 查单结果存档备查（纯审计，ADR-0006 §3 回调为辅，查单驱动也入档便于溯源）
        archiveQueryResult(payout, qr);

        return switch (qr.transferState()) {
            case "SUCCESS" -> {
                int affected = payoutMapper.markSuccess(id, LocalDateTime.now());
                yield affected == 1 ? QueryOutcome.SUCCESS : QueryOutcome.SKIPPED;
            }
            case "FAIL" -> {
                int affected = payoutMapper.markFailed(id, StrUtil.blankToDefault(qr.failReason(), "商家转账失败"));
                yield affected == 1 ? QueryOutcome.FAILED : QueryOutcome.SKIPPED;
            }
            // PROCESSING（或未知态）→ 保持 processing 等下轮
            default -> QueryOutcome.PENDING;
        };
    }

    /** 查单/回调结果入审计表（gz_pay_payout_callback_log，纯审计永不更新，doc/11 §4.9）。 */
    private void archiveQueryResult(GzPayPayoutTransaction payout, TransferQueryResult qr) {
        GzPayPayoutCallbackLog log = GzPayPayoutCallbackLog.builder()
            .outPayoutNo(payout.getOutPayoutNo())
            .payoutId(qr.payoutId())
            .callbackType("payout")
            .rawBody(qr.rawBody())
            .processStatus("processed")
            .build();
        payoutCallbackLogMapper.insert(log);
    }

    @Override
    public GzPayPayoutTransactionVO getById(Long id) {
        return payoutMapper.selectVoById(id);
    }

    /** 单条查单推进结果（scanAndQuery 统计分类）。 */
    private enum QueryOutcome {
        SUCCESS, FAILED, PENDING, SKIPPED
    }
}
