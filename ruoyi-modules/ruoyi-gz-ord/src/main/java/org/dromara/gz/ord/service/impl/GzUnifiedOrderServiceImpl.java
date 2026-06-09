package org.dromara.gz.ord.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.gacha.domain.dto.GachaSnapshot;
import org.dromara.gz.gacha.domain.entity.GzGachaOrder;
import org.dromara.gz.gacha.mapper.GzGachaOrderMapper;
import org.dromara.gz.ord.domain.bo.GzAdminOrderQueryBo;
import org.dromara.gz.ord.domain.dto.OrdSnapshot;
import org.dromara.gz.ord.domain.entity.GzOrdOrder;
import org.dromara.gz.ord.domain.vo.GzUnifiedOrderVo;
import org.dromara.gz.ord.enums.ExpressCarrierEnum;
import org.dromara.gz.ord.enums.LogisticsStatusEnum;
import org.dromara.gz.ord.mapper.GzOrdOrderMapper;
import org.dromara.gz.ord.service.IGzUnifiedOrderService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一订单视图服务实现（GZ-ADMIN-103，doc/11 §8.1）。
 *
 * <p><b>聚合口径</b>（强约束 #1）：以 {@code gz_pay_transaction} 为分页主表（财务真源，含 test 单），
 * 按 {@code business_type} 回查 {@code gz_ord_order}（preorder）/ {@code gz_gacha_order}（gacha）
 * 批量补明细（IN 查询非 N+1，R3），映射统一 {@link GzUnifiedOrderVo}。test 单无业务订单表
 * （business_order_no 为 null）则仅投影支付流水字段。</p>
 *
 * <p><b>租户 / 软删隔离</b>：三表查询全走 ruoyi {@code TenantLineInnerInterceptor} + {@code @TableLogic}
 * 自动 append，不手写 where（决策 D2，不建物理 VIEW 即为此）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-103)
 */
@Slf4j
@Service("gzOrdUnifiedOrderServiceImpl")
@RequiredArgsConstructor
public class GzUnifiedOrderServiceImpl implements IGzUnifiedOrderService {

    private static final String BIZ_PREORDER = "preorder";
    private static final String BIZ_GACHA = "gacha";
    private static final String BIZ_TEST = "test";

    private static final String PLACEHOLDER_IMAGE_URL = "/static/placeholder/ord-product.png";

    private final GzPayTransactionMapper payTransactionMapper;
    private final GzOrdOrderMapper ordOrderMapper;
    private final GzGachaOrderMapper gachaOrderMapper;
    private final IGzUserService userService;
    private final IGzFileService fileService;
    private final ObjectMapper objectMapper;

    // ============================================================
    //  字典 / 统一映射（doc/11 §8.2 + 附录 A.9）
    // ============================================================

    /** business_type → 中文 label（字典 gz_business_type）。 */
    private static final Map<String, String> BIZ_TYPE_LABELS = Map.of(
        BIZ_PREORDER, "预定货品",
        BIZ_GACHA, "扭蛋机",
        BIZ_TEST, "测试"
    );

    /** preorder business_status → 统一 chip code（doc/11 §8.2）。 */
    private static final Map<String, String> PREORDER_CHIP = Map.of(
        "created", "to_pay",
        "paid", "to_ship",
        "in_logistics", "shipping",
        "delivered", "done",
        "cancelled", "cancelled",
        "refunded", "refunded"
    );

    /** gacha business_status → 统一 chip code（doc/11 §8.2；扭蛋无待支付/取消）。 */
    private static final Map<String, String> GACHA_CHIP = Map.of(
        "pending_ship", "to_ship",
        "in_logistics", "shipping",
        "delivered", "done",
        "refunded", "refunded"
    );

    /** 统一 chip code → 中文 label（doc/11 §8.2）。 */
    private static final Map<String, String> CHIP_LABELS = Map.of(
        "to_pay", "待支付",
        "to_ship", "待发货",
        "shipping", "运输中",
        "done", "已完成",
        "cancelled", "已取消",
        "refunded", "已退款"
    );

    // ============================================================
    //  AC4 列表
    // ============================================================

    @Override
    public TableDataInfo<GzUnifiedOrderVo> listForAdmin(GzAdminOrderQueryBo query, PageQuery pageQuery) {
        GzAdminOrderQueryBo q = query == null ? new GzAdminOrderQueryBo() : query;

        // userKeyword（昵称 / openid）→ 解析 user_id 集合；无匹配则强制空结果
        List<Long> userIds = null;
        if (StrUtil.isNotBlank(q.getUserKeyword())) {
            userIds = userService.selectIdsByKeyword(q.getUserKeyword());
            if (userIds.isEmpty()) {
                return TableDataInfo.build(new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize(), 0));
            }
        }

        // 以 gz_pay_transaction 为分页主表（含 test 单）：businessType / orderNo / 时间 / userIds 主表过滤
        LambdaQueryWrapper<GzPayTransaction> lqw = new LambdaQueryWrapper<GzPayTransaction>()
            .eq(StrUtil.isNotBlank(q.getBusinessType()), GzPayTransaction::getBusinessType, q.getBusinessType())
            .likeRight(StrUtil.isNotBlank(q.getOrderNo()), GzPayTransaction::getOutTradeNo, q.getOrderNo())
            .in(userIds != null, GzPayTransaction::getUserId, userIds == null ? Collections.emptyList() : userIds)
            .ge(q.getStartTime() != null, GzPayTransaction::getCreateTime, q.getStartTime())
            .le(q.getEndTime() != null, GzPayTransaction::getCreateTime, q.getEndTime())
            // paid_time 优先倒序，未支付（null）退 create_time（doc/11 §8.1 created_at 排序锚点）
            .orderByDesc(GzPayTransaction::getPaidTime)
            .orderByDesc(GzPayTransaction::getCreateTime);

        Page<GzPayTransaction> page = payTransactionMapper.selectPage(pageQuery.build(), lqw);
        List<GzPayTransaction> txns = page.getRecords();

        // 批量回查业务订单 + 用户（防 N+1）
        Map<String, GzOrdOrder> preorderMap = batchPreorder(txns);
        Map<String, GzGachaOrder> gachaMap = batchGacha(txns);
        Map<Long, GzUserVO> userMap = batchUsers(txns);

        // 映射 + businessStatus（chip）/ logisticsStatus 本页内过滤（R3）
        List<GzUnifiedOrderVo> rows = txns.stream()
            .map(t -> toVo(t, preorderMap, gachaMap, userMap, false))
            .filter(vo -> matchChip(vo, q.getBusinessStatus()))
            .filter(vo -> matchLogistics(vo, q.getLogisticsStatus()))
            .collect(Collectors.toList());

        // 当 chip / logistics 过滤后，total 用主表分页 total（本页内过滤为运营辅助筛，V1.1 数据量小）
        Page<GzUnifiedOrderVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(rows);
        return TableDataInfo.build(voPage);
    }

    // ============================================================
    //  AC5 详情
    // ============================================================

    @Override
    public GzUnifiedOrderVo getDetailForAdmin(Long transactionId) {
        if (transactionId == null) {
            return null;
        }
        GzPayTransaction txn = payTransactionMapper.selectById(transactionId);
        if (txn == null) {
            return null;
        }
        Map<String, GzOrdOrder> preorderMap = batchPreorder(List.of(txn));
        Map<String, GzGachaOrder> gachaMap = batchGacha(List.of(txn));
        Map<Long, GzUserVO> userMap = batchUsers(List.of(txn));
        return toVo(txn, preorderMap, gachaMap, userMap, true);
    }

    // ============================================================
    //  批量回查
    // ============================================================

    private Map<String, GzOrdOrder> batchPreorder(List<GzPayTransaction> txns) {
        List<String> orderNos = txns.stream()
            .filter(t -> BIZ_PREORDER.equals(t.getBusinessType()) && StrUtil.isNotBlank(t.getBusinessOrderNo()))
            .map(GzPayTransaction::getBusinessOrderNo)
            .distinct()
            .toList();
        if (orderNos.isEmpty()) {
            return Collections.emptyMap();
        }
        List<GzOrdOrder> list = ordOrderMapper.selectList(
            new LambdaQueryWrapper<GzOrdOrder>().in(GzOrdOrder::getOrderNo, orderNos));
        return list.stream().collect(Collectors.toMap(GzOrdOrder::getOrderNo, o -> o, (a, b) -> a));
    }

    private Map<String, GzGachaOrder> batchGacha(List<GzPayTransaction> txns) {
        List<String> orderNos = txns.stream()
            .filter(t -> BIZ_GACHA.equals(t.getBusinessType()) && StrUtil.isNotBlank(t.getBusinessOrderNo()))
            .map(GzPayTransaction::getBusinessOrderNo)
            .distinct()
            .toList();
        if (orderNos.isEmpty()) {
            return Collections.emptyMap();
        }
        List<GzGachaOrder> list = gachaOrderMapper.selectList(
            new LambdaQueryWrapper<GzGachaOrder>().in(GzGachaOrder::getOrderNo, orderNos));
        return list.stream().collect(Collectors.toMap(GzGachaOrder::getOrderNo, o -> o, (a, b) -> a));
    }

    private Map<Long, GzUserVO> batchUsers(List<GzPayTransaction> txns) {
        List<Long> ids = txns.stream()
            .map(GzPayTransaction::getUserId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        return ids.isEmpty() ? Collections.emptyMap() : userService.selectVoMapByIds(ids);
    }

    // ============================================================
    //  映射
    // ============================================================

    private GzUnifiedOrderVo toVo(GzPayTransaction t,
                                  Map<String, GzOrdOrder> preorderMap,
                                  Map<String, GzGachaOrder> gachaMap,
                                  Map<Long, GzUserVO> userMap,
                                  boolean withDetail) {
        GzUnifiedOrderVo vo = new GzUnifiedOrderVo();
        // ---- 支付流水维度（所有类型）----
        vo.setTransactionId(t.getId());
        vo.setOutTradeNo(t.getOutTradeNo());
        vo.setBusinessOrderNo(t.getBusinessOrderNo());
        vo.setBusinessType(t.getBusinessType());
        vo.setBusinessTypeLabel(BIZ_TYPE_LABELS.getOrDefault(t.getBusinessType(), t.getBusinessType()));
        vo.setAmountCent(t.getAmountCent());
        vo.setPayStatus(t.getStatus());
        vo.setPaidTime(t.getPaidTime());
        vo.setCreatedAt(toLocalDateTime(t.getCreateTime()));
        // ---- 用户维度 ----
        vo.setUserId(t.getUserId());
        if (t.getUserId() != null) {
            GzUserVO user = userMap.get(t.getUserId());
            if (user != null) {
                vo.setUserNickname(user.getNickname());
                vo.setOpenid(user.getOpenid());
            }
        }
        // ---- 业务订单维度（按 business_type 回查）----
        if (BIZ_PREORDER.equals(t.getBusinessType())) {
            fillPreorder(vo, preorderMap.get(t.getBusinessOrderNo()), withDetail);
        } else if (BIZ_GACHA.equals(t.getBusinessType())) {
            fillGacha(vo, gachaMap.get(t.getBusinessOrderNo()), withDetail);
        }
        // test 单：仅支付流水字段（无业务 / 物流 / 地址块）
        return vo;
    }

    private void fillPreorder(GzUnifiedOrderVo vo, GzOrdOrder o, boolean withDetail) {
        if (o == null) {
            return;
        }
        vo.setBusinessStatus(o.getBusinessStatus());
        String chip = PREORDER_CHIP.get(o.getBusinessStatus());
        vo.setChipStatus(chip);
        vo.setChipLabel(chip == null ? null : CHIP_LABELS.get(chip));
        vo.setLogisticsStatus(o.getLogisticsStatus());
        vo.setLogisticsStatusLabel(logisticsLabel(o.getLogisticsStatus()));
        vo.setCnCarrierCode(o.getCnCarrierCode());
        vo.setCnCarrierName(ExpressCarrierEnum.labelOf(o.getCnCarrierCode()));
        vo.setCnTrackingNo(o.getCnTrackingNo());
        vo.setDeliveredTime(o.getDeliveredTime());
        vo.setCancelledTime(o.getCancelledTime());
        vo.setQty(o.getQty());

        OrdSnapshot.Product product = readJson(o.getProductSnapshotJson(), OrdSnapshot.Product.class);
        OrdSnapshot.Sku sku = readJson(o.getSkuSnapshotJson(), OrdSnapshot.Sku.class);
        if (product != null) {
            vo.setProductName(product.getName());
            vo.setProductImageUrl(resolveImageUrl(product.getMainImageId()));
            vo.setIpTag(product.getIpTag());
            vo.setDeliveryDateText(product.getDeliveryDateText());
            vo.setDeliveryDateExact(product.getDeliveryDateExact());
        }
        if (sku != null) {
            vo.setSpecName(sku.getSpecName());
        }
        if (withDetail) {
            OrdSnapshot.Address addr = readJson(o.getAddressSnapshotJson(), OrdSnapshot.Address.class);
            if (addr != null) {
                vo.setRecipient(addr.getRecipient());
                vo.setRecipientMobile(addr.getMobile());
                vo.setFullAddress(joinAddress(addr));
            }
        }
    }

    private void fillGacha(GzUnifiedOrderVo vo, GzGachaOrder o, boolean withDetail) {
        if (o == null) {
            return;
        }
        vo.setBusinessStatus(o.getBusinessStatus());
        String chip = GACHA_CHIP.get(o.getBusinessStatus());
        vo.setChipStatus(chip);
        vo.setChipLabel(chip == null ? null : CHIP_LABELS.get(chip));
        vo.setLogisticsStatus(o.getLogisticsStatus());
        vo.setLogisticsStatusLabel(logisticsLabel(o.getLogisticsStatus()));
        vo.setCnCarrierCode(o.getCnCarrierCode());
        vo.setCnCarrierName(ExpressCarrierEnum.labelOf(o.getCnCarrierCode()));
        vo.setCnTrackingNo(o.getCnTrackingNo());
        vo.setDeliveredTime(o.getDeliveredTime());

        GachaSnapshot.Prize prize = readJson(o.getPrizeSnapshotJson(), GachaSnapshot.Prize.class);
        GachaSnapshot.Machine machine = readJson(o.getMachineSnapshotJson(), GachaSnapshot.Machine.class);
        if (prize != null) {
            vo.setPrizeName(prize.getName());
            // 获得物图：prize.imageId 优先，回退 machine.coverImageId（doc/11 §8.1 gacha 合并规则）
            String imageId = StrUtil.isNotBlank(prize.getImageId())
                ? prize.getImageId()
                : (machine == null ? null : machine.getCoverImageId());
            vo.setPrizeImageUrl(resolveImageUrl(imageId));
            vo.setRarity(prize.getRarity());
        }
        if (machine != null) {
            vo.setMachineName(machine.getName());
        }
        if (withDetail) {
            // gacha 地址 snapshot 可能 NULL（用户未补地址，R5）→ 详情前端判空显示「用户未补地址」
            OrdSnapshot.Address addr = readJson(o.getAddressSnapshotJson(), OrdSnapshot.Address.class);
            if (addr != null) {
                vo.setRecipient(addr.getRecipient());
                vo.setRecipientMobile(addr.getMobile());
                vo.setFullAddress(joinAddress(addr));
            }
        }
    }

    // ============================================================
    //  本页内过滤（businessStatus chip / logisticsStatus）
    // ============================================================

    private boolean matchChip(GzUnifiedOrderVo vo, String chipFilter) {
        if (StrUtil.isBlank(chipFilter)) {
            return true;
        }
        return chipFilter.equals(vo.getChipStatus());
    }

    private boolean matchLogistics(GzUnifiedOrderVo vo, String logisticsFilter) {
        if (StrUtil.isBlank(logisticsFilter)) {
            return true;
        }
        return logisticsFilter.equals(vo.getLogisticsStatus());
    }

    // ============================================================
    //  辅助
    // ============================================================

    private String logisticsLabel(String code) {
        if (code == null) {
            return null;
        }
        for (LogisticsStatusEnum e : LogisticsStatusEnum.values()) {
            if (e.getCode().equals(code)) {
                return e.getLabel();
            }
        }
        return code;
    }

    private String resolveImageUrl(String imageId) {
        if (StrUtil.isBlank(imageId)) {
            return PLACEHOLDER_IMAGE_URL;
        }
        try {
            return fileService.getPresignedUrl(Long.parseLong(imageId)).getUrl();
        } catch (Exception ex) {
            log.warn("[gz-unified-order] 图片解析失败 fileId={}，回退占位图：{}", imageId, ex.getMessage());
            return PLACEHOLDER_IMAGE_URL;
        }
    }

    private static String joinAddress(OrdSnapshot.Address a) {
        StringBuilder sb = new StringBuilder();
        appendIfNotBlank(sb, a.getProvince());
        appendIfNotBlank(sb, a.getCity());
        appendIfNotBlank(sb, a.getDistrict());
        appendIfNotBlank(sb, a.getDetail());
        return sb.toString();
    }

    private static void appendIfNotBlank(StringBuilder sb, String s) {
        if (StrUtil.isNotBlank(s)) {
            sb.append(s);
        }
    }

    private static LocalDateTime toLocalDateTime(java.util.Date date) {
        return date == null ? null
            : date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    private <T> T readJson(String json, Class<T> clazz) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.warn("[gz-unified-order] snapshot 反序列化失败 type={}: {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
