package org.dromara.gz.user.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.user.domain.entity.readonly.GachaOrderRow;
import org.dromara.gz.user.domain.entity.readonly.OrdOrderRow;
import org.dromara.gz.user.domain.vo.GzUnifiedOrderVo;
import org.dromara.gz.user.domain.vo.UnifiedOrderDetailVo;
import org.dromara.gz.user.mapper.readonly.GachaOrderRowMapper;
import org.dromara.gz.user.mapper.readonly.OrdOrderRowMapper;
import org.dromara.gz.user.service.IGzUnifiedOrderService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 统一订单聚合服务实现（GZ-USER-101）—— service 层 UNION（doc/11 §8.1 / 决策 D1）。
 *
 * <p><b>无物理 VIEW / 物化表</b>：分别用 {@link OrdOrderRowMapper} / {@link GachaOrderRowMapper}（绑
 * gz_ord_order / gz_gacha_order 的只读 mapper）查回，各自映射成 {@link GzUnifiedOrderVo} 后合并、
 * 按 createdAt 降序排序、内存分页。tenant_id / del_flag 过滤由 ruoyi 拦截器自动 append（service 不手写 WHERE）。</p>
 *
 * <p><b>gacha 分支 product_snapshot 合并</b>（doc/11 §8.1 钉死 5 字段）：把 prize_snapshot_json +
 * machine_snapshot_json 拍平成 {@code {name, cover(image_id), spec(rarity 文案), machine, rarity}}。
 * cover 透传 image_id（FK gz_file_object），不解析裸 URL（AC5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
@Slf4j
@Service("gzUserUnifiedOrderServiceImpl")
@RequiredArgsConstructor
public class GzUnifiedOrderServiceImpl implements IGzUnifiedOrderService {

    private static final String BIZ_ALL = "all";
    private static final String BIZ_PREORDER = "preorder";
    private static final String BIZ_GACHA = "gacha";

    /** 订单号前缀（doc/11 §6.3 / §7.4 命名规范，决策 D2 走前缀判 businessType） */
    private static final String PREFIX_PREORDER = "PREORD-";
    private static final String PREFIX_GACHA = "GACHA-";

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    /** 分页上限（防恶意大分页拖库，AC10） */
    private static final int MAX_PAGE_SIZE = 50;

    private final OrdOrderRowMapper ordOrderRowMapper;
    private final GachaOrderRowMapper gachaOrderRowMapper;
    /** 全局 ObjectMapper（snapshot JSON 解析 / gacha 合并 snapshot 序列化；可注入便于单测） */
    private final ObjectMapper objectMapper;

    @Override
    public TableDataInfo<GzUnifiedOrderVo> listByUser(Long userId, String bizType, Integer pageNum, Integer pageSize) {
        if (userId == null) {
            return TableDataInfo.build();
        }
        String biz = normalizeBizType(bizType);
        int num = (pageNum == null || pageNum < 1) ? DEFAULT_PAGE_NUM : pageNum;
        int size = normalizePageSize(pageSize);

        // 1. 按 bizType 分支查（AC4：单类只查单表，all 才并查）
        List<GzUnifiedOrderVo> merged = new ArrayList<>();
        if (BIZ_ALL.equals(biz) || BIZ_PREORDER.equals(biz)) {
            for (OrdOrderRow row : queryOrdOrders(userId)) {
                merged.add(toPreorderVo(row));
            }
        }
        if (BIZ_ALL.equals(biz) || BIZ_GACHA.equals(biz)) {
            for (GachaOrderRow row : queryGachaOrders(userId)) {
                merged.add(toGachaVo(row));
            }
        }

        // 2. 合并后按 createdAt DESC 排序（AC3）；null createdAt 沉底
        merged.sort(Comparator.comparing(GzUnifiedOrderVo::getCreatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));

        // 3. 内存分页（合并 + 排序后做，AC10 / 决策 D4）
        long total = merged.size();
        int from = Math.min((num - 1) * size, merged.size());
        int to = Math.min(from + size, merged.size());
        List<GzUnifiedOrderVo> rows = merged.subList(from, to);

        TableDataInfo<GzUnifiedOrderVo> dataInfo = TableDataInfo.build();
        dataInfo.setRows(rows);
        dataInfo.setTotal(total);
        return dataInfo;
    }

    // ============================================================
    //  单订单详情（GZ-USER-102）：统一字段 + 业务差异块
    // ============================================================

    @Override
    public UnifiedOrderDetailVo getDetail(String orderNo, Long userId) {
        if (userId == null || StrUtil.isBlank(orderNo)) {
            return null;
        }
        // 决策 D2：orderNo 前缀判 businessType，省一次 DB 探测
        if (orderNo.startsWith(PREFIX_PREORDER)) {
            OrdOrderRow row = ordOrderRowMapper.selectOne(
                new LambdaQueryWrapper<OrdOrderRow>().eq(OrdOrderRow::getOrderNo, orderNo));
            // AC1：非本人 / 不存在 → null（不泄露存在性，controller 统一转 403/未找到）
            if (row == null || !userId.equals(row.getUserId())) {
                return null;
            }
            return toPreorderDetail(row);
        }
        if (orderNo.startsWith(PREFIX_GACHA)) {
            GachaOrderRow row = gachaOrderRowMapper.selectOne(
                new LambdaQueryWrapper<GachaOrderRow>().eq(GachaOrderRow::getOrderNo, orderNo));
            if (row == null || !userId.equals(row.getUserId())) {
                return null;
            }
            return toGachaDetail(row);
        }
        // 非法前缀（test 单 / 脏数据）→ 不可达本详情页
        return null;
    }

    /** 预购详情：统一字段 + preorderSnapshot 差异块（7 字段，AC2）。 */
    private UnifiedOrderDetailVo toPreorderDetail(OrdOrderRow o) {
        JsonNode product = readTree(o.getProductSnapshotJson());
        JsonNode sku = readTree(o.getSkuSnapshotJson());
        Long unitPriceCent = longOrNull(sku, "priceCent");
        String arrivalText = textOrNull(product, "deliveryDateText");
        if (StrUtil.isBlank(arrivalText)) {
            arrivalText = textOrNull(product, "deliveryDateExact");
        }
        UnifiedOrderDetailVo.PreorderSnapshot diff = UnifiedOrderDetailVo.PreorderSnapshot.builder()
            .productName(textOrNull(product, "name"))
            .productImageId(textOrNull(product, "mainImageId"))
            .skuSpec(textOrNull(sku, "specName"))
            .deadlineText(null)
            .arrivalText(arrivalText)
            .unitPriceCent(unitPriceCent)
            .qty(o.getQty())
            .build();
        return baseDetailBuilder(o.getOrderNo(), BIZ_PREORDER, o.getUserId(), o.getProductSnapshotJson(),
            o.getTotalAmountCent(), o.getBusinessStatus(), o.getLogisticsStatus(), o.getCnCarrierCode(),
            o.getCnTrackingNo(), o.getAddressSnapshotJson(), o.getPaidTime(), o.getDeliveredTime(),
            toLocalDateTime(o.getCreateTime()))
            .preorderSnapshot(diff)
            .build();
    }

    /** 扭蛋详情：统一字段 + gachaSnapshot 差异块（5 字段，AC2；盲盒语义见 VO 注释）。 */
    private UnifiedOrderDetailVo toGachaDetail(GachaOrderRow o) {
        JsonNode prize = readTree(o.getPrizeSnapshotJson());
        JsonNode machine = readTree(o.getMachineSnapshotJson());
        String cover = textOrNull(prize, "imageId");
        if (StrUtil.isBlank(cover)) {
            cover = textOrNull(machine, "coverImageId");
        }
        UnifiedOrderDetailVo.GachaSnapshot diff = UnifiedOrderDetailVo.GachaSnapshot.builder()
            .prizeName(textOrNull(prize, "name"))
            .prizeImageId(cover)
            .rarity(textOrNull(prize, "rarity"))
            .machineName(textOrNull(machine, "name"))
            .paidTime(o.getPaidTime())
            .build();
        return baseDetailBuilder(o.getOrderNo(), BIZ_GACHA, o.getUserId(),
            mergeGachaProductSnapshot(o.getPrizeSnapshotJson(), o.getMachineSnapshotJson()),
            o.getTotalAmountCent(), o.getBusinessStatus(), o.getLogisticsStatus(), o.getCnCarrierCode(),
            o.getCnTrackingNo(), o.getAddressSnapshotJson(), o.getPaidTime(), o.getDeliveredTime(),
            toLocalDateTime(o.getCreateTime()))
            .gachaSnapshot(diff)
            .build();
    }

    /** 统一详情公共字段 builder（= GzUnifiedOrderVo 同字段集，决策 D1）。 */
    private UnifiedOrderDetailVo.UnifiedOrderDetailVoBuilder baseDetailBuilder(
        String orderNo, String businessType, Long userId, String productSnapshotJson, Long totalAmountCent,
        String businessStatus, String logisticsStatus, String cnCarrierCode, String cnTrackingNo,
        String addressSnapshotJson, LocalDateTime paidTime, LocalDateTime deliveredTime, LocalDateTime createdAt) {
        return UnifiedOrderDetailVo.builder()
            .orderNo(orderNo)
            .businessType(businessType)
            .userId(userId)
            .productSnapshotJson(productSnapshotJson)
            .totalAmountCent(totalAmountCent)
            .businessStatus(businessStatus)
            .logisticsStatus(logisticsStatus)
            .cnCarrierCode(cnCarrierCode)
            .cnTrackingNo(cnTrackingNo)
            .addressSnapshotJson(addressSnapshotJson)
            .paidTime(paidTime)
            .deliveredTime(deliveredTime)
            .createdAt(createdAt);
    }

    // ============================================================
    //  查询（user_id 归属，AC7；tenant/del_flag 走拦截器，AC6）
    // ============================================================

    private List<OrdOrderRow> queryOrdOrders(Long userId) {
        LambdaQueryWrapper<OrdOrderRow> lqw = new LambdaQueryWrapper<OrdOrderRow>()
            .eq(OrdOrderRow::getUserId, userId);
        return ordOrderRowMapper.selectList(lqw);
    }

    private List<GachaOrderRow> queryGachaOrders(Long userId) {
        LambdaQueryWrapper<GachaOrderRow> lqw = new LambdaQueryWrapper<GachaOrderRow>()
            .eq(GachaOrderRow::getUserId, userId);
        return gachaOrderRowMapper.selectList(lqw);
    }

    // ============================================================
    //  映射成统一 VO
    // ============================================================

    /** 预购 → 统一 VO：product_snapshot 透传原结构（AC5 preorder 分支）。 */
    private GzUnifiedOrderVo toPreorderVo(OrdOrderRow o) {
        return GzUnifiedOrderVo.builder()
            .orderNo(o.getOrderNo())
            .businessType(BIZ_PREORDER)
            .userId(o.getUserId())
            .productSnapshotJson(o.getProductSnapshotJson())
            .totalAmountCent(o.getTotalAmountCent())
            .businessStatus(o.getBusinessStatus())
            .logisticsStatus(o.getLogisticsStatus())
            .cnCarrierCode(o.getCnCarrierCode())
            .cnTrackingNo(o.getCnTrackingNo())
            .addressSnapshotJson(o.getAddressSnapshotJson())
            .paidTime(o.getPaidTime())
            .deliveredTime(o.getDeliveredTime())
            .createdAt(toLocalDateTime(o.getCreateTime()))
            .build();
    }

    /** 扭蛋 → 统一 VO：product_snapshot 合并出 5 字段（AC5 gacha 分支）。 */
    private GzUnifiedOrderVo toGachaVo(GachaOrderRow o) {
        return GzUnifiedOrderVo.builder()
            .orderNo(o.getOrderNo())
            .businessType(BIZ_GACHA)
            .userId(o.getUserId())
            .productSnapshotJson(mergeGachaProductSnapshot(o.getPrizeSnapshotJson(), o.getMachineSnapshotJson()))
            .totalAmountCent(o.getTotalAmountCent())
            .businessStatus(o.getBusinessStatus())
            .logisticsStatus(o.getLogisticsStatus())
            .cnCarrierCode(o.getCnCarrierCode())
            .cnTrackingNo(o.getCnTrackingNo())
            .addressSnapshotJson(o.getAddressSnapshotJson())
            .paidTime(o.getPaidTime())
            .deliveredTime(o.getDeliveredTime())
            .createdAt(toLocalDateTime(o.getCreateTime()))
            .build();
    }

    /**
     * gacha 分支 product_snapshot 合并（doc/11 §8.1 钉死 5 字段，AC5）：
     * <pre>
     * name    = prize_snapshot_json.name
     * cover   = prize_snapshot_json.imageId（空回退 machine_snapshot_json.coverImageId）— 透传 image_id 不解析 URL
     * spec    = prize_snapshot_json.rarity 文案化（直接取 rarity 字符串，前端按稀有度色 token 渲染）
     * machine = machine_snapshot_json.name
     * rarity  = prize_snapshot_json.rarity（SSR/SR/R/N）
     * </pre>
     * 任一 snapshot 解析失败 → 该字段置 null（不抛异常吞历史订单），返回固定 5 键 JSON。
     */
    private String mergeGachaProductSnapshot(String prizeJson, String machineJson) {
        JsonNode prize = readTree(prizeJson);
        JsonNode machine = readTree(machineJson);

        String name = textOrNull(prize, "name");
        String rarity = textOrNull(prize, "rarity");
        String cover = textOrNull(prize, "imageId");
        if (StrUtil.isBlank(cover)) {
            cover = textOrNull(machine, "coverImageId");
        }
        String machineName = textOrNull(machine, "name");

        ObjectNode merged = objectMapper.createObjectNode();
        merged.put("name", name);
        merged.put("cover", cover);
        merged.put("spec", rarity);
        merged.put("machine", machineName);
        merged.put("rarity", rarity);
        return merged.toString();
    }

    // ============================================================
    //  helpers
    // ============================================================

    private String normalizeBizType(String bizType) {
        if (BIZ_PREORDER.equals(bizType) || BIZ_GACHA.equals(bizType)) {
            return bizType;
        }
        return BIZ_ALL;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private JsonNode readTree(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            log.warn("[gz-unified-order] snapshot JSON 解析失败，置空字段：{}", ex.getMessage());
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private Long longOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isNumber() ? v.asLong() : null;
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}
