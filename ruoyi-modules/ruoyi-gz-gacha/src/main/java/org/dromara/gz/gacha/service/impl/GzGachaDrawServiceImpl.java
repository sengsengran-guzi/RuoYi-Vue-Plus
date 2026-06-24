package org.dromara.gz.gacha.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.gacha.domain.dto.GachaSnapshot;
import org.dromara.gz.gacha.domain.entity.GzGachaDraw;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaOrder;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.domain.vo.GachaStartDrawVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawHistoryVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawMachineFilterVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawStatusVo;
import org.dromara.gz.gacha.enums.GachaMachineStatusEnum;
import org.dromara.gz.gacha.exception.GachaInternalException;
import org.dromara.gz.gacha.exception.GzGachaErrorCode;
import org.dromara.gz.gacha.mapper.GzGachaDrawMapper;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaOrderMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzUserGachaCollectionMapper;
import org.dromara.gz.gacha.service.IGzGachaDrawService;
import org.dromara.gz.gacha.service.IGzGachaProductService;
import org.dromara.gz.gacha.service.internal.ProbabilityNormalizer;
import org.dromara.gz.gacha.service.internal.ProbabilityNormalizer.NormalizeResult;
import org.dromara.gz.gacha.service.internal.SecureRandomDrawer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扭蛋开盒服务实现（GZ-GACHA-104 ⭐，doc/10 §8.N4-N6 / §8.E5）。
 *
 * <p><b>业务模型钉死</b>（README §A）：付款必出 1 件实物；缺货付款前拦截（{@code MACHINE_EMPTY} 不收款），
 * 付款后必有货；并发扣减失败 = 从剩余有货池重抽改派其他商品（绝不退款）。<b>扭蛋域无系统自动退款</b> ——
 * 本类不写任何退款逻辑 / 不触 gz_pay_refund。</p>
 *
 * <p><b>三层防超卖</b>（doc/10 §8.N6 / 强约束 #1）：DB 事务 + {@code SELECT FOR UPDATE} 锁奖品行 +
 * {@code UPDATE stock_remain WHERE version=? AND stock_remain>0}（乐观锁）。防同一商品超卖；失败后果 =
 * 改派其他商品重抽。</p>
 *
 * <p><b>开盒上下文恢复</b>（决策）：{@code draw/start} 把 machineId 写 Redis（key=out_trade_no，TTL 30min），
 * 异步开盒事务据 payTransactionId（= out_trade_no）从 Redis 取 machineId + 从 gz_pay_transaction 取
 * userId/amount —— 不另建「开盒意图表」（draw 仅在成功时落，doc/11 F7.3，AC1 仅建 3 表）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzGachaDrawServiceImpl implements IGzGachaDrawService {

    /** 揭晓图解析失败 / 素材未到位时的 C 系统占位图（待甲方素材替换，同 gz-ord / gacha 详情口径） */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    private final GzGachaMachineMapper machineMapper;
    private final GzGachaPrizeMapper prizeMapper;
    private final GzGachaDrawMapper drawMapper;
    private final GzGachaOrderMapper orderMapper;
    private final GzUserGachaCollectionMapper collectionMapper;
    private final ProbabilityNormalizer probabilityNormalizer;
    private final SecureRandomDrawer secureRandomDrawer;
    private final IGzPayTransactionService payTransactionService;
    private final IGzUserService userService;
    /** 揭晓图片签名 URL 解析（GACHA-105：snapshot.imageId → 可访问 URL；NULL / 失败 → 占位，同 gz-ord 口径） */
    private final IGzFileService fileService;
    /** 产品库（ADR-0013：快照名/图/参考价取产品，rarity 取投放线） */
    private final IGzGachaProductService productService;
    /** 整机售罄 auto_off（独立 Bean，after-commit 跨 Bean 调用使 REQUIRES_NEW 真生效，AC7） */
    private final org.dromara.gz.gacha.service.internal.GachaMachineAutoOffService autoOffService;
    /** 开盒意图存储（封 RedisUtils 静态，便于单测 mock；out_trade_no → machineId，TTL 30min） */
    private final org.dromara.gz.gacha.service.internal.GachaDrawIntentStore drawIntentStore;
    /** snapshot JSON 序列化（注入而非 JsonUtils 静态，便于单测；同 gz-ord 口径） */
    private final ObjectMapper objectMapper;
    /**
     * 自身代理（@Lazy 断构造期自引用环）：{@code @Async} 入口经此代理调 {@code runDrawTransaction}，
     * 使 {@code @Transactional} 真生效（自调用绕过代理则事务/异步注解失效）。
     */
    private final org.springframework.beans.factory.ObjectProvider<IGzGachaDrawService> selfProvider;

    // ============================================================
    //  AC2 + AC2.5 — mp 投币开盒（付款前缺货拦截 → 建支付单 → 记意图）
    // ============================================================

    @Override
    public GachaStartDrawVo startDraw(Long machineId, Long userId) {
        // ① 机器存在 + on_shelf（doc/10 §8.N4）
        GzGachaMachine machine = machineMapper.selectById(machineId);
        if (machine == null) {
            throw new ServiceException(GzGachaErrorCode.MACHINE_NOT_FOUND_MSG, GzGachaErrorCode.MACHINE_NOT_FOUND);
        }
        if (!GachaMachineStatusEnum.ON_SHELF.getCode().equals(machine.getStatus())) {
            // 非上架（off_shelf / auto_off）→ 视同缺货拦截，不收款
            throw new ServiceException(GzGachaErrorCode.MACHINE_EMPTY_MSG, GzGachaErrorCode.MACHINE_EMPTY);
        }

        // ② 付款前缺货拦截（AC2.5 / §8.E1）：至少 1 个 prize enabled=1 AND stock_remain>0（不收款前提）
        long availableCount = prizeMapper.selectCount(Wrappers.<GzGachaPrize>lambdaQuery()
            .eq(GzGachaPrize::getMachineId, machineId)
            .eq(GzGachaPrize::getEnabled, 1)
            .gt(GzGachaPrize::getStockRemain, 0));
        if (availableCount <= 0) {
            throw new ServiceException(GzGachaErrorCode.MACHINE_EMPTY_MSG, GzGachaErrorCode.MACHINE_EMPTY);
        }

        // ③ 取下单用户 openid（统一下单必需）
        GzUserVO user = userService.selectVoById(userId);
        if (user == null || StrUtil.isBlank(user.getOpenid())) {
            throw new ServiceException(GzGachaErrorCode.USER_INVALID_MSG, GzGachaErrorCode.USER_INVALID);
        }

        // ④ 调 PAY-101 建 gz_pay_transaction(business_type='gacha')，拿 out_trade_no(GACHA-) + mp 5 参
        //    金额单位分 = machine.single_price_cent（后端取，不信前端）
        long amountCent = machine.getSinglePriceCent();
        CreateOrderBo payBo = CreateOrderBo.builder()
            .businessType(PayBusinessType.GACHA)
            .businessOrderNo(null) // 扭蛋开盒成功才建衍生订单，建支付单时无 business_order_no（draw 不预建，F7.3）
            .amountCent(amountCent)
            .openid(user.getOpenid())
            .userId(userId)
            .description("谷子宇宙扭蛋 - " + truncate(machine.getName(), 32))
            .build();
        MpPayParamsVO payParams = payTransactionService.createBusinessOrder(payBo);
        String outTradeNo = payParams.getOutTradeNo();

        // ⑤ 记开盒意图（machineId）入 Redis，供异步开盒恢复（key=out_trade_no，TTL 30min）
        drawIntentStore.save(outTradeNo, machineId);

        log.info("[gz-gacha-draw] startDraw ok machineId={} userId={} amountCent={} outTradeNo={}（付款前缺货已过，待回调开盒）",
            machineId, userId, amountCent, outTradeNo);

        return GachaStartDrawVo.builder()
            .outTradeNo(outTradeNo)
            .payParams(payParams)
            .build();
    }

    // ============================================================
    //  AC4 + AC5 — 核心开盒事务（4 表原子，必出 1 件，失败重抽不退款）
    // ============================================================

    @Async(org.dromara.gz.gacha.config.GachaExecutorConfig.GACHA_EXECUTOR)
    @Override
    public void executeDrawTransaction(String payTransactionId) {
        // @Async 派发线程：经自身代理调事务核心（@Transactional 在 runDrawTransaction 真生效）。
        // 失败由 @Async 线程内异常处理；下次回调 / PAY-102 查单兜底重试（幂等 pay_transaction_id 唯一索引）。
        selfProvider.getObject().runDrawTransaction(payTransactionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void runDrawTransaction(String payTransactionId) {
        // 步骤 1 — 幂等：pay_transaction_id 已落 draw → 直接 return（微信重推 / @Async 重入，强约束 #3）
        Long existedDrawId = drawMapper.selectIdByPayTransactionId(payTransactionId);
        if (existedDrawId != null) {
            log.info("[gz-gacha-draw] 幂等跳过（开盒记录已存在）drawId={} payTransactionId={}", existedDrawId, payTransactionId);
            return;
        }

        // 恢复开盒上下文：machineId（Redis 意图）+ userId/amount（gz_pay_transaction）
        DrawContext ctx = loadContext(payTransactionId);

        // 步骤 2 — SELECT FOR UPDATE 锁所有在售有货候选行（锁奖品行不锁机器行，决策 D1）
        List<GzGachaPrize> locked = prizeMapper.selectInPoolForUpdate(ctx.machineId());
        if (locked == null || locked.isEmpty()) {
            // 理论不可达（付款前已拦缺货 + FOR UPDATE 锁内库存不被外部抢）→ 系统级异常走 §6.E2 重试（非退款）
            throw new GachaInternalException("开盒事务候选为空（付款前缺货拦截应已保证非空）machineId="
                + ctx.machineId() + " payTransactionId=" + payTransactionId);
        }

        // 步骤 3 — 有界重抽循环（最多候选数次）：归一化 → 随机出 1 件 → 乐观锁扣减；affected=0 → 移出候选重抽
        List<GzGachaPrize> candidates = new ArrayList<>(locked);
        GzGachaPrize won = null;
        int attempts = 0;
        int maxAttempts = candidates.size();
        while (won == null && !candidates.isEmpty() && attempts < maxAttempts) {
            attempts++;
            NormalizeResult normalized = probabilityNormalizer.normalize(candidates);
            GzGachaPrize picked = secureRandomDrawer.draw(normalized);
            int affected = prizeMapper.deductStock(picked.getId(), picked.getVersion());
            if (affected == 1) {
                won = picked;
                // 内存同步扣减后状态（snapshot 用，非必须）
                won.setStockRemain(won.getStockRemain() - 1);
                won.setVersion(won.getVersion() + 1);
            } else {
                // affected=0：被并发抢空 → 移出候选 + 回到循环顶重新归一化重抽（绝不退款，§8.E5）
                candidates.removeIf(p -> p.getId().equals(picked.getId()));
                log.info("[gz-gacha-draw] 乐观锁扣减失败（被并发抢空）改派重抽 prizeId={} 剩余候选={} payTransactionId={}",
                    picked.getId(), candidates.size(), payTransactionId);
            }
        }
        if (won == null) {
            // 候选取空仍未成功（理论不可达，见 GachaInternalException 注释）→ ROLLBACK 走 §6.E2 重试（非退款）
            throw new GachaInternalException("开盒重抽耗尽候选仍未扣减成功（理论不可达）machineId="
                + ctx.machineId() + " attempts=" + attempts + " payTransactionId=" + payTransactionId);
        }

        // 加载机器（snapshot 用）
        GzGachaMachine machine = machineMapper.selectById(ctx.machineId());
        // 加载获得物产品（ADR-0013：快照名/图/参考价取产品；产品被删/取不到 → 字段留空，rarity 仍取线）
        GzGachaProduct wonProduct = productService.getById(won.getProductId());
        String machineSnapshot = writeJson(buildMachineSnapshot(machine));
        String prizeSnapshot = writeJson(buildPrizeSnapshot(won, wonProduct));
        LocalDateTime drawnTime = ctx.paidTime() != null ? ctx.paidTime() : LocalDateTime.now();

        // 步骤 4 — INSERT gz_gacha_draw（draw_no + 获得物 + snapshot + 幂等 pay_transaction_id）
        GzGachaDraw draw = insertDraw(ctx, won, machineSnapshot, prizeSnapshot, drawnTime, payTransactionId);

        // 步骤 5 — 事务内 UPSERT gz_user_gacha_collection（图鉴 +1；tenant_id 取自锁行（won.tenantId），@Async 无租户上下文）
        collectionMapper.upsertCollection(won.getTenantId(), ctx.userId(), ctx.machineId(), won.getId());

        // 步骤 6 — INSERT gz_gacha_order(pending_ship + in_japan)
        insertOrder(ctx, draw, machineSnapshot, prizeSnapshot, drawnTime, payTransactionId);

        // 累计机器销量（原子 UPDATE，不读改写）
        machineMapper.increaseSalesCount(ctx.machineId(), 1);

        // 步骤 7 — COMMIT（@Transactional 边界，4 表变更原子）
        log.info("[gz-gacha-draw] 开盒成功 drawNo={} prizeId={} rarity={} machineId={} userId={} payTransactionId={}",
            draw.getDrawNo(), won.getId(), won.getRarity(), ctx.machineId(), ctx.userId(), payTransactionId);

        // 清理意图（删 Redis，最终一致；失败不影响 — 幂等兜底 pay_transaction_id 唯一索引）
        drawIntentStore.clear(payTransactionId);

        // AC7 — 事务提交后检测整机售罄 → auto_off（决策 D6：提交后触发，不延长本事务对 prize 行锁的持有时间）。
        // 注册 after-commit 回调（在 @Transactional 提交后执行）；无同步上下文（理论不会，本方法 @Transactional）则直接调。
        Long machineId = ctx.machineId();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        autoOffService.autoOffIfEmpty(machineId);
                    }
                });
        } else {
            autoOffService.autoOffIfEmpty(machineId);
        }
    }

    // ============================================================
    //  GACHA-105 — mp 揭晓状态轮询（仅查不改，驱动三段分镜动画）
    // ============================================================

    @Override
    public GzGachaDrawStatusVo getStatusForMp(String payTransactionId, String drawNo, Long userId) {
        // 按 pay_transaction_id 优先（mp start 返回 outTradeNo）；兜底 draw_no
        GzGachaDraw draw = null;
        if (StrUtil.isNotBlank(payTransactionId)) {
            draw = drawMapper.selectByPayTransactionId(payTransactionId);
        }
        if (draw == null && StrUtil.isNotBlank(drawNo)) {
            draw = drawMapper.selectByDrawNo(drawNo);
        }

        GzGachaDrawStatusVo vo = new GzGachaDrawStatusVo();

        // 查不到 draw ⇒ 付款后开盒事务异步进行中（draw 仅成功时落，doc/11 F7.3）→ drawing。
        // 付款必出 1 件，事务只会 drawing→drawn，无 refunded 揭晓态（扭蛋域无系统退款）。
        if (draw == null) {
            vo.setStatus("drawing");
            return vo;
        }

        // 归属校验：draw 已落但非本人 → 拒（不可查他人开盒结果）
        if (!userId.equals(draw.getUserId())) {
            throw new ServiceException(GzGachaErrorCode.DRAW_FORBIDDEN_MSG, GzGachaErrorCode.DRAW_FORBIDDEN);
        }

        // drawn：解 prize_snapshot_json → 揭晓数据 + 图片签名 URL（防后台改商品后历史读不准，决策 D4）
        vo.setStatus("drawn");
        vo.setDrawNo(draw.getDrawNo());
        vo.setMachineId(draw.getMachineId());
        vo.setPrize(buildRevealPrize(draw));
        return vo;
    }

    // ============================================================
    //  GZ-GACHA-106 — mp「我开过的」开盒历史（分页 + 机器筛选 chip）
    // ============================================================

    @Override
    public TableDataInfo<GzGachaDrawHistoryVo> listMyHistory(Long userId, Long machineId, PageQuery pageQuery) {
        if (userId == null) {
            return TableDataInfo.build(new Page<>());
        }
        // 走索引 (tenant_id, user_id, drawn_time DESC)：user_id 等值 + drawn_time DESC 排序（强约束 / R1）
        LambdaQueryWrapper<GzGachaDraw> lqw = Wrappers.<GzGachaDraw>lambdaQuery()
            .eq(GzGachaDraw::getUserId, userId)
            .eq(machineId != null, GzGachaDraw::getMachineId, machineId)
            .orderByDesc(GzGachaDraw::getDrawnTime)
            .orderByDesc(GzGachaDraw::getId);
        Page<GzGachaDraw> page = drawMapper.selectPage(pageQuery.build(), lqw);

        List<GzGachaDraw> rows = page.getRecords();
        // 当前页 draw → 衍生订单 business_status（一抽一单，批量取避免 N+1，AC2）
        Map<Long, String> statusByDrawId = loadOrderStatusByDrawIds(rows);

        Page<GzGachaDrawHistoryVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<GzGachaDrawHistoryVo> voList = new ArrayList<>(rows.size());
        for (GzGachaDraw d : rows) {
            voList.add(toHistoryVo(d, statusByDrawId.get(d.getId())));
        }
        voPage.setRecords(voList);
        return TableDataInfo.build(voPage);
    }

    @Override
    public List<GzGachaDrawMachineFilterVo> listMyHistoryMachines(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = drawMapper.selectMyHistoryMachines(userId);
        List<GzGachaDrawMachineFilterVo> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object midObj = row.get("machineId");
            if (midObj == null) {
                continue;
            }
            GzGachaDrawMachineFilterVo vo = new GzGachaDrawMachineFilterVo();
            vo.setMachineId(((Number) midObj).longValue());
            // machineName 解自该机器最近一条 draw 的 machine_snapshot_json（非实时 join，与列表口径一致）
            GachaSnapshot.Machine snap = readJson((String) row.get("machineSnapshotJson"), GachaSnapshot.Machine.class);
            vo.setMachineName(snap == null ? null : snap.getName());
            list.add(vo);
        }
        return list;
    }

    /** 当前页 draw → 衍生订单 business_status 映射（批量取，避免 N+1；空页短路）。 */
    private Map<Long, String> loadOrderStatusByDrawIds(List<GzGachaDraw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        List<Long> drawIds = rows.stream().map(GzGachaDraw::getId).toList();
        List<Map<String, Object>> orderRows = orderMapper.selectStatusByDrawIds(drawIds);
        Map<Long, String> map = new HashMap<>(orderRows.size() * 2);
        for (Map<String, Object> row : orderRows) {
            Object did = row.get("drawId");
            if (did != null) {
                map.put(((Number) did).longValue(), (String) row.get("businessStatus"));
            }
        }
        return map;
    }

    /**
     * draw 行 → 历史列表 VO（snapshot 解扁平字段 + 图片签名 URL，决策 D6）。
     *
     * <p>图片 URL 解析顺序（强约束 #4 / AC2）：prize snapshot.imageId → 空回退 machine snapshot.coverImageId
     * → 仍空 / 解析失败 → 占位图（同揭晓口径 {@link #resolveRevealImageUrl}）。snapshot 异常（理论不可达）→
     * 仅图片占位，文案字段尽力解（不抛错，历史展示不应因单行异常中断）。</p>
     *
     * @param d              开盒记录
     * @param businessStatus 关联衍生订单业务态（可空：无衍生订单 / 未匹配）
     */
    private GzGachaDrawHistoryVo toHistoryVo(GzGachaDraw d, String businessStatus) {
        GzGachaDrawHistoryVo vo = new GzGachaDrawHistoryVo();
        vo.setDrawId(d.getId());
        vo.setDrawNo(d.getDrawNo());
        vo.setMachineId(d.getMachineId());
        vo.setDrawnTime(d.getDrawnTime());
        vo.setBusinessStatus(businessStatus);

        GachaSnapshot.Machine machineSnap = readJson(d.getMachineSnapshotJson(), GachaSnapshot.Machine.class);
        if (machineSnap != null) {
            vo.setMachineName(machineSnap.getName());
        }
        GachaSnapshot.Prize prizeSnap = readJson(d.getPrizeSnapshotJson(), GachaSnapshot.Prize.class);
        if (prizeSnap != null) {
            vo.setPrizeName(prizeSnap.getName());
            vo.setRarity(prizeSnap.getRarity());
            vo.setReferenceValueCent(prizeSnap.getReferenceValueCent());
            vo.setPrizeImageUrl(resolveRevealImageUrl(prizeSnap.getImageId(), d.getMachineSnapshotJson()));
        } else {
            // prize snapshot 异常（理论不可达）→ 图片占位兜底，不抛错
            vo.setPrizeImageUrl(PLACEHOLDER_IMAGE_URL);
        }
        return vo;
    }

    /**
     * 由 draw 的 snapshot JSON 构造揭晓 Prize VO。
     *
     * <p>图片 URL 解析顺序（AC4）：prize snapshot.imageId → 空回退 machine snapshot.coverImageId →
     * 仍空 / 解析失败 → 占位图。snapshot.imageId 为 string（跨层契约），换签名 URL 前 parseLong。</p>
     */
    private GzGachaDrawStatusVo.Prize buildRevealPrize(GzGachaDraw draw) {
        GachaSnapshot.Prize prizeSnap = readJson(draw.getPrizeSnapshotJson(), GachaSnapshot.Prize.class);
        GzGachaDrawStatusVo.Prize p = new GzGachaDrawStatusVo.Prize();
        if (prizeSnap != null) {
            p.setName(prizeSnap.getName());
            p.setRarity(prizeSnap.getRarity());
            p.setReferenceValueCent(prizeSnap.getReferenceValueCent());
            p.setImageUrl(resolveRevealImageUrl(prizeSnap.getImageId(), draw.getMachineSnapshotJson()));
        } else {
            // snapshot 异常（理论不可达）→ 占位兜底，不抛错（揭晓动画不应因图片失败而中断）
            p.setImageUrl(PLACEHOLDER_IMAGE_URL);
        }
        return p;
    }

    /**
     * 揭晓图 URL：prize imageId → 空回退机器封面 coverImageId → 占位（AC4）。
     *
     * @param prizeImageIdStr   prize snapshot imageId（string，可空）
     * @param machineSnapshotJson 机器 snapshot JSON（回退取 coverImageId）
     */
    private String resolveRevealImageUrl(String prizeImageIdStr, String machineSnapshotJson) {
        String url = presignedOrNull(prizeImageIdStr);
        if (url != null) {
            return url;
        }
        // 回退机器封面
        GachaSnapshot.Machine machineSnap = readJson(machineSnapshotJson, GachaSnapshot.Machine.class);
        if (machineSnap != null) {
            String coverUrl = presignedOrNull(machineSnap.getCoverImageId());
            if (coverUrl != null) {
                return coverUrl;
            }
        }
        return PLACEHOLDER_IMAGE_URL;
    }

    /** fileId(string) → 签名 URL；空 / 非数字 / 解析失败 → null（由调用方决定回退）。 */
    private String presignedOrNull(String fileIdStr) {
        if (StrUtil.isBlank(fileIdStr)) {
            return null;
        }
        try {
            Long fileId = Long.valueOf(fileIdStr);
            return fileService.getPresignedUrl(fileId).getUrl();
        } catch (Exception ex) {
            log.warn("[gz-gacha-draw] 揭晓图解析失败 fileId={}，回退：{}", fileIdStr, ex.getMessage());
            return null;
        }
    }

    private <T> T readJson(String json, Class<T> clazz) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("[gz-gacha-draw] snapshot 反序列化失败 type={}：{}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ============================================================
    //  内部：上下文恢复 / snapshot / 落库 / auto_off
    // ============================================================

    /** 恢复开盒上下文（machineId from Redis intent + userId/amount/paidTime from gz_pay_transaction）。 */
    private DrawContext loadContext(String payTransactionId) {
        GzPayTransactionVO txn = payTransactionService.getByOutTradeNo(payTransactionId);
        if (txn == null) {
            throw new GachaInternalException("开盒事务找不到支付订单 payTransactionId=" + payTransactionId);
        }
        Long machineId = drawIntentStore.getMachineId(payTransactionId);
        if (machineId == null) {
            // Redis 意图过期 / 丢失（极端：回调延迟 > 30min 或 Redis 重启）→ 系统级异常走 §6.E2 重试（非退款）
            throw new GachaInternalException("开盒意图丢失（Redis 无 machineId）payTransactionId=" + payTransactionId);
        }
        return new DrawContext(machineId, txn.getUserId(), txn.getAmountCent(), txn.getPaidTime());
    }

    private GachaSnapshot.Machine buildMachineSnapshot(GzGachaMachine m) {
        return GachaSnapshot.Machine.builder()
            .machineId(String.valueOf(m.getId()))
            .machineNo(m.getMachineNo())
            .name(m.getName())
            .coverImageId(m.getCoverImageId() == null ? null : String.valueOf(m.getCoverImageId()))
            .build();
    }

    /**
     * 获得物快照（ADR-0013）：name / imageId / referenceValueCent 取产品库（固有属性），rarity 取投放线
     * （按机器可调）。产品被删 / 取不到（理论：开盒瞬间产品仍在）→ 名/图/参考价留空，rarity 仍落 —— 快照隔离
     * 历史，落库后不再受改表影响。
     */
    private GachaSnapshot.Prize buildPrizeSnapshot(GzGachaPrize p, GzGachaProduct product) {
        return GachaSnapshot.Prize.builder()
            .prizeId(String.valueOf(p.getId()))
            .prizeNo(p.getPrizeNo())
            .name(product == null ? null : product.getName())
            .imageId(product == null || product.getImageId() == null ? null : String.valueOf(product.getImageId()))
            .rarity(p.getRarity())
            .referenceValueCent(product == null ? null : product.getReferenceValueCent())
            .build();
    }

    /**
     * INSERT draw。
     *
     * <p><b>draw_no 派生自 out_trade_no 唯一后缀</b>（决策）：out_trade_no = {@code GACHA-yyyyMMdd-NNNNNN}
     * 全局唯一（PAY-101 序号 + DB UNIQUE 兜底）；draw_no = {@code DRW-yyyyMMdd-NNNNNN} 复用同后缀 →
     * <b>1 笔支付 1 个 draw_no，天然唯一无并发冲突</b>。不再用「当日 MAX+1」（N 并发未提交时各读同一 max
     * → 全撞 UNIQUE，压测实证翻车）。pay_transaction_id 唯一索引兜底幂等。</p>
     */
    private GzGachaDraw insertDraw(DrawContext ctx, GzGachaPrize won, String machineSnapshot,
                                   String prizeSnapshot, LocalDateTime drawnTime, String payTransactionId) {
        GzGachaDraw draw = new GzGachaDraw();
        draw.setDrawNo(deriveNo("DRW-", payTransactionId));
        draw.setUserId(ctx.userId());
        draw.setMachineId(ctx.machineId());
        draw.setPrizeId(won.getId());
        draw.setPayTransactionId(payTransactionId);
        draw.setMachineSnapshotJson(machineSnapshot);
        draw.setPrizeSnapshotJson(prizeSnapshot);
        draw.setDrawAmountCent(ctx.amountCent());
        draw.setDrawnTime(drawnTime);
        draw.setVersion(0);
        try {
            drawMapper.insert(draw);
            return draw;
        } catch (DuplicateKeyException dup) {
            // draw_no / pay_transaction_id 撞 → 幂等：同笔支付已被别的回调先落开盒（微信重推 / @Async 重入）→
            // 视为幂等冲突，向上抛 ROLLBACK（步骤 1 幂等已先拦，此处兜底；非超卖、非退款）。
            throw new GachaInternalException("开盒记录幂等冲突（draw_no / pay_transaction_id 已存在）payTransactionId="
                + payTransactionId, dup);
        }
    }

    /**
     * INSERT order(pending_ship + in_japan)。
     *
     * <p><b>order_no 派生自 out_trade_no 唯一后缀</b>：order_no = {@code GACHA-yyyyMMdd-NNNNNN} 即等于
     * out_trade_no（1 笔支付 1 单，同口径全局唯一）；draw_id / pay_transaction_id 唯一索引兜底幂等。</p>
     */
    private void insertOrder(DrawContext ctx, GzGachaDraw draw, String machineSnapshot,
                             String prizeSnapshot, LocalDateTime drawnTime, String payTransactionId) {
        GzGachaOrder order = new GzGachaOrder();
        order.setOrderNo(deriveNo("GACHA-", payTransactionId));
        order.setDrawId(draw.getId());
        order.setUserId(ctx.userId());
        order.setMachineSnapshotJson(machineSnapshot);
        order.setPrizeSnapshotJson(prizeSnapshot);
        order.setTotalAmountCent(ctx.amountCent());
        order.setAddressSnapshotJson(null); // 开盒时不强制选地址，mp 订单详情补（F7.2）
        order.setBusinessStatus("pending_ship");
        order.setLogisticsStatus("in_japan");
        order.setPayTransactionId(payTransactionId);
        order.setPaidTime(drawnTime);
        order.setVersion(0);
        try {
            orderMapper.insert(order);
            log.info("[gz-gacha-draw] 衍生订单落库 orderNo={} drawId={} status=pending_ship/in_japan", order.getOrderNo(), draw.getId());
        } catch (DuplicateKeyException dup) {
            throw new GachaInternalException("衍生订单幂等冲突（order_no / draw_id / pay_tx 已存在）payTransactionId="
                + payTransactionId, dup);
        }
    }

    // ----- 工具 -----

    /**
     * 由 out_trade_no（{@code GACHA-yyyyMMdd-NNNNNN}）派生业务号（替换前缀）。
     *
     * <p>draw_no = {@code DRW-yyyyMMdd-NNNNNN}；order_no = {@code GACHA-yyyyMMdd-NNNNNN}（= out_trade_no）。
     * out_trade_no 全局唯一 → 派生号天然唯一，无并发 MAX+1 冲突。</p>
     */
    private String deriveNo(String prefix, String payTransactionId) {
        int dash = payTransactionId.indexOf('-');
        // 去掉 GACHA- 前缀，保留 yyyyMMdd-NNNNNN；异常格式兜底用原值（仍唯一）
        String suffix = dash >= 0 ? payTransactionId.substring(dash + 1) : payTransactionId;
        return prefix + suffix;
    }

    private String writeJson(Object snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new GachaInternalException("snapshot 序列化失败", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 开盒上下文（恢复自 Redis 意图 + gz_pay_transaction）。 */
    private record DrawContext(Long machineId, Long userId, Long amountCent, LocalDateTime paidTime) {
    }
}
