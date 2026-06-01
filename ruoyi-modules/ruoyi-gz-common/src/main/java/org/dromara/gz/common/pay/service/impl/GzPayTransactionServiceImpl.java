package org.dromara.gz.common.pay.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.GzPayTestCreateBo;
import org.dromara.gz.common.pay.domain.bo.GzPayTransactionQueryBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.CallbackResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.JsapiPayParams;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.UnifiedOrderRequest;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.internal.WechatPayVerifyException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付订单服务实现（GZ-PAY-001）。
 *
 * <p>实现 doc/10 §2 通道 HelloWorld：统一下单（AC 4）/ 回调验签 + 幂等（AC 6/7）/ 超时关单（AC 8）。
 * 通道实连封装在 {@link IWechatPayClient}（mock / real 双实现，profile 切换，AC 9）。</p>
 *
 * <p><b>幂等三层</b>（AC 7）：</p>
 * <ol>
 *   <li>transaction_id UNIQUE(tenant_id, transaction_id) DB 兜底（微信天然幂等 key）</li>
 *   <li>业务层 SELECT 判断：已 paid → callback_log duplicated 直接成功</li>
 *   <li>乐观锁 {@code UPDATE ... WHERE id=? AND version=? AND status='pending'}（markPaid）：
 *       affected=0 → 并发已处理，标 duplicated</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzPayTransactionServiceImpl implements IGzPayTransactionService {

    private static final String CALLBACK_TYPE_PAYMENT = "payment";
    private static final String CB_RECEIVED = "received";
    private static final String CB_PROCESSED = "processed";
    private static final String CB_DUPLICATED = "duplicated";
    private static final String CB_FAILED = "failed";

    /** 测试单有效期（doc/10 §2.E5：5 min 未回调 → timeout） */
    private static final int EXPIRE_MINUTES = 5;
    /** out_trade_no 撞 UNIQUE 时的重试次数（并发同日同序号兜底） */
    private static final int OUT_TRADE_NO_RETRY = 3;

    private final GzPayTransactionMapper transactionMapper;
    private final GzPayCallbackLogMapper callbackLogMapper;
    private final PayOrderNoGenerator orderNoGenerator;
    private final IWechatPayClient wechatPayClient;
    private final WechatPayProperties payProperties;

    // ============================================================
    //  AC 4 统一下单
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MpPayParamsVO createTestOrder(GzPayTestCreateBo bo, Long loginUserId) {
        long amountCent = bo.getAmountCent() != null ? bo.getAmountCent() : payProperties.getTest().getAmountCent();
        if (amountCent < 1 || amountCent > 100) {
            throw new ServiceException("测试单金额须在 1-100 分之间");
        }
        String openid = StrUtil.isNotBlank(bo.getOpenid()) ? bo.getOpenid() : "mock_openid_admin_test";

        // ① 建单 created + expire_time=now+5min
        GzPayTransaction tx = createCreatedTransaction(amountCent, openid, loginUserId);

        // ② 调通道统一下单拿 prepay_id（mock 返回固定值 / real 调微信）
        String prepayId;
        try {
            prepayId = wechatPayClient.createJsapiOrder(
                new UnifiedOrderRequest(tx.getOutTradeNo(), amountCent, openid, "谷子宇宙通道测试单"));
        } catch (Exception e) {
            // 通道失败 → 标 failed（doc/10 §2 通道失败态）
            tx.setStatus(PayStatus.FAILED);
            transactionMapper.updateById(tx);
            log.error("[gz-pay] 统一下单失败 out_trade_no={}", tx.getOutTradeNo(), e);
            throw new ServiceException("统一下单失败: " + e.getMessage());
        }

        // ③ UPDATE created → pending + prepay_id
        int updated = transactionMapper.markPending(tx.getId(), prepayId);
        if (updated == 0) {
            throw new ServiceException("订单状态推进失败（created → pending）");
        }

        // ④ 算 5 参签名返回 mp
        JsapiPayParams p = wechatPayClient.buildPayParams(prepayId);
        log.info("[gz-pay] 测试单创建 out_trade_no={} amount={} prepay_id={}", tx.getOutTradeNo(), amountCent, prepayId);
        return MpPayParamsVO.builder()
            .timeStamp(p.timeStamp())
            .nonceStr(p.nonceStr())
            .packageVal(p.packageVal())
            .signType(p.signType())
            .paySign(p.paySign())
            .outTradeNo(tx.getOutTradeNo())
            .build();
    }

    /**
     * 建 created 单（out_trade_no 撞 UNIQUE 重试）。
     */
    private GzPayTransaction createCreatedTransaction(long amountCent, String openid, Long loginUserId) {
        LocalDateTime now = LocalDateTime.now();
        DuplicateKeyException lastDup = null;
        for (int i = 0; i < OUT_TRADE_NO_RETRY; i++) {
            String outTradeNo = orderNoGenerator.generate(PayBusinessType.TEST);
            GzPayTransaction tx = GzPayTransaction.builder()
                .outTradeNo(outTradeNo)
                .businessType(PayBusinessType.TEST)
                .businessOrderNo(null)
                .userId(loginUserId)
                .openid(openid)
                .channelCode("wechat_pay_v3")
                .amountCent(amountCent)
                .currency("CNY")
                .status(PayStatus.CREATED)
                .expireTime(now.plusMinutes(EXPIRE_MINUTES))
                .version(0)
                .build();
            try {
                transactionMapper.insert(tx);
                return tx;
            } catch (DuplicateKeyException dup) {
                lastDup = dup;
                log.warn("[gz-pay] out_trade_no 撞 UNIQUE 重试 {}/{}：{}", i + 1, OUT_TRADE_NO_RETRY, outTradeNo);
            }
        }
        throw new ServiceException("生成测试单失败（out_trade_no 连续冲突）", lastDup);
    }

    // ============================================================
    //  AC 6/7 回调验签 + 幂等
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePaymentNotify(NotifyContext ctx) {
        // ① 验签解密（失败 throw → controller 写 failed + 401，不静默 AC 6）
        CallbackResult result = wechatPayClient.parseAndVerifyNotify(ctx);

        // ② 写 callback_log received（raw_body = 解密后 JSON）
        writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
            ctx.signature(), CB_RECEIVED, null);

        // 非 SUCCESS 交易状态（如 CLOSED / PAYERROR）：不推进 paid，记审计
        if (!"SUCCESS".equalsIgnoreCase(result.tradeState())) {
            writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
                ctx.signature(), CB_PROCESSED, "trade_state=" + result.tradeState() + " 非 SUCCESS，不推进 paid");
            log.warn("[gz-pay] 回调非 SUCCESS out_trade_no={} state={}", result.outTradeNo(), result.tradeState());
            return true;
        }

        GzPayTransaction tx = transactionMapper.selectByOutTradeNo(result.outTradeNo());
        if (tx == null) {
            writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
                ctx.signature(), CB_FAILED, "订单不存在: " + result.outTradeNo());
            log.error("[gz-pay] 回调订单不存在 out_trade_no={}", result.outTradeNo());
            return false;
        }

        // ③ 幂等 SELECT：已 paid → duplicated 直接成功（AC 7）
        if (PayStatus.PAID.equals(tx.getStatus())) {
            writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
                ctx.signature(), CB_DUPLICATED, "订单已 paid，重复回调");
            log.info("[gz-pay] 重复回调（已 paid）out_trade_no={}", result.outTradeNo());
            return true;
        }

        // ④ 乐观锁 UPDATE pending → paid（version + status 双守卫，AC 7）
        int affected = transactionMapper.markPaid(
            tx.getId(), tx.getVersion(), result.transactionId(), result.feeCent(), LocalDateTime.now());
        if (affected == 0) {
            // 并发回调已抢先推进 / version 漂移 → 视为重复
            writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
                ctx.signature(), CB_DUPLICATED, "乐观锁冲突（affected=0），并发已处理");
            log.info("[gz-pay] 乐观锁冲突视为重复回调 out_trade_no={}", result.outTradeNo());
            return true;
        }

        // ⑤ callback_log processed
        writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
            ctx.signature(), CB_PROCESSED, null);
        log.info("[gz-pay] 回调处理成功 out_trade_no={} → paid transaction_id={}",
            result.outTradeNo(), result.transactionId());
        return true;
    }

    private void writeCallbackLog(String transactionId, String outTradeNo, String rawBody,
                                  String signature, String processStatus, String processError) {
        GzPayCallbackLog log = GzPayCallbackLog.builder()
            .transactionId(transactionId)
            .outTradeNo(outTradeNo)
            .callbackType(CALLBACK_TYPE_PAYMENT)
            .rawBody(rawBody)
            .signature(signature)
            .processStatus(processStatus)
            .processError(processError)
            .build();
        callbackLogMapper.insert(log);
    }

    // ============================================================
    //  AC 8 超时关单
    // ============================================================

    @Override
    public ExpireResult expireTimeoutOrders() {
        // cron 无登录态 → 全租户扫（与 GZ-BEAN-009 markNoShowBatch 同思路）
        return TenantHelper.ignore(() -> {
            LocalDateTime now = LocalDateTime.now();
            List<Long> ids = transactionMapper.selectExpiredPendingIds(now);
            int closed = 0;
            int skipped = 0;
            for (Long id : ids) {
                try {
                    int affected = transactionMapper.markTimeout(id, now);
                    if (affected == 1) {
                        closed++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    // 单条异常隔离（不让一条坏单卡死整批，CLAUDE.md §6 #7）
                    skipped++;
                    log.error("[gz-pay] 超时关单单条失败 id={}", id, e);
                }
            }
            ExpireResult result = new ExpireResult(ids.size(), closed, skipped);
            if (!ids.isEmpty()) {
                log.info("[gz-pay] 超时关单完成：扫描 {} / 关单 {} / 跳过 {}", result.scanned(), result.closed(), result.skipped());
            }
            return result;
        });
    }

    // ============================================================
    //  AC 5 / AC 12 查询
    // ============================================================

    @Override
    public TableDataInfo<GzPayTransactionVO> selectPageList(GzPayTransactionQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzPayTransaction> wrapper = Wrappers.<GzPayTransaction>lambdaQuery()
            .eq(StrUtil.isNotBlank(query.getBusinessType()), GzPayTransaction::getBusinessType, query.getBusinessType())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzPayTransaction::getStatus, query.getStatus())
            .eq(StrUtil.isNotBlank(query.getOutTradeNo()), GzPayTransaction::getOutTradeNo, query.getOutTradeNo())
            .eq(StrUtil.isNotBlank(query.getTransactionId()), GzPayTransaction::getTransactionId, query.getTransactionId())
            .orderByDesc(GzPayTransaction::getId);
        Page<GzPayTransactionVO> page = transactionMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    @Override
    public GzPayTransactionVO getByOutTradeNo(String outTradeNo) {
        GzPayTransaction tx = transactionMapper.selectByOutTradeNo(outTradeNo);
        return tx == null ? null : MapstructUtils.convert(tx, GzPayTransactionVO.class);
    }

    @Override
    public GzPayTransactionVO getById(Long id) {
        return transactionMapper.selectVoById(id);
    }
}
