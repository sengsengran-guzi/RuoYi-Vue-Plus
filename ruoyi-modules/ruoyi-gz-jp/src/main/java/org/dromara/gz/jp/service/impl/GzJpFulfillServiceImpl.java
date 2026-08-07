package org.dromara.gz.jp.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.DictService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.domain.bo.GzJpFulfillAdvanceBo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillQueryBo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillShipBo;
import org.dromara.gz.jp.domain.dto.GzJpFulfillBoardRow;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpFulfillRejectReason;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBatchResultVO;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBoardItemVO;
import org.dromara.gz.jp.domain.vo.GzJpFulfillRejectVO;
import org.dromara.gz.jp.exception.GzJpFulfillErrorCode;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.service.IGzJpFulfillService;
import org.dromara.gz.jp.service.internal.GzJpFulfillStateMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 履约推进服务实现（GZ-JP-106，FLOW:F-JP-03.step2/step3）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_order_item} 段
 * （{@code fulfill_status} / {@code carrier_code} / {@code tracking_no} / {@code shipped_at}）。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li><b>状态判定全部委托 {@link GzJpFulfillStateMachine}</b> —— 本类只负责取数、加锁、分组、写、计数。
 *       状态链一旦有第二处 if，两处迟早不一致。</li>
 *   <li><b>先 FOR UPDATE 锁行，再判定，再按当前状态分组写</b>：批量里各行状态不同（有的还在购买中、
 *       有的已到清关），一条 SQL 带不动，所以按 {@code fulfill_status} 分组、每组一条带守卫的 UPDATE。
 *       守卫 affected 与预期不符 = 有人在锁外改了数据 → <b>整批回滚</b>而不是糊一个近似计数。</li>
 *   <li><b>ids 去重 + 升序</b>：加锁顺序一致才不会死锁（两个店员勾了重叠的行是真实场景）。</li>
 *   <li><b>部分成功 + 逐行原因</b>：批量里混入同事刚推过的 / 客人没付钱的 / 已发出的属常态，
 *       整批失败会逼着店员一行行试。只有「一行没动且无幂等跳过」才抛 4108。</li>
 *   <li><b>发货必须一次写齐状态 + 单号 + 时间</b>：分两步写会留下「已 delivered 没单号」的坏数据，
 *       而 accept 第 2 条正是断言这种行 0 条。</li>
 *   <li><b>不写 cron</b>：SnailJob 在 prod 未部署，任何「自动推进」都得是 admin 手动或读时惰性。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpFulfillServiceImpl implements IGzJpFulfillService {

    /** 单次批量上限（与商品批量上下架同口径，防一次性刷全表） */
    private static final int BATCH_MAX = 200;

    /** 单条 UPDATE 的 IN 列表切片大小（200 上限下最多 2 片；避免超长 SQL） */
    private static final int SQL_CHUNK = 100;

    /** 看板分页默认 / 上限 */
    private static final int BOARD_PAGE_DEFAULT = 20;
    private static final int BOARD_PAGE_MAX = 200;

    /** 国内快递公司字典（既有字典，GZ-JP-106 复用不新建） */
    private static final String DICT_EXPRESS_CARRIER = "gz_express_carrier";

    /**
     * 运单号格式闸：字母数字开头，允许 - 与 _，4~64 位。
     *
     * <p>只挡明显手滑（空格 / 中文 / 一两个字符），不做各家快递的精确校验 ——
     * 挡过头会让店员根本发不了货，而单号错了客人自己查不到就会反馈。</p>
     */
    private static final Pattern TRACKING_NO_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9\\-_]{3,63}$");

    private final GzJpOrderItemMapper itemMapper;
    private final GzJpOrderMapper orderMapper;
    private final IGzUserService userService;
    private final DictService dictService;
    /** Spring 全局 ObjectMapper（解商品快照；构造注入以便单测替换） */
    private final ObjectMapper objectMapper;

    // ============================================================
    //  批量推进（FLOW:F-JP-03.step2）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzJpFulfillBatchResultVO advance(GzJpFulfillAdvanceBo bo, Long operatorId) {
        if (bo == null) {
            throw new ServiceException("请求参数为空");
        }
        List<Long> ids = normalizeIds(bo.getItemIds());
        String target = StrUtil.trimToEmpty(bo.getTargetStatus());

        if (!GzJpFulfillStatus.isValid(target)) {
            throw new ServiceException("非法的目标状态：" + target);
        }
        // ★ AC「置 delivered 未填单号被拒」的第一处落点 —— 拦在读库之前，一行都不会被改
        if (GzJpFulfillStatus.DELIVERED.getCode().equals(target)) {
            throw new ServiceException(GzJpFulfillRejectReason.SHIP_REQUIRED.getMessage(),
                GzJpFulfillErrorCode.SHIP_TRACKING_REQUIRED);
        }

        GzJpFulfillBatchResultVO result = applyBatch(ids, target, null, null, operatorId);
        log.info("[gz-jp-fulfill] ADVANCE target={} requested={} advanced={} skipped={} rejected={} operator={}",
            target, result.getRequested(), result.getAdvanced(), result.getSkipped(), result.getRejected(), operatorId);
        return result;
    }

    // ============================================================
    //  批量发货（FLOW:F-JP-03.step3）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzJpFulfillBatchResultVO ship(GzJpFulfillShipBo bo, Long operatorId) {
        if (bo == null) {
            throw new ServiceException("请求参数为空");
        }
        List<Long> ids = normalizeIds(bo.getItemIds());
        String carrier = StrUtil.trimToEmpty(bo.getCarrierCode());
        String trackingNo = StrUtil.trimToEmpty(bo.getTrackingNo());

        // ★ AC「置 delivered 未填单号被拒」的第二处落点：缺一即拒，绝不「先发货再补单号」
        if (StrUtil.isBlank(carrier) || StrUtil.isBlank(trackingNo)) {
            throw new ServiceException("发货必须同时填写快递公司与运单号",
                GzJpFulfillErrorCode.SHIP_TRACKING_REQUIRED);
        }
        if (!TRACKING_NO_PATTERN.matcher(trackingNo).matches()) {
            throw new ServiceException("运单号格式不正确（4~64 位字母、数字或短横线）",
                GzJpFulfillErrorCode.SHIP_TRACKING_REQUIRED);
        }
        String carrierLabel = resolveCarrierLabel(carrier);

        GzJpFulfillBatchResultVO result = applyBatch(ids, GzJpFulfillStatus.DELIVERED.getCode(),
            carrier, trackingNo, operatorId);
        result.setCarrierCode(carrier);
        result.setCarrierLabel(carrierLabel);
        result.setTrackingNo(trackingNo);
        log.info("[gz-jp-fulfill] SHIP carrier={} trackingNo={} requested={} advanced={} rejected={} operator={}",
            carrier, trackingNo, result.getRequested(), result.getAdvanced(), result.getRejected(), operatorId);
        return result;
    }

    // ============================================================
    //  批量写的公共骨架
    // ============================================================

    /**
     * 批量推进 / 批量发货共用的执行骨架。
     *
     * <p>顺序刻意如此：① 按 id 升序 FOR UPDATE 锁行（防死锁 + 让判定基于最新值）→
     * ② 批量取订单付款态 → ③ 逐行判定（发货另加「同一客人」闸）→
     * ④ 按当前状态分组、带守卫写 → ⑤ 汇总。</p>
     *
     * @param ids        已去重升序的行 id
     * @param target     目标状态
     * @param carrier    快递编码（null = 走推进而非发货）
     * @param trackingNo 运单号（null = 走推进）
     * @param operatorId 操作人
     * @return 执行结果
     */
    private GzJpFulfillBatchResultVO applyBatch(List<Long> ids, String target,
                                                String carrier, String trackingNo, Long operatorId) {
        boolean ship = carrier != null;

        // ① 悲观锁加载（ids 已升序 → 并发重叠请求的加锁顺序一致 → 不死锁）
        List<GzJpOrderItem> rows = itemMapper.selectByIdsForUpdate(ids);
        Map<Long, GzJpOrderItem> rowMap = new HashMap<>(rows.size());
        for (GzJpOrderItem row : rows) {
            rowMap.put(row.getId(), row);
        }

        // ② 订单付款态（★ 未支付订单的行 fulfill_status 也是 purchasing，必须靠订单判定）
        Map<Long, String> orderStatusMap = loadOrderStatus(rows);

        // ③ 发货专属闸：一个运单号 = 一个包裹 = 一个收件人
        if (ship) {
            assertSingleCustomer(ids, rowMap, orderStatusMap, trackingNo);
        }

        GzJpFulfillBatchResultVO result = new GzJpFulfillBatchResultVO();
        result.setTargetStatus(target);
        result.setTargetStatusLabel(GzJpFulfillStatus.labelOf(target));
        result.setRequested(ids.size());

        // 按「当前状态」分组：同组才能共用一条带守卫的 UPDATE
        Map<String, List<Long>> todoByFrom = new LinkedHashMap<>();
        int skipped = 0;

        for (Long id : ids) {
            GzJpOrderItem row = rowMap.get(id);
            if (row == null) {
                result.getRejects().add(reject(id, null, GzJpFulfillRejectReason.NOT_FOUND));
                continue;
            }
            String orderStatus = orderStatusMap.get(row.getOrderId());
            if (!GzJpOrderStatus.isPaidLike(orderStatus)) {
                result.getRejects().add(reject(id, row.getFulfillStatus(), GzJpFulfillRejectReason.ORDER_UNPAID));
                continue;
            }
            String from = row.getFulfillStatus();
            GzJpFulfillRejectReason reason = ship
                ? GzJpFulfillStateMachine.checkShip(from)
                : GzJpFulfillStateMachine.checkAdvance(from, target);
            if (reason != null) {
                result.getRejects().add(reject(id, from, reason));
                continue;
            }
            if (target.equals(from)) {
                // 已是目标态：幂等跳过（同事刚推过），不算失败也不重复写
                skipped++;
                continue;
            }
            todoByFrom.computeIfAbsent(from, k -> new ArrayList<>()).add(id);
        }

        // ④ 分组写（每组再按 SQL_CHUNK 切片）
        int advanced = 0;
        LocalDateTime shippedAt = LocalDateTime.now();
        for (Map.Entry<String, List<Long>> entry : todoByFrom.entrySet()) {
            String from = entry.getKey();
            for (List<Long> chunk : partition(entry.getValue(), SQL_CHUNK)) {
                int affected = ship
                    ? itemMapper.shipGuarded(chunk, from, carrier, trackingNo, shippedAt, operatorId)
                    : itemMapper.advanceGuarded(chunk, from, target, operatorId);
                if (affected != chunk.size()) {
                    // 行已被 FOR UPDATE 锁住，理论不可达。真发生了说明有人绕过本服务写库 ——
                    // 宁可整批回滚报错，也不返回一个对不上账的计数。
                    throw new ServiceException("履约状态已被他人修改（预期 " + chunk.size()
                        + " 行、实际 " + affected + " 行），已回滚，请刷新看板后重试");
                }
                advanced += affected;
            }
        }

        result.setAdvanced(advanced);
        result.setSkipped(skipped);
        result.setRejected(result.getRejects().size());

        // ⑤ 一行没动、也没有任何幂等跳过 = 这次操作整体就是错的，抛错让 admin 看见红字
        if (advanced == 0 && skipped == 0) {
            GzJpFulfillRejectVO first = result.getRejects().isEmpty() ? null : result.getRejects().get(0);
            String detail = first == null ? "所选行均不可操作" : first.getReason();
            throw new ServiceException("没有可" + (ship ? "发货" : "推进") + "的商品行："
                + detail + "（共 " + result.getRejected() + " 行被拒）",
                GzJpFulfillErrorCode.NOTHING_ADVANCED);
        }
        return result;
    }

    /**
     * 批量取订单付款态。
     *
     * <p>不加锁：并发只可能把 paid 改成 partial_refunded / refunded，三者都算「付过款」，
     * 判定结果不会翻转；created → cancelled 的行本来就被拒。</p>
     */
    private Map<Long, String> loadOrderStatus(List<GzJpOrderItem> rows) {
        Set<Long> orderIds = new HashSet<>();
        for (GzJpOrderItem row : rows) {
            if (row.getOrderId() != null) {
                orderIds.add(row.getOrderId());
            }
        }
        if (orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<GzJpOrder> orders = orderMapper.selectByIds(orderIds);
        Map<Long, String> map = new HashMap<>(orders.size());
        for (GzJpOrder order : orders) {
            map.put(order.getId(), order.getBusinessStatus());
        }
        return map;
    }

    /**
     * 发货闸：本批行必须同属一个客人，且该运单号不能已经挂在别的客人名下。
     *
     * <p>不拦的后果不是数据难看，是<b>甲客人在自己订单详情里看到乙客人的运单号</b>。
     * 分两次往同一个包裹里补行是合法的，所以校验的是「客人一致」而不是「单号未使用」。</p>
     */
    private void assertSingleCustomer(List<Long> ids, Map<Long, GzJpOrderItem> rowMap,
                                      Map<Long, String> orderStatusMap, String trackingNo) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (Long id : ids) {
            GzJpOrderItem row = rowMap.get(id);
            // 只统计「会被真正写入」的候选行：不存在 / 未支付的行本来就会被逐行拒掉
            if (row != null && GzJpOrderStatus.isPaidLike(orderStatusMap.get(row.getOrderId()))) {
                userIds.add(row.getUserId());
            }
        }
        if (userIds.size() > 1) {
            throw new ServiceException("同一个运单号只能属于一个客人，本次勾选跨了 " + userIds.size()
                + " 个客人，请分开发货", GzJpFulfillErrorCode.SHIP_CROSS_USER);
        }
        if (userIds.isEmpty()) {
            return;
        }
        Long userId = userIds.iterator().next();
        List<Long> owners = itemMapper.selectUserIdsByTrackingNo(trackingNo);
        for (Long owner : owners) {
            if (owner != null && !owner.equals(userId)) {
                throw new ServiceException("运单号 " + trackingNo + " 已用于其他客人的包裹，请核对后重填",
                    GzJpFulfillErrorCode.SHIP_CROSS_USER);
            }
        }
    }

    // ============================================================
    //  履约看板查询（UI:admin.fulfill_board，GZ-JP-108 消费）
    // ============================================================

    @Override
    public TableDataInfo<GzJpFulfillBoardItemVO> selectBoardPage(GzJpFulfillQueryBo query, PageQuery pageQuery) {
        GzJpFulfillQueryBo q = query == null ? new GzJpFulfillQueryBo() : query;

        // 状态多选：非法值不静默丢弃（丢弃会让筛选结果看起来「更多」而不是「更少」，更危险）
        if (ObjectUtil.isNotEmpty(q.getFulfillStatus())) {
            for (String st : q.getFulfillStatus()) {
                if (!GzJpFulfillStatus.isValid(st)) {
                    throw new ServiceException("非法的履约状态筛选值：" + st);
                }
            }
        }

        // 客人筛选：keyword 先解析成 id 集合，与显式 userId 取交集
        Collection<Long> userIds = resolveUserIds(q);
        if (userIds != null && userIds.isEmpty()) {
            // 关键词没匹配到任何客人 → 直接空结果，别退化成「不筛选」把全部数据倒出来
            return TableDataInfo.build(new ArrayList<>());
        }

        LocalDateTime beginTime = q.getBeginDate() == null ? null : q.getBeginDate().atStartOfDay();
        LocalDateTime endTime = q.getEndDate() == null ? null : LocalDateTime.of(q.getEndDate(), LocalTime.MAX);

        Page<GzJpFulfillBoardRow> page = itemMapper.selectBoardPage(
            buildPage(pageQuery), q, userIds, beginTime, endTime);

        List<GzJpFulfillBoardRow> rows = page.getRecords();
        Map<Long, GzUserVO> userMap = loadUsers(rows);
        Map<String, String> carriers = safeDictMap();

        List<GzJpFulfillBoardItemVO> vos = new ArrayList<>(rows.size());
        for (GzJpFulfillBoardRow row : rows) {
            vos.add(toBoardVO(row, userMap, carriers));
        }
        Page<GzJpFulfillBoardItemVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(vos);
        return TableDataInfo.build(voPage);
    }

    private Page<GzJpFulfillBoardRow> buildPage(PageQuery pageQuery) {
        long current = 1L;
        long size = BOARD_PAGE_DEFAULT;
        if (pageQuery != null) {
            if (pageQuery.getPageNum() != null && pageQuery.getPageNum() > 0) {
                current = pageQuery.getPageNum();
            }
            if (pageQuery.getPageSize() != null && pageQuery.getPageSize() > 0) {
                size = Math.min(pageQuery.getPageSize(), BOARD_PAGE_MAX);
            }
        }
        return new Page<>(current, size);
    }

    /**
     * 客人筛选条件 → user_id 集合。
     *
     * @return null = 不按客人筛选；空集合 = 关键词无匹配（调用方短路返回空结果）
     */
    private Collection<Long> resolveUserIds(GzJpFulfillQueryBo q) {
        boolean hasKeyword = StrUtil.isNotBlank(q.getKeyword());
        if (q.getUserId() == null && !hasKeyword) {
            return null;
        }
        if (!hasKeyword) {
            return List.of(q.getUserId());
        }
        List<Long> byKeyword = userService.selectIdsByKeyword(StrUtil.trim(q.getKeyword()));
        if (ObjectUtil.isEmpty(byKeyword)) {
            return List.of();
        }
        if (q.getUserId() == null) {
            return byKeyword;
        }
        return byKeyword.contains(q.getUserId()) ? List.of(q.getUserId()) : List.of();
    }

    private Map<Long, GzUserVO> loadUsers(List<GzJpFulfillBoardRow> rows) {
        Set<Long> ids = new HashSet<>();
        for (GzJpFulfillBoardRow row : rows) {
            if (row.getUserId() != null) {
                ids.add(row.getUserId());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, GzUserVO> map = userService.selectVoMapByIds(ids);
        return map == null ? Collections.emptyMap() : map;
    }

    private GzJpFulfillBoardItemVO toBoardVO(GzJpFulfillBoardRow row,
                                             Map<Long, GzUserVO> userMap,
                                             Map<String, String> carriers) {
        GzJpFulfillBoardItemVO vo = new GzJpFulfillBoardItemVO();
        vo.setId(row.getId());
        vo.setOrderId(row.getOrderId());
        vo.setOrderNo(row.getOrderNo());
        vo.setBusinessStatus(row.getBusinessStatus());
        vo.setUserId(row.getUserId());
        vo.setProductId(row.getProductId());
        vo.setQty(row.getQty());
        vo.setUnitPriceCent(row.getUnitPriceCent());
        vo.setAmountCent(row.getAmountCent());
        vo.setFulfillStatus(row.getFulfillStatus());
        vo.setFulfillStatusLabel(GzJpFulfillStatus.labelOf(row.getFulfillStatus()));
        vo.setTerminal(GzJpFulfillStateMachine.isTerminal(row.getFulfillStatus()));
        vo.setCarrierCode(row.getCarrierCode());
        vo.setCarrierLabel(row.getCarrierCode() == null ? null : carriers.get(row.getCarrierCode()));
        vo.setTrackingNo(row.getTrackingNo());
        vo.setShippedAt(row.getShippedAt());
        vo.setRefundStatus(row.getRefundStatus());
        vo.setRefundStatusLabel(row.getRefundStatus() == null ? null
            : GzJpRefundStatus.labelOf(row.getRefundStatus()));
        vo.setRefundAmountCent(row.getRefundAmountCent());
        vo.setOrderCreateTime(row.getOrderCreateTime());
        vo.setPaidTime(row.getPaidTime());

        GzUserVO user = userMap.get(row.getUserId());
        if (user != null) {
            vo.setUserNickname(user.getNickname());
            vo.setUserMobile(user.getMobile());
            vo.setUserNo(user.getUserNo());
        }
        // ★ 商品信息读快照不回查商品表：改名 / 下架 / 删除都不影响看板
        JpOrderSnapshot.Product snap = readSnapshot(row.getProductSnapshotJson());
        if (snap != null) {
            vo.setProductNo(snap.getProductNo());
            vo.setName(snap.getName());
            vo.setEventName(snap.getEventName());
        } else {
            vo.setName("商品");
        }
        return vo;
    }

    private JpOrderSnapshot.Product readSnapshot(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JpOrderSnapshot.Product.class);
        } catch (Exception e) {
            // 快照损坏（历史脏数据）：少显示一点，也不要让整个看板 500
            log.warn("[gz-jp-fulfill] 商品快照解析失败，按缺省展示: {}", e.getMessage());
            return null;
        }
    }

    // ============================================================
    //  工具
    // ============================================================

    /**
     * 入参 id 规范化：去空 → 去重 → <b>升序</b> → 上限校验。
     *
     * <p>升序是防死锁的关键：两个并发请求勾了重叠的行，若加锁顺序相反，InnoDB 会直接判死锁
     * （本项目购物车加购踩过一次）。排序后所有请求都按同一顺序申请行锁，最坏只是排队。</p>
     */
    private List<Long> normalizeIds(List<Long> raw) {
        if (ObjectUtil.isEmpty(raw)) {
            throw new ServiceException("请至少选择一行商品");
        }
        Set<Long> distinct = new HashSet<>();
        for (Long id : raw) {
            if (id != null) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            throw new ServiceException("请至少选择一行商品");
        }
        if (distinct.size() > BATCH_MAX) {
            throw new ServiceException("单次最多操作 " + BATCH_MAX + " 行，请分批处理");
        }
        List<Long> ids = new ArrayList<>(distinct);
        Collections.sort(ids);
        return ids;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return chunks;
    }

    private GzJpFulfillRejectVO reject(Long itemId, String currentStatus, GzJpFulfillRejectReason reason) {
        GzJpFulfillRejectVO vo = new GzJpFulfillRejectVO();
        vo.setItemId(itemId);
        vo.setCurrentStatus(currentStatus);
        vo.setCurrentStatusLabel(currentStatus == null ? null : GzJpFulfillStatus.labelOf(currentStatus));
        vo.setReasonCode(reason.name());
        vo.setReason(reason.getMessage());
        return vo;
    }

    /**
     * 快递编码合法性 + 中文名。
     *
     * <p>字典有内容时做成员校验（挡住手写 curl 传进来的错码，否则客人侧只能看到一个裸编码）；
     * 字典整体取不到时<b>只告警不拦</b> —— 字典缓存故障不该让整个发货动作停摆。</p>
     */
    private String resolveCarrierLabel(String carrier) {
        Map<String, String> carriers = safeDictMap();
        if (carriers.isEmpty()) {
            log.warn("[gz-jp-fulfill] 字典 {} 取不到内容，跳过快递编码校验 carrier={}", DICT_EXPRESS_CARRIER, carrier);
            return null;
        }
        String label = carriers.get(carrier);
        if (StrUtil.isBlank(label)) {
            throw new ServiceException("未知的快递公司编码：" + carrier);
        }
        return label;
    }

    private Map<String, String> safeDictMap() {
        try {
            Map<String, String> map = dictService.getAllDictByDictType(DICT_EXPRESS_CARRIER);
            return map == null ? Collections.emptyMap() : map;
        } catch (Exception e) {
            log.warn("[gz-jp-fulfill] 读取字典 {} 失败: {}", DICT_EXPRESS_CARRIER, e.getMessage());
            return Collections.emptyMap();
        }
    }
}
