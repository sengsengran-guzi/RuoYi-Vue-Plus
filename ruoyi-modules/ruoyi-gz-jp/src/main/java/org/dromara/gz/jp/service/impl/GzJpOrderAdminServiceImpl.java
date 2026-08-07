package org.dromara.gz.jp.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.DictService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.domain.bo.GzJpOrderQueryBo;
import org.dromara.gz.jp.domain.dto.GzJpOrderItemStat;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminVO;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.service.IGzJpOrderAdminService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * admin 订单管理服务实现（GZ-JP-109，UI:admin.order）—— <b>纯只读</b>。
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li><b>★ 不过滤付款状态</b> —— 与 {@link GzJpFulfillServiceImpl#selectBoardPage} 最大的差别。
 *       看板是店员的采购清单，硬编码「只出付过款的行」（漏了就会拿没付钱的单去日本下单）；
 *       本页是<b>资金视角的查单页</b>，{@code created} / {@code cancelled} 的单必须能查到 ——
 *       客人来问「我下的那单怎么不见了」，答案往往正是「那单没付成功」。
 *       把看板那道闸抄过来，等于让这个问题在后台永远查不出答案。</li>
 *   <li><b>分页只查订单表，客人信息与款数事后批量补</b>：join 用户表会把分页的 total 算复杂，
 *       而且用户表在别的模块（gz-common）—— 跨模块写 JOIN 是本项目一贯避免的。
 *       两次批量查询（{@code selectVoMapByIds} + {@code selectStatsByOrderIds}）替代 N+1。</li>
 *   <li><b>详情的商品行读下单快照</b>，不回查商品表：商品改名 / 下架 / 删除后，
 *       历史订单照常显示成下单那一刻的样子。</li>
 *   <li><b>没有任何写方法</b>：推进 / 发货 / 标失败全在履约看板（GZ-JP-106 的
 *       {@code /system/gz/jp/fulfill/*}）。本页多一个写口子就是绕过状态机守卫的入口。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpOrderAdminServiceImpl implements IGzJpOrderAdminService {

    /** 列表分页默认 / 上限（与履约看板同口径） */
    private static final int PAGE_DEFAULT = 20;
    private static final int PAGE_MAX = 200;

    /** 国内快递公司字典（既有字典，复用不新建） */
    private static final String DICT_EXPRESS_CARRIER = "gz_express_carrier";

    private final GzJpOrderMapper baseMapper;
    private final GzJpOrderItemMapper itemMapper;
    private final IGzUserService userService;
    /** 详情里的商户单号 / 微信交易号 / 通道手续费（对账时店员要拿去微信商户后台核） */
    private final IGzPayTransactionService payTransactionService;
    private final DictService dictService;
    /** Spring 全局 ObjectMapper（解下单快照；构造注入以便单测替换） */
    private final ObjectMapper objectMapper;

    // ============================================================
    //  列表
    // ============================================================

    @Override
    public TableDataInfo<GzJpOrderAdminVO> selectAdminPage(GzJpOrderQueryBo query, PageQuery pageQuery) {
        GzJpOrderQueryBo q = query == null ? new GzJpOrderQueryBo() : query;

        // 状态多选：非法值直接报错，不静默丢弃（丢弃会让结果看起来「更多」而不是「更少」）
        if (ObjectUtil.isNotEmpty(q.getBusinessStatus())) {
            for (String st : q.getBusinessStatus()) {
                if (!GzJpOrderStatus.isValid(st)) {
                    throw new ServiceException("非法的订单状态筛选值：" + st);
                }
            }
        }

        // 客人筛选：keyword 先解析成 id 集合，与显式 userId 取交集
        Collection<Long> userIds = resolveUserIds(q);
        if (userIds != null && userIds.isEmpty()) {
            // 关键词没匹配到任何客人 → 直接空结果，别退化成「不筛选」把全部订单倒出来
            return TableDataInfo.build(new ArrayList<>());
        }

        LocalDateTime beginTime = q.getBeginDate() == null ? null : q.getBeginDate().atStartOfDay();
        LocalDateTime endTime = q.getEndDate() == null ? null : LocalDateTime.of(q.getEndDate(), LocalTime.MAX);

        LambdaQueryWrapper<GzJpOrder> lqw = Wrappers.<GzJpOrder>lambdaQuery()
            .like(StrUtil.isNotBlank(q.getOrderNo()), GzJpOrder::getOrderNo, StrUtil.trim(q.getOrderNo()))
            .in(userIds != null, GzJpOrder::getUserId, userIds)
            .in(ObjectUtil.isNotEmpty(q.getBusinessStatus()), GzJpOrder::getBusinessStatus, q.getBusinessStatus())
            .ge(beginTime != null, GzJpOrder::getCreateTime, beginTime)
            .le(endTime != null, GzJpOrder::getCreateTime, endTime)
            // 最新下的单排最前 —— 查单页的默认诉求永远是「刚才那单」
            .orderByDesc(GzJpOrder::getId);

        Page<GzJpOrder> page = baseMapper.selectPage(buildPage(pageQuery), lqw);

        Page<GzJpOrderAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<GzJpOrder> records = page.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(List.of());
            return TableDataInfo.build(voPage);
        }

        Map<Long, GzUserVO> userMap = loadUsers(records);
        Map<Long, GzJpOrderItemStat> statMap = loadStats(records);

        List<GzJpOrderAdminVO> vos = new ArrayList<>(records.size());
        for (GzJpOrder order : records) {
            vos.add(toListVO(order, userMap, statMap.get(order.getId())));
        }
        voPage.setRecords(vos);
        return TableDataInfo.build(voPage);
    }

    private GzJpOrderAdminVO toListVO(GzJpOrder order, Map<Long, GzUserVO> userMap, GzJpOrderItemStat stat) {
        GzJpOrderAdminVO vo = new GzJpOrderAdminVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setTotalAmountCent(order.getTotalAmountCent());
        vo.setBusinessStatus(order.getBusinessStatus());
        vo.setBusinessStatusLabel(GzJpOrderStatus.labelOf(order.getBusinessStatus()));
        // 没有商品行的订单（理论不该存在，脏数据兜底）显示 0，而不是让整列空着
        vo.setItemCount(stat == null ? 0 : ObjectUtil.defaultIfNull(stat.getItemCount(), 0));
        vo.setTotalQty(stat == null ? 0 : ObjectUtil.defaultIfNull(stat.getTotalQty(), 0));
        vo.setCreateTime(toLocalDateTime(order.getCreateTime()));
        vo.setPaidTime(order.getPaidTime());
        vo.setCancelledTime(order.getCancelledTime());
        fillUser(userMap.get(order.getUserId()), vo::setUserNickname, vo::setUserMobile, vo::setUserNo);
        return vo;
    }

    // ============================================================
    //  详情
    // ============================================================

    @Override
    public GzJpOrderAdminDetailVO getAdminDetail(Long orderId) {
        if (ObjectUtil.isNull(orderId)) {
            throw new ServiceException("订单不存在");
        }
        GzJpOrder order = baseMapper.selectById(orderId);
        if (order == null) {
            // 软删 / 不存在 / 跨租户都走这里（selectById 已被 @TableLogic 与租户拦截器收口）
            throw new ServiceException("订单不存在");
        }

        List<GzJpOrderItem> items = itemMapper.selectList(
            Wrappers.<GzJpOrderItem>lambdaQuery()
                .eq(GzJpOrderItem::getOrderId, orderId)
                .orderByAsc(GzJpOrderItem::getId));

        Map<String, String> carriers = safeDictMap();
        List<GzJpOrderAdminItemVO> itemVOs = new ArrayList<>(items.size());
        int totalQty = 0;
        for (GzJpOrderItem item : items) {
            itemVOs.add(toItemVO(item, carriers));
            totalQty += ObjectUtil.defaultIfNull(item.getQty(), 0);
        }

        GzJpOrderAdminDetailVO vo = new GzJpOrderAdminDetailVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTotalAmountCent(order.getTotalAmountCent());
        vo.setBusinessStatus(order.getBusinessStatus());
        vo.setBusinessStatusLabel(GzJpOrderStatus.labelOf(order.getBusinessStatus()));
        vo.setItemCount(itemVOs.size());
        vo.setTotalQty(totalQty);
        vo.setCreateTime(toLocalDateTime(order.getCreateTime()));
        vo.setPaidTime(order.getPaidTime());
        vo.setCancelledTime(order.getCancelledTime());
        vo.setUserNote(order.getUserNote());
        vo.setRemark(order.getRemark());
        vo.setUserId(order.getUserId());
        vo.setPayTransactionId(order.getPayTransactionId());
        vo.setAddress(readSnapshot(order.getAddressSnapshotJson(), JpOrderSnapshot.Address.class));
        vo.setItems(itemVOs);

        GzUserVO user = order.getUserId() == null ? null : userService.selectVoById(order.getUserId());
        fillUser(user, vo::setUserNickname, vo::setUserMobile, vo::setUserNo);

        fillPayTransaction(vo, order.getPayTransactionId());
        return vo;
    }

    private GzJpOrderAdminItemVO toItemVO(GzJpOrderItem item, Map<String, String> carriers) {
        GzJpOrderAdminItemVO vo = new GzJpOrderAdminItemVO();
        vo.setId(item.getId());
        vo.setProductId(item.getProductId());
        vo.setQty(item.getQty());
        vo.setUnitPriceCent(item.getUnitPriceCent());
        vo.setAmountCent(item.getAmountCent());
        vo.setSource(item.getSource());
        vo.setFulfillStatus(item.getFulfillStatus());
        vo.setFulfillStatusLabel(GzJpFulfillStatus.labelOf(item.getFulfillStatus()));
        vo.setCarrierCode(item.getCarrierCode());
        vo.setCarrierLabel(item.getCarrierCode() == null ? null : carriers.get(item.getCarrierCode()));
        vo.setTrackingNo(item.getTrackingNo());
        vo.setShippedAt(item.getShippedAt());
        vo.setRefundStatus(item.getRefundStatus());
        vo.setRefundStatusLabel(item.getRefundStatus() == null ? null
            : GzJpRefundStatus.labelOf(item.getRefundStatus()));
        vo.setRefundAmountCent(item.getRefundAmountCent());

        // ★ 商品信息一律读快照，不回查商品表
        JpOrderSnapshot.Product snap = readSnapshot(item.getProductSnapshotJson(), JpOrderSnapshot.Product.class);
        if (snap != null) {
            vo.setProductNo(snap.getProductNo());
            vo.setName(snap.getName());
            // ★ 下发 file id 不是 URL：admin 用 GzImageThumb 自己换预签名，后端不为一屏 30 行各签一次
            vo.setMainImageId(snap.getMainImageId());
            vo.setEventName(snap.getEventName());
            vo.setDeliveryDateText(snap.getDeliveryDateText());
            vo.setNoticeText(snap.getNoticeText());
        } else {
            vo.setName("商品");
        }
        return vo;
    }

    /**
     * 补支付流水信息（商户单号 / 微信交易号 / 通道手续费）。
     *
     * <p>取不到<b>只留空不抛错</b>：支付流水是另一个模块的数据，它挂了不该让整个订单详情打不开 ——
     * 店员至少还要能看到货和地址。</p>
     */
    private void fillPayTransaction(GzJpOrderAdminDetailVO vo, Long payTransactionId) {
        if (payTransactionId == null) {
            // 未支付订单建单时就没有流水（回调才回填）—— 正常情况，不是异常
            return;
        }
        try {
            GzPayTransactionVO txn = payTransactionService.getById(payTransactionId);
            if (txn == null) {
                return;
            }
            vo.setOutTradeNo(txn.getOutTradeNo());
            vo.setWxTransactionId(txn.getTransactionId());
            vo.setPayStatus(txn.getStatus());
            vo.setPayFeeCent(txn.getFeeCent());
        } catch (Exception e) {
            log.warn("[gz-jp-order-admin] 支付流水读取失败 payTransactionId={}：{}", payTransactionId, e.getMessage());
        }
    }

    // ============================================================
    //  工具
    // ============================================================

    private Page<GzJpOrder> buildPage(PageQuery pageQuery) {
        long current = 1L;
        long size = PAGE_DEFAULT;
        if (pageQuery != null) {
            if (pageQuery.getPageNum() != null && pageQuery.getPageNum() > 0) {
                current = pageQuery.getPageNum();
            }
            if (pageQuery.getPageSize() != null && pageQuery.getPageSize() > 0) {
                size = Math.min(pageQuery.getPageSize(), PAGE_MAX);
            }
        }
        return new Page<>(current, size);
    }

    /**
     * 客人筛选条件 → user_id 集合。
     *
     * @return null = 不按客人筛选；空集合 = 关键词无匹配（调用方短路返回空结果）
     */
    private Collection<Long> resolveUserIds(GzJpOrderQueryBo q) {
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

    private Map<Long, GzUserVO> loadUsers(List<GzJpOrder> orders) {
        Set<Long> ids = new HashSet<>();
        for (GzJpOrder order : orders) {
            if (order.getUserId() != null) {
                ids.add(order.getUserId());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, GzUserVO> map = userService.selectVoMapByIds(ids);
        return map == null ? Collections.emptyMap() : map;
    }

    private Map<Long, GzJpOrderItemStat> loadStats(List<GzJpOrder> orders) {
        List<Long> orderIds = orders.stream().map(GzJpOrder::getId).toList();
        if (orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<GzJpOrderItemStat> stats = itemMapper.selectStatsByOrderIds(orderIds);
        Map<Long, GzJpOrderItemStat> map = new HashMap<>(stats.size());
        for (GzJpOrderItemStat stat : stats) {
            map.put(stat.getOrderId(), stat);
        }
        return map;
    }

    /** 客人三件套统一填充（列表 VO 与详情 VO 字段名相同但类型不同，用 setter 引用收口一处）。 */
    private void fillUser(GzUserVO user, java.util.function.Consumer<String> nickname,
                          java.util.function.Consumer<String> mobile, java.util.function.Consumer<String> userNo) {
        if (user == null) {
            return;
        }
        nickname.accept(user.getNickname());
        mobile.accept(user.getMobile());
        userNo.accept(user.getUserNo());
    }

    private <T> T readSnapshot(String json, Class<T> type) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            // 快照损坏（历史脏数据）：少显示一点，也不要让整个订单详情 500
            log.warn("[gz-jp-order-admin] 快照解析失败 type={}：{}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private Map<String, String> safeDictMap() {
        try {
            Map<String, String> map = dictService.getAllDictByDictType(DICT_EXPRESS_CARRIER);
            return map == null ? Collections.emptyMap() : map;
        } catch (Exception e) {
            log.warn("[gz-jp-order-admin] 读取字典 {} 失败：{}", DICT_EXPRESS_CARRIER, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** ruoyi {@code BaseEntity.createTime} 是 {@code java.util.Date}，VO 统一用 LocalDateTime。 */
    private static LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
