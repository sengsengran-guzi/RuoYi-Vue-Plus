package org.dromara.gz.ord.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.ord.domain.bo.GzOrdOrderQueryBo;
import org.dromara.gz.ord.domain.dto.OrdSnapshot;
import org.dromara.gz.ord.domain.dto.applet.SubmitOrderReq;
import org.dromara.gz.ord.domain.entity.GzOrdOrder;
import org.dromara.gz.ord.domain.entity.GzOrdProduct;
import org.dromara.gz.ord.domain.entity.GzOrdSku;
import org.dromara.gz.ord.domain.vo.GzOrdOrderAdminVO;
import org.dromara.gz.ord.domain.vo.applet.OrdOrderDetailVO;
import org.dromara.gz.ord.domain.vo.applet.OrdOrderListItemVO;
import org.dromara.gz.ord.domain.vo.applet.SubmitOrderVO;
import org.dromara.gz.ord.enums.ExpressCarrierEnum;
import org.dromara.gz.ord.enums.LogisticsStatusEnum;
import org.dromara.gz.ord.enums.OrdBusinessStatusEnum;
import org.dromara.gz.ord.enums.OrdChipStatusEnum;
import org.dromara.gz.ord.enums.OrdProductStatusEnum;
import org.dromara.gz.ord.exception.GzOrdErrorCode;
import org.dromara.gz.ord.mapper.GzOrdOrderMapper;
import org.dromara.gz.ord.mapper.GzOrdProductMapper;
import org.dromara.gz.ord.mapper.GzOrdSkuMapper;
import org.dromara.gz.ord.service.IGzOrdOrderService;
import org.dromara.gz.ord.service.IGzOrdSkuService;
import org.dromara.gz.ord.service.internal.OrdTimelineBuilder;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 预购订单服务实现（GZ-ORD-104，V1.1 业务线 A 端到端核心）。
 *
 * <p><b>跨域下单事务</b>（doc/10 §7.N6，决策 D1）：{@link #submit} 在 {@code @Transactional} 内：
 * ①校验商品/地址 → ②扣 SKU 库存（乐观锁，无限库存跳过）→ ③生成 order_no → ④INSERT gz_ord_order
 * (created，三段 snapshot) → ⑤调 PAY-101 {@code createBusinessOrder(preorder)} 写 gz_pay_transaction
 * 拿 5 参 → 任一失败整体回滚。PAY-101 createBusinessOrder 是 {@code REQUIRED}，mock 模式无远程阻塞
 * 副作用，随同回滚安全（强约束 #1）。</p>
 *
 * <p><b>order_no 即 gz_pay_transaction.business_order_no</b>：本服务用 {@link PayOrderNoGenerator}
 * 生成 PREORD- 订单号，作为 order_no 落库，并传给 PAY-101 当 businessOrderNo —— 支付回调 SPI
 * 据 business_order_no 反查本订单（doc/10 §6.N6 前缀分发后再用 business_order_no 定位）。
 * {@code pay_transaction_id}（微信交易号）建单时 NULL，回调 {@link #onPaid} 时回填（doc/11 §6.3）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzOrdOrderServiceImpl implements IGzOrdOrderService {

    private static final DateTimeFormatter DELIVERY_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** order_no 撞 UNIQUE 时重试次数（并发同日同序号兜底，与 PAY-001 同款） */
    private static final int ORDER_NO_RETRY = 3;

    private final GzOrdOrderMapper baseMapper;
    private final GzOrdProductMapper productMapper;
    private final GzOrdSkuMapper skuMapper;
    private final IGzOrdSkuService skuService;
    private final IGzPayTransactionService payTransactionService;
    private final PayOrderNoGenerator orderNoGenerator;
    private final IGzUserService userService;
    private final IGzUserAddressService addressService;
    /** 主图 file_id → 签名 URL（GZ-ORD-105 列表卡 / 详情商品图，图片用 image_id 强约束 #9） */
    private final IGzFileService fileService;
    /** Spring 注入的全局 ObjectMapper（snapshot JSON 序列化/反序列化；不用 JsonUtils 静态以便单测可注入） */
    private final ObjectMapper objectMapper;

    /** 主图解析失败回退占位图（与 GzOrdProductServiceImpl 同口径 R4）。 */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/placeholder/ord-product.png";

    // ============================================================
    //  AC2 跨域下单事务
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmitOrderVO submit(SubmitOrderReq req, Long userId) {
        if (userId == null) {
            throw new ServiceException("未登录", 401);
        }
        int qty = req.getQty();

        // ① 商品校验：on_shelf + 未截止（doc/10 §7.E1/E3）
        GzOrdProduct product = productMapper.selectById(req.getProductId());
        if (product == null) {
            throw new ServiceException(GzOrdErrorCode.PRODUCT_NOT_FOUND_MSG, GzOrdErrorCode.PRODUCT_NOT_FOUND);
        }
        boolean onShelf = OrdProductStatusEnum.ON_SHELF.getCode().equals(product.getStatus());
        boolean notExpired = product.getDeadlineTime() != null && product.getDeadlineTime().isAfter(LocalDateTime.now());
        if (!onShelf || !notExpired) {
            throw new ServiceException(GzOrdErrorCode.PRODUCT_OFF_MSG, GzOrdErrorCode.PRODUCT_OFF);
        }

        // ② SKU 校验（存在 + 属于该商品 + 启用）
        GzOrdSku sku = skuMapper.selectById(req.getSkuId());
        if (sku == null || !sku.getProductId().equals(product.getId())) {
            throw new ServiceException(GzOrdErrorCode.SKU_NOT_FOUND_MSG, GzOrdErrorCode.SKU_NOT_FOUND);
        }
        if (sku.getEnabled() == null || sku.getEnabled() != 1) {
            throw new ServiceException(GzOrdErrorCode.SKU_OUT_OF_STOCK_MSG, GzOrdErrorCode.SKU_OUT_OF_STOCK);
        }

        // ③ 地址校验：归属当前用户（getByIdForUser 非本人 → null，doc/10 §7.N6）
        GzUserAddressVO address = addressService.getByIdForUser(userId, req.getAddressId());
        if (address == null) {
            throw new ServiceException(GzOrdErrorCode.ADDRESS_INVALID_MSG, GzOrdErrorCode.ADDRESS_INVALID);
        }

        // ④ 取下单用户 openid（统一下单必需）
        GzUserVO user = userService.selectVoById(userId);
        if (user == null || StrUtil.isBlank(user.getOpenid())) {
            throw new ServiceException("用户信息异常，请重新登录", 401);
        }

        // ⑤ 扣 SKU 库存（乐观锁；无限库存 stock_remain IS NULL 跳过扣减；失败抛 SKU_OUT_OF_STOCK，整事务回滚）
        skuService.tryDeductStock(sku.getId(), qty);

        // ⑥ 金额后端重算（不信任前端）：total = sku.price_cent × qty（下单锁定，doc/11 §6.3 计算口径）
        long totalAmountCent = sku.getPriceCent() * (long) qty;

        // ⑦ 生成 order_no（PREORD-yyyyMMdd-6位，复用 PAY-101 统一订单号生成器）+ INSERT created（三段 snapshot 完整）
        GzOrdOrder order = insertCreatedOrder(req, userId, product, sku, address, qty, totalAmountCent);

        // ⑧ 调 PAY-101 建 gz_pay_transaction（business_type=preorder，REQUIRED 同事务，决策 D1）
        //    businessOrderNo = order_no，支付回调 SPI 据此定位订单
        CreateOrderBo payBo = CreateOrderBo.builder()
            .businessType(PayBusinessType.PREORDER)
            .businessOrderNo(order.getOrderNo())
            .amountCent(totalAmountCent)
            .openid(user.getOpenid())
            .userId(userId)
            .description("谷子宇宙预购 - " + truncate(product.getName(), 32))
            .build();
        MpPayParamsVO payParams = payTransactionService.createBusinessOrder(payBo);

        log.info("[gz-ord-order] submit ok orderNo={} ordOrderId={} userId={} skuId={} qty={} amount={} outTradeNo={}",
            order.getOrderNo(), order.getId(), userId, sku.getId(), qty, totalAmountCent, payParams.getOutTradeNo());

        return SubmitOrderVO.builder()
            .ordOrderId(order.getId())
            .orderNo(order.getOrderNo())
            .payParams(payParams)
            .build();
    }

    /**
     * 生成 order_no + INSERT created 订单（order_no 撞 UNIQUE 重试）。三段 snapshot 在此序列化落库。
     */
    private GzOrdOrder insertCreatedOrder(SubmitOrderReq req, Long userId, GzOrdProduct product,
                                          GzOrdSku sku, GzUserAddressVO address, int qty, long totalAmountCent) {
        String productSnapshot = writeJson(buildProductSnapshot(product));
        String skuSnapshot = writeJson(buildSkuSnapshot(sku));
        String addressSnapshot = writeJson(buildAddressSnapshot(address));

        DuplicateKeyException lastDup = null;
        for (int i = 0; i < ORDER_NO_RETRY; i++) {
            String orderNo = orderNoGenerator.generate(PayBusinessType.PREORDER);
            GzOrdOrder order = new GzOrdOrder();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setProductId(product.getId());
            order.setSkuId(sku.getId());
            order.setProductSnapshotJson(productSnapshot);
            order.setSkuSnapshotJson(skuSnapshot);
            order.setAddressSnapshotJson(addressSnapshot);
            order.setQty(qty);
            order.setTotalAmountCent(totalAmountCent);
            order.setBusinessStatus(OrdBusinessStatusEnum.CREATED.getCode());
            order.setLogisticsStatus(LogisticsStatusEnum.IN_JAPAN.getCode());
            // pay_transaction_id 建单时 NULL（回调 onPaid 回填，doc/11 §6.3）
            order.setUserNote(req.getUserNote());
            order.setVersion(0);
            try {
                baseMapper.insert(order);
                return order;
            } catch (DuplicateKeyException dup) {
                lastDup = dup;
                log.warn("[gz-ord-order] order_no 撞 UNIQUE 重试 {}/{}：{}", i + 1, ORDER_NO_RETRY, orderNo);
            }
        }
        throw new ServiceException("生成订单失败（order_no 连续冲突）", lastDup);
    }

    private OrdSnapshot.Product buildProductSnapshot(GzOrdProduct p) {
        return OrdSnapshot.Product.builder()
            .productId(String.valueOf(p.getId()))
            .productNo(p.getProductNo())
            .name(p.getName())
            .mainImageId(p.getMainImageId() == null ? null : String.valueOf(p.getMainImageId()))
            .ipTag(p.getIpTag())
            .deliveryDateText(p.getDeliveryDateText())
            .deliveryDateExact(p.getDeliveryDateExact() == null ? null : p.getDeliveryDateExact().format(DELIVERY_DATE_FMT))
            .build();
    }

    private OrdSnapshot.Sku buildSkuSnapshot(GzOrdSku s) {
        return OrdSnapshot.Sku.builder()
            .skuId(String.valueOf(s.getId()))
            .skuNo(s.getSkuNo())
            .specName(s.getSpecName())
            .priceCent(s.getPriceCent())
            .build();
    }

    private OrdSnapshot.Address buildAddressSnapshot(GzUserAddressVO a) {
        // F6.2：不含 receiver_id
        return OrdSnapshot.Address.builder()
            .recipient(a.getRecipientName())
            .mobile(a.getMobile())
            .province(a.getProvince())
            .city(a.getCity())
            .district(a.getDistrict())
            .detail(a.getDetail())
            .build();
    }

    // ============================================================
    //  AC6 取消订单（仅 created，行锁防与回调竞争，回滚库存）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long ordOrderId, Long userId) {
        if (userId == null) {
            throw new ServiceException("未登录", 401);
        }
        // SELECT ... FOR UPDATE 行锁（与 onPaid 互斥，doc/10 §7.E8 / 风险 R2）
        GzOrdOrder order = baseMapper.selectByIdForUpdate(ordOrderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new ServiceException(GzOrdErrorCode.ORDER_NOT_FOUND_MSG, GzOrdErrorCode.ORDER_NOT_FOUND);
        }
        // 仅 created 可取消；已 paid 走 PAY-103 退款（本卡不实现）
        if (!OrdBusinessStatusEnum.CREATED.getCode().equals(order.getBusinessStatus())) {
            throw new ServiceException(GzOrdErrorCode.ORDER_NOT_CANCELLABLE_MSG, GzOrdErrorCode.ORDER_NOT_CANCELLABLE);
        }
        int affected = baseMapper.markCancelled(order.getId(), LocalDateTime.now());
        if (affected == 0) {
            // 行锁内仍 0（理论罕见：版本漂移/并发回调抢先）→ 视为不可取消（不回滚库存）
            throw new ServiceException(GzOrdErrorCode.ORDER_NOT_CANCELLABLE_MSG, GzOrdErrorCode.ORDER_NOT_CANCELLABLE);
        }
        // 回滚 SKU 库存（无限库存跳过，决策 D5/D7）
        skuService.restoreStock(order.getSkuId(), order.getQty());
        log.info("[gz-ord-order] cancel ok orderNo={} ordOrderId={} userId={} 库存已回滚 skuId={} qty={}",
            order.getOrderNo(), order.getId(), userId, order.getSkuId(), order.getQty());
    }

    // ============================================================
    //  AC5 详情（snapshot 还原；供 pay-result 轮询）
    // ============================================================

    @Override
    public OrdOrderDetailVO getDetail(Long ordOrderId, Long userId) {
        if (userId == null) {
            return null;
        }
        GzOrdOrder order = baseMapper.selectById(ordOrderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return null;
        }
        return toDetailVO(order);
    }

    private OrdOrderDetailVO toDetailVO(GzOrdOrder o) {
        String chipStatus = OrdChipStatusEnum.fromBusinessStatus(o.getBusinessStatus());
        return OrdOrderDetailVO.builder()
            .id(o.getId())
            .orderNo(o.getOrderNo())
            .qty(o.getQty())
            .totalAmountCent(o.getTotalAmountCent())
            .businessStatus(o.getBusinessStatus())
            .businessStatusLabel(businessLabel(o.getBusinessStatus()))
            .logisticsStatus(o.getLogisticsStatus())
            .logisticsStatusLabel(logisticsLabel(o.getLogisticsStatus()))
            .chipStatus(chipStatus)
            .chipLabel(chipLabel(chipStatus))
            // GZ-ORD-105 AC2：4 节点时间线（无 closed）+ 国内快递公司中文名
            .timeline(OrdTimelineBuilder.build(o))
            .cnCarrierCode(o.getCnCarrierCode())
            .cnCarrierName(ExpressCarrierEnum.labelOf(o.getCnCarrierCode()))
            .product(readJson(o.getProductSnapshotJson(), OrdSnapshot.Product.class))
            .sku(readJson(o.getSkuSnapshotJson(), OrdSnapshot.Sku.class))
            .address(readJson(o.getAddressSnapshotJson(), OrdSnapshot.Address.class))
            .cnTrackingNo(o.getCnTrackingNo())
            .userNote(o.getUserNote())
            .paidTime(o.getPaidTime())
            .deliveredTime(o.getDeliveredTime())
            .cancelledTime(o.getCancelledTime())
            .createTime(toLocalDateTime(o.getCreateTime()))
            .build();
    }

    private String businessLabel(String code) {
        for (OrdBusinessStatusEnum e : OrdBusinessStatusEnum.values()) {
            if (e.getCode().equals(code)) {
                return e.getLabel();
            }
        }
        return code;
    }

    private String logisticsLabel(String code) {
        for (LogisticsStatusEnum e : LogisticsStatusEnum.values()) {
            if (e.getCode().equals(code)) {
                return e.getLabel();
            }
        }
        return code;
    }

    /** 统一 chip code → 中文 label（doc/11 §8.2：待支付/待发货/运输中/已完成/已取消/已退款）。 */
    private String chipLabel(String chipStatus) {
        return CHIP_LABELS.getOrDefault(chipStatus, "");
    }

    /** 统一 chip 中文 label 映射（doc/11 §8.2 钉死，all 不展示 label）。 */
    private static final Map<String, String> CHIP_LABELS = Map.of(
        "to_pay", "待支付",
        "to_ship", "待发货",
        "shipping", "运输中",
        "done", "已完成",
        "cancelled", "已取消",
        "refunded", "已退款"
    );

    // ============================================================
    //  GZ-ORD-105 AC1 mp 我的订单列表（chip → business_status 过滤，snapshot 渲染）
    // ============================================================

    @Override
    public TableDataInfo<OrdOrderListItemVO> pageForMp(String chipStatus, Long userId, PageQuery pageQuery) {
        if (userId == null) {
            return TableDataInfo.build(new Page<>());
        }
        // chip → business_status 过滤值（严格按 doc/11 §8.2；all / 非法 → null 不过滤）
        String businessStatus = OrdChipStatusEnum.toBusinessStatusCode(chipStatus);
        LambdaQueryWrapper<GzOrdOrder> lqw = new LambdaQueryWrapper<GzOrdOrder>()
            .eq(GzOrdOrder::getUserId, userId)
            .eq(StrUtil.isNotBlank(businessStatus), GzOrdOrder::getBusinessStatus, businessStatus)
            .orderByDesc(GzOrdOrder::getCreateTime);
        Page<GzOrdOrder> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<OrdOrderListItemVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toListItemVO).toList());
        return TableDataInfo.build(voPage);
    }

    private OrdOrderListItemVO toListItemVO(GzOrdOrder o) {
        OrdSnapshot.Product product = readJson(o.getProductSnapshotJson(), OrdSnapshot.Product.class);
        OrdSnapshot.Sku sku = readJson(o.getSkuSnapshotJson(), OrdSnapshot.Sku.class);
        String chipStatus = OrdChipStatusEnum.fromBusinessStatus(o.getBusinessStatus());
        return OrdOrderListItemVO.builder()
            .id(o.getId())
            .orderNo(o.getOrderNo())
            .productName(product == null ? null : product.getName())
            .productImageUrl(resolveImageUrl(product == null ? null : product.getMainImageId()))
            .specName(sku == null ? null : sku.getSpecName())
            .qty(o.getQty())
            .totalAmountCent(o.getTotalAmountCent())
            .businessStatus(o.getBusinessStatus())
            .logisticsStatus(o.getLogisticsStatus())
            .chipStatus(chipStatus)
            .chipLabel(chipLabel(chipStatus))
            .createTime(toLocalDateTime(o.getCreateTime()))
            .build();
    }

    /** snapshot 主图 file_id（String）→ 签名 URL；空 / 解析失败 → 占位图（强约束 #9 / R4）。 */
    private String resolveImageUrl(String mainImageId) {
        if (StrUtil.isBlank(mainImageId)) {
            return PLACEHOLDER_IMAGE_URL;
        }
        try {
            return fileService.getPresignedUrl(Long.parseLong(mainImageId)).getUrl();
        } catch (Exception ex) {
            log.warn("[gz-ord-order] 订单主图解析失败 fileId={}，回退占位图：{}", mainImageId, ex.getMessage());
            return PLACEHOLDER_IMAGE_URL;
        }
    }

    // ============================================================
    //  GZ-ORD-105 AC5/AC6 admin 只读订单列表 + 详情（关联 gz_user 取手机号）
    // ============================================================

    @Override
    public TableDataInfo<GzOrdOrderAdminVO> pageForAdmin(GzOrdOrderQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzOrdOrder> lqw = new LambdaQueryWrapper<>();
        if (query != null) {
            lqw.eq(StrUtil.isNotBlank(query.getBusinessStatus()),
                GzOrdOrder::getBusinessStatus, query.getBusinessStatus());
            lqw.like(StrUtil.isNotBlank(query.getOrderNo()),
                GzOrdOrder::getOrderNo, query.getOrderNo());
            // userPhone 模糊：先解析 user_id 集合（无匹配 → 强制空结果，避免误返全量）
            if (StrUtil.isNotBlank(query.getUserPhone())) {
                List<Long> userIds = userService.selectIdsByMobileLike(query.getUserPhone());
                if (userIds.isEmpty()) {
                    return TableDataInfo.build(new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize(), 0));
                }
                lqw.in(GzOrdOrder::getUserId, userIds);
            }
        }
        lqw.orderByDesc(GzOrdOrder::getCreateTime);
        Page<GzOrdOrder> page = baseMapper.selectPage(pageQuery.build(), lqw);

        // 批量取用户手机号 / 昵称（防 N+1）
        List<Long> orderUserIds = page.getRecords().stream()
            .map(GzOrdOrder::getUserId).distinct().toList();
        Map<Long, GzUserVO> userMap = orderUserIds.isEmpty()
            ? Collections.emptyMap()
            : userService.selectVoMapByIds(orderUserIds);

        Page<GzOrdOrderAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
            .map(o -> toAdminVO(o, userMap.get(o.getUserId()), false))
            .collect(Collectors.toList()));
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzOrdOrderAdminVO getDetailForAdmin(Long ordOrderId) {
        if (ordOrderId == null) {
            return null;
        }
        GzOrdOrder order = baseMapper.selectById(ordOrderId);
        if (order == null) {
            return null;
        }
        GzUserVO user = userService.selectVoById(order.getUserId());
        return toAdminVO(order, user, true);
    }

    /**
     * 订单实体 → admin VO。{@code withSnapshot=true}（详情）才解析三段 snapshot 区，列表省带宽只投影核心字段。
     */
    private GzOrdOrderAdminVO toAdminVO(GzOrdOrder o, GzUserVO user, boolean withSnapshot) {
        GzOrdOrderAdminVO vo = new GzOrdOrderAdminVO();
        vo.setId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setUserId(o.getUserId());
        if (user != null) {
            vo.setUserPhone(user.getMobile());
            vo.setUserNickname(user.getNickname());
        }
        OrdSnapshot.Product product = readJson(o.getProductSnapshotJson(), OrdSnapshot.Product.class);
        OrdSnapshot.Sku sku = readJson(o.getSkuSnapshotJson(), OrdSnapshot.Sku.class);
        vo.setProductName(product == null ? null : product.getName());
        vo.setSpecName(sku == null ? null : sku.getSpecName());
        vo.setQty(o.getQty());
        vo.setTotalAmountCent(o.getTotalAmountCent());
        vo.setBusinessStatus(o.getBusinessStatus());
        vo.setBusinessStatusLabel(businessLabel(o.getBusinessStatus()));
        vo.setLogisticsStatus(o.getLogisticsStatus());
        vo.setLogisticsStatusLabel(logisticsLabel(o.getLogisticsStatus()));
        vo.setCnCarrierCode(o.getCnCarrierCode());
        vo.setCnCarrierName(ExpressCarrierEnum.labelOf(o.getCnCarrierCode()));
        vo.setCnTrackingNo(o.getCnTrackingNo());
        vo.setUserNote(o.getUserNote());
        vo.setPaidTime(o.getPaidTime());
        vo.setDeliveredTime(o.getDeliveredTime());
        vo.setCancelledTime(o.getCancelledTime());
        vo.setCreateTime(toLocalDateTime(o.getCreateTime()));
        if (withSnapshot) {
            OrdSnapshot.Address addr = readJson(o.getAddressSnapshotJson(), OrdSnapshot.Address.class);
            if (addr != null) {
                vo.setRecipient(addr.getRecipient());
                vo.setRecipientMobile(addr.getMobile());
                vo.setFullAddress(joinAddress(addr));
            }
        }
        return vo;
    }

    /** 拼接完整收货地址（省+市+区+详细，null 段跳过）。 */
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

    // ============================================================
    //  AC3 支付成功回调（onPaid，PAY-101 回调事务内 REQUIRED）/ 退款回调（onRefunded）
    // ============================================================

    @Override
    public void onPaid(GzPayTransaction txn) {
        String orderNo = txn.getBusinessOrderNo();
        if (StrUtil.isBlank(orderNo)) {
            log.warn("[gz-ord-order] onPaid business_order_no 为空 out_trade_no={} 跳过", txn.getOutTradeNo());
            return;
        }
        // 行锁定位订单（与用户 cancel 互斥，风险 R2）
        GzOrdOrder order = baseMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            // 订单不存在 → 抛错让回调事务回滚 + 微信重试 + PAY-102 兜底（doc/10 §6.E2）
            throw new ServiceException("预购订单不存在 orderNo=" + orderNo);
        }
        // 幂等：仅 created → paid；已 paid（重复回调）affected=0 直接跳过（双层幂等，风险 R5）
        int affected = baseMapper.markPaid(order.getId(), txn.getTransactionId(), txn.getPaidTime());
        if (affected == 0) {
            log.info("[gz-ord-order] onPaid 幂等跳过（非 created）orderNo={} status={}", orderNo, order.getBusinessStatus());
            return;
        }
        // 累加商品销量（原子 UPDATE，doc/11 §6.1 sales_count）
        productMapper.increaseSalesCount(order.getProductId(), order.getQty());
        log.info("[gz-ord-order] onPaid ok orderNo={} ordOrderId={} → paid transaction_id={} paid_time={}",
            orderNo, order.getId(), txn.getTransactionId(), txn.getPaidTime());
    }

    @Override
    public void onRefunded(GzPayTransaction txn) {
        String orderNo = txn.getBusinessOrderNo();
        if (StrUtil.isBlank(orderNo)) {
            log.warn("[gz-ord-order] onRefunded business_order_no 为空 out_trade_no={} 跳过", txn.getOutTradeNo());
            return;
        }
        GzOrdOrder order = baseMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            throw new ServiceException("预购订单不存在 orderNo=" + orderNo);
        }
        // paid → refunded；SKU 库存不归还（货已采购，doc/10 §6.N10）
        int affected = baseMapper.markRefunded(order.getId());
        if (affected == 0) {
            log.info("[gz-ord-order] onRefunded 幂等跳过（非 paid）orderNo={} status={}", orderNo, order.getBusinessStatus());
            return;
        }
        log.info("[gz-ord-order] onRefunded ok orderNo={} ordOrderId={} → refunded（SKU 库存不归还）",
            orderNo, order.getId());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** ruoyi BaseEntity.createTime 是 java.util.Date，VO 用 LocalDateTime（系统默认时区转换）。 */
    private static LocalDateTime toLocalDateTime(java.util.Date date) {
        return date == null ? null
            : date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    /** snapshot 序列化（注入 ObjectMapper，避免 JsonUtils 静态 Spring 依赖，便于单测）。 */
    private String writeJson(Object snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new ServiceException("snapshot 序列化失败: " + e.getMessage());
        }
    }

    /** snapshot 反序列化（读详情时还原 POJO）。 */
    private <T> T readJson(String json, Class<T> clazz) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new ServiceException("snapshot 反序列化失败: " + e.getMessage());
        }
    }
}
