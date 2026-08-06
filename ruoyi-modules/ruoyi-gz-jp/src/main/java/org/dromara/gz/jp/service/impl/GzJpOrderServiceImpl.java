package org.dromara.gz.jp.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.domain.bo.GzJpOrderSubmitBo;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;
import org.dromara.gz.jp.domain.entity.GzJpCartItem;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.entity.GzJpProduct;
import org.dromara.gz.jp.domain.enums.GzJpEventStatus;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpItemSource;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpProductStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderListItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderSubmitVO;
import org.dromara.gz.jp.exception.GzJpOrderErrorCode;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.mapper.GzJpProductMapper;
import org.dromara.gz.jp.service.IGzJpCartService;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.dromara.gz.jp.service.IGzJpOrderService;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拼团订单服务实现（GZ-JP-105，FLOW:F-JP-02.step4/step5/step6）。
 *
 * <p>范式照抄 {@code GzOrdOrderServiceImpl.submit}（跨域事务 + 快照 + 统一建单），
 * 但<b>表不复用</b>：gz_ord_* 服务的是另一个小程序的另一批用户与另一套业务规则。</p>
 *
 * <p><b>下单事务的顺序是有讲究的</b>（不要随手调换）：</p>
 * <ol>
 *   <li>先<b>全量校验</b>再写任何一行 —— 一单几十款，写到第 20 行才发现第 21 款下架了，
 *       靠回滚也能对，但错误信息就只能说「下单失败」而说不出是哪件。</li>
 *   <li>订单与订单行<b>一次性配齐字段 insert</b>，绝不 insert 后 updateById 补字段 ——
 *       {@code @Version} 实体的内存 version 在 insert 后仍是 null，补写会静默不落库
 *       （GZ-BEAN-039 踩过：座位号补写丢失 → 防超卖失效还返 200）。</li>
 *   <li>清购物车放在<b>建支付单之前</b>：{@code deleteItems} 是 REQUIRED 传播，
 *       与本事务同生共死，下单失败车不会被清。</li>
 *   <li>建支付单<b>最后</b>：它是唯一可能触网的一步（real 模式调微信统一下单），
 *       放最后能让前面所有纯 DB 校验先失败掉，少打无谓的微信请求。</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpOrderServiceImpl implements IGzJpOrderService {

    /** order_no 撞 UNIQUE 时的重试次数（与 GZ-PAY out_trade_no 同款；每次重试前先把序号对齐 DB） */
    private static final int ORDER_NO_RETRY = 5;

    /** 列表卡最多下发几张缩略图（一单 30 款，列表页放不下也不需要） */
    private static final int LIST_THUMB_MAX = 4;

    /** 图片缺失 / 解析失败 / 商品已删时的占位图（与 GZ-JP-103/104 同一张） */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    private final GzJpOrderMapper baseMapper;
    private final GzJpOrderItemMapper itemMapper;
    private final GzJpProductMapper productMapper;
    /** 场的「生效状态」判定收口在这里（读时惰性，含到点自动结束）—— 本类不重写一份 */
    private final IGzJpEventService eventService;
    private final IGzJpCartService cartService;
    private final IGzPayTransactionService payTransactionService;
    private final PayOrderNoGenerator orderNoGenerator;
    private final IGzUserService userService;
    private final IGzUserAddressService addressService;
    private final IGzFileService fileService;
    /** Spring 全局 ObjectMapper（快照 JSON 序列化/反序列化；构造注入以便单测替换） */
    private final ObjectMapper objectMapper;

    // ============================================================
    //  下单（FLOW:F-JP-02.step4）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzJpOrderSubmitVO submit(GzJpOrderSubmitBo bo, Long userId) {
        if (ObjectUtil.isNull(userId)) {
            throw new ServiceException("未登录", 401);
        }

        // ① 取本人勾选的购物车行（service 层已收口 user_id 条件，传别人的 id 拿不到行）
        List<GzJpCartItem> cartItems = cartService.selectByUserAndIds(userId, bo.getCartItemIds());
        if (cartItems.isEmpty()) {
            // 车被清空 / 换了设备 / 传的全是别人的 id —— 对客人都是「没东西可结算」
            throw new ServiceException(GzJpOrderErrorCode.EMPTY_SELECTION_MSG, GzJpOrderErrorCode.EMPTY_SELECTION);
        }

        // ② 批量取商品与场（一单可跨多场，逐条查就是 N+1）
        Set<Long> productIds = new LinkedHashSet<>(cartItems.stream().map(GzJpCartItem::getProductId).toList());
        Map<Long, GzJpProduct> productMap = new HashMap<>();
        for (GzJpProduct p : productMapper.selectByIds(productIds)) {
            productMap.put(p.getId(), p);
        }
        Set<Long> eventIds = new LinkedHashSet<>(productMap.values().stream().map(GzJpProduct::getEventId).toList());
        // status 已是「生效状态」（读时惰性判定，含到 end_time 自动结束）—— 判定逻辑只有
        // GzJpEventStatus.effective 一处实现，这里不重写；批量取避免逐条 isBookable 的 N+1
        Map<Long, GzJpEventOptionVO> eventMap = eventService.selectOptionMap(eventIds);

        // ③ 逐行校验 —— ★ 先全查一遍再报错，一次说清所有失效项（不然客人要一件一件试）
        List<String> invalidNames = new ArrayList<>();
        for (GzJpCartItem ci : cartItems) {
            String reason = checkNotBookable(productMap.get(ci.getProductId()), eventMap);
            if (reason != null) {
                invalidNames.add(reason);
            }
        }
        if (!invalidNames.isEmpty()) {
            // 前端拿 ITEM_INVALID 应退回购物车并高亮，别停在确认页反复重试（GZ-JP-204）
            throw new ServiceException(
                "以下商品已失效，请回购物车处理后再结算：" + String.join("、", invalidNames),
                GzJpOrderErrorCode.ITEM_INVALID);
        }

        // ④ 地址校验：必须是本人的（getByIdForUser 对非本人返回 null）
        GzUserAddressVO address = addressService.getByIdForUser(userId, bo.getAddressId());
        if (address == null) {
            throw new ServiceException(GzJpOrderErrorCode.ADDRESS_INVALID_MSG, GzJpOrderErrorCode.ADDRESS_INVALID);
        }

        // ⑤ 取 openid（统一下单必需；★ 拼团小程序的用户，openid 由拼团 appid 签发，与建单 appid 同源）
        GzUserVO user = userService.selectVoById(userId);
        if (user == null || StrUtil.isBlank(user.getOpenid())) {
            throw new ServiceException("用户信息异常，请重新登录", 401);
        }

        // ⑥ ★ 后端重算金额：Σ(当前商品单价 × 数量)。前端传来的任何价一概不采信
        //    「当前」= 下单这一刻的 gz_jp_product.price_cent，不是加购那一刻（购物车不存价格快照）
        long totalAmountCent = 0L;
        for (GzJpCartItem ci : cartItems) {
            GzJpProduct p = productMap.get(ci.getProductId());
            totalAmountCent += lineAmount(p, ci.getQty());
        }
        if (totalAmountCent < 1) {
            // 全部行金额为 0（商品价配成 0）—— 微信统一下单最低 1 分，与其让通道报错不如这里说明白
            throw new ServiceException("订单金额异常，请联系客服", GzJpOrderErrorCode.ITEM_INVALID);
        }
        // 确认页金额比对（可选）：只报错、绝不采信前端值作为成交价
        if (bo.getExpectedAmountCent() != null && bo.getExpectedAmountCent() != totalAmountCent) {
            log.warn("[gz-jp-order] 前端金额与后端重算不一致 userId={} 前端={} 后端={}",
                userId, bo.getExpectedAmountCent(), totalAmountCent);
            throw new ServiceException(
                "商品价格有变动，当前应付 " + yuan(totalAmountCent) + " 元，请确认后重新提交",
                GzJpOrderErrorCode.PRICE_CHANGED);
        }

        // ⑦ 生成 order_no + 一次性配齐字段 INSERT 订单（撞 UNIQUE 重试）
        String addressSnapshot = writeJson(buildAddressSnapshot(address));
        GzJpOrder order = insertCreatedOrder(userId, totalAmountCent, addressSnapshot, bo.getUserNote());

        // ⑧ 逐行 INSERT 订单行（含商品快照）—— 同样一次配齐，不 insert 后补 update
        int totalQty = 0;
        for (GzJpCartItem ci : cartItems) {
            GzJpProduct p = productMap.get(ci.getProductId());
            GzJpEventOptionVO event = eventMap.get(p.getEventId());
            int qty = ObjectUtil.defaultIfNull(ci.getQty(), 1);
            totalQty += qty;
            itemMapper.insert(buildOrderItem(order, userId, p, event, qty));
        }

        // ⑨ 清掉已结算的购物车行（REQUIRED 传播，与本事务同生共死：下单失败车不会被清）
        //    ★★ 这一刀同时是【防重复提交的串行点】——真库 10 并发实测：不设这道闸，
        //    同一份车会生成 10 张订单（疯狂双击 / 弱网重发都会走到这）。
        //    原理：DELETE 在事务内对命中行加 X 锁，第二个事务阻塞到第一个提交后才执行，
        //    此时行已不在 → 删到 0 行 → 与「读到的行数」对不上 → 抛错整单回滚。
        //    最终恰好一张订单落库，其余请求拿到 4107（前端不该重试，去订单列表看那张单）。
        int cleared = cartService.deleteItems(userId, bo.getCartItemIds());
        if (cleared != cartItems.size()) {
            log.warn("[gz-jp-order] 重复提交拦截 userId={} 读到 {} 行、实删 {} 行（并发下单 / 另一端刚删了车）",
                userId, cartItems.size(), cleared);
            throw new ServiceException(GzJpOrderErrorCode.DUPLICATE_SUBMIT_MSG, GzJpOrderErrorCode.DUPLICATE_SUBMIT);
        }

        // ⑩ 调 GZ-PAY 统一建单（business_type='jp'，businessOrderNo = order_no）
        //    appid 由 PayAppidResolver 按本次请求 clientid 解析（GZ-SYS-022）——
        //    submit 是同步 HTTP 请求触发、请求上下文里有 clientid，故不需要显式传 appid
        CreateOrderBo payBo = CreateOrderBo.builder()
            .businessType(PayBusinessType.JP)
            .businessOrderNo(order.getOrderNo())
            .amountCent(totalAmountCent)
            .openid(user.getOpenid())
            .userId(userId)
            .description(buildPayDescription(cartItems, productMap))
            .build();
        MpPayParamsVO payParams = payTransactionService.createBusinessOrder(payBo);

        log.info("[gz-jp-order] submit ok orderNo={} orderId={} userId={} 款数={} 件数={} amount={} outTradeNo={}",
            order.getOrderNo(), order.getId(), userId, cartItems.size(), totalQty, totalAmountCent,
            payParams.getOutTradeNo());

        return GzJpOrderSubmitVO.builder()
            .orderId(order.getId())
            .orderNo(order.getOrderNo())
            .totalAmountCent(totalAmountCent)
            .itemCount(cartItems.size())
            .payParams(payParams)
            .build();
    }

    /**
     * 单项可下单性校验（写路径口径：商品 on_shelf + 所属场生效状态 open）。
     *
     * @return null = 可下单；否则返回给客人看的失效说明（含商品名）
     */
    private String checkNotBookable(GzJpProduct product, Map<Long, GzJpEventOptionVO> eventMap) {
        if (product == null) {
            // 商品已被删（selectByIds 过滤软删）—— 拿不到名字，只能给个通用说法
            return "有商品已下架";
        }
        if (GzJpProductStatus.of(product.getStatus()) != GzJpProductStatus.ON_SHELF) {
            return "「" + product.getName() + "」已下架";
        }
        GzJpEventOptionVO event = eventMap.get(product.getEventId());
        // ★ 写路径认严格闸 isBookable：生效状态必须是 open（关场 / 到 end_time 立刻不可下单）。
        //   场被删 / draft / closed 对客人都是「这场不收单了」
        if (event == null || !GzJpEventStatus.OPEN.getCode().equals(event.getStatus())) {
            return "「" + product.getName() + "」所在的场已结束";
        }
        return null;
    }

    /** 行金额 = 当前单价 × 数量（单价缺失按 0，配合 ⑥ 的总额下限守卫兜住脏数据）。 */
    private long lineAmount(GzJpProduct product, Integer qty) {
        long price = product == null ? 0L : ObjectUtil.defaultIfNull(product.getPriceCent(), 0L);
        return price * ObjectUtil.defaultIfNull(qty, 0);
    }

    /**
     * 生成 order_no + INSERT created 订单（撞 UNIQUE 重试）。
     *
     * <p>order_no 与 out_trade_no 共用 {@link PayOrderNoGenerator} 的当日号段（Redis 原子自增 +
     * DB MAX 播种）。撞 UNIQUE 只可能是 Redis 计数器落后 DB（如 Redis 被 flush / 从旧快照恢复），
     * 故重试前先 {@code reconcileOutTradeNoToDbMax} 把计数器抬过 DB 当日最大值 —— 部署即自愈，
     * 不必人工 DEL Redis key（prod 曾因此报「out_trade_no 连续冲突」）。</p>
     */
    private GzJpOrder insertCreatedOrder(Long userId, long totalAmountCent, String addressSnapshot, String userNote) {
        DuplicateKeyException lastDup = null;
        for (int i = 0; i < ORDER_NO_RETRY; i++) {
            String orderNo = orderNoGenerator.generate(PayBusinessType.JP);
            GzJpOrder order = new GzJpOrder();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setTotalAmountCent(totalAmountCent);
            order.setBusinessStatus(GzJpOrderStatus.CREATED.getCode());
            // pay_transaction_id 建单时 NULL —— 支付回调回填（此刻支付流水还没建）
            order.setAddressSnapshotJson(addressSnapshot);
            order.setUserNote(userNote);
            order.setVersion(0);
            try {
                baseMapper.insert(order);
                return order;
            } catch (DuplicateKeyException dup) {
                lastDup = dup;
                log.warn("[gz-jp-order] order_no 撞 UNIQUE 重试 {}/{}：{}", i + 1, ORDER_NO_RETRY, orderNo);
                orderNoGenerator.reconcileOutTradeNoToDbMax(PayBusinessType.JP);
            }
        }
        throw new ServiceException("生成订单失败（order_no 连续冲突）", lastDup);
    }

    /**
     * 装配一条订单行（★ 一次性配齐所有字段，绝不 insert 后补 update）。
     */
    private GzJpOrderItem buildOrderItem(GzJpOrder order, Long userId, GzJpProduct p, GzJpEventOptionVO event, int qty) {
        long unitPrice = ObjectUtil.defaultIfNull(p.getPriceCent(), 0L);
        GzJpOrderItem item = new GzJpOrderItem();
        item.setOrderId(order.getId());
        // 冗余 user_id：履约看板按客人聚合且跨订单（REQ-FULFILL-006）
        item.setUserId(userId);
        item.setProductId(p.getId());
        item.setProductSnapshotJson(writeJson(buildProductSnapshot(p, event)));
        item.setQty(qty);
        item.setUnitPriceCent(unitPrice);
        item.setAmountCent(unitPrice * qty);
        // ★ 一期恒 batch；二期代切加 snap（REQ-SNAP-004），只加枚举值不改表
        item.setSource(GzJpItemSource.BATCH.getCode());
        // 履约起点；支付回调再显式激活一次（见 GzJpOrderItemMapper.activatePurchasing 注释）
        item.setFulfillStatus(GzJpFulfillStatus.PURCHASING.getCode());
        item.setVersion(0);
        return item;
    }

    private JpOrderSnapshot.Product buildProductSnapshot(GzJpProduct p, GzJpEventOptionVO event) {
        return JpOrderSnapshot.Product.builder()
            .productId(String.valueOf(p.getId()))
            .productNo(p.getProductNo())
            .name(p.getName())
            // ★ 存 file id 不存 URL（URL 是 1h 预签名，快照进去一小时后全是裂图）
            .mainImageId(p.getMainImageId() == null ? null : String.valueOf(p.getMainImageId()))
            .priceCent(p.getPriceCent())
            .deliveryDateText(p.getDeliveryDateText())
            // ★ 甲方点名的「额外注意事项」必须锁进快照：客人当时接受的条款，事后改商品不能反过来改它
            .noticeText(p.getNoticeText())
            .eventId(p.getEventId() == null ? null : String.valueOf(p.getEventId()))
            .eventNo(event == null ? null : event.getEventNo())
            .eventName(event == null ? null : event.getName())
            .build();
    }

    private JpOrderSnapshot.Address buildAddressSnapshot(GzUserAddressVO a) {
        // 不含地址簿主键：客人删了那条地址，订单收货信息不受影响也不该反查
        return JpOrderSnapshot.Address.builder()
            .recipient(a.getRecipientName())
            .mobile(a.getMobile())
            .province(a.getProvince())
            .city(a.getCity())
            .district(a.getDistrict())
            .detail(a.getDetail())
            .build();
    }

    /** 微信支付「商品描述」（客人账单里看到的那行；多款时给首款 + N 件，不堆全名）。 */
    private String buildPayDescription(List<GzJpCartItem> cartItems, Map<Long, GzJpProduct> productMap) {
        GzJpProduct first = productMap.get(cartItems.get(0).getProductId());
        String name = first == null ? "商品" : truncate(first.getName(), 20);
        return cartItems.size() == 1
            ? "谷子宇宙拼团 - " + name
            : "谷子宇宙拼团 - " + name + " 等 " + cartItems.size() + " 款";
    }

    // ============================================================
    //  支付回调（FLOW:F-JP-02.step6）
    // ============================================================

    @Override
    public void onPaid(GzPayTransaction txn) {
        String orderNo = txn.getBusinessOrderNo();
        if (StrUtil.isBlank(orderNo)) {
            log.warn("[gz-jp-order] onPaid business_order_no 为空 out_trade_no={} 跳过", txn.getOutTradeNo());
            return;
        }
        // 行锁定位（与客人取消 / 并发的第二次回调互斥）
        GzJpOrder order = baseMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            // 订单不存在 → 抛错让整笔回调事务回滚 → 微信重试 + GZ-PAY 主动查单兜底
            throw new ServiceException("拼团订单不存在 orderNo=" + orderNo);
        }
        // ★ 幂等第一道：仅 created → paid；重复回调 affected=0 直接返回，绝不重复推进商品行
        int affected = baseMapper.markPaid(order.getId(), txn.getId(), txn.getPaidTime());
        if (affected == 0) {
            log.info("[gz-jp-order] onPaid 幂等跳过（非 created）orderNo={} status={}", orderNo, order.getBusinessStatus());
            return;
        }
        // 全部商品行进入履约起点（★ 幂等第二道在 SQL 的 WHERE 里：只碰仍在 purchasing 的行，
        // 万一回调被重放也不会把已推进到「日本仓库已发货」的行打回去）
        int activated = itemMapper.activatePurchasing(order.getId());
        log.info("[gz-jp-order] onPaid ok orderNo={} orderId={} → paid payTxnId={} 激活商品行={} 行",
            orderNo, order.getId(), txn.getId(), activated);
    }

    // ============================================================
    //  读（mp 订单列表 / 详情）
    // ============================================================

    @Override
    public GzJpOrderDetailVO getDetail(Long orderId, Long userId) {
        if (ObjectUtil.isNull(userId) || ObjectUtil.isNull(orderId)) {
            return null;
        }
        GzJpOrder order = baseMapper.selectById(orderId);
        // 不是本人的单 → 与「不存在」同样返回 null，不泄漏别人有没有这单
        if (order == null || !order.getUserId().equals(userId)) {
            return null;
        }
        List<GzJpOrderItem> items = itemMapper.selectList(
            Wrappers.<GzJpOrderItem>lambdaQuery()
                .eq(GzJpOrderItem::getOrderId, orderId)
                .orderByAsc(GzJpOrderItem::getId));

        Map<Long, String> urlCache = new HashMap<>();
        List<GzJpOrderItemVO> itemVOs = new ArrayList<>(items.size());
        int totalQty = 0;
        for (GzJpOrderItem item : items) {
            itemVOs.add(toItemVO(item, urlCache));
            totalQty += ObjectUtil.defaultIfNull(item.getQty(), 0);
        }

        GzJpOrderDetailVO vo = new GzJpOrderDetailVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTotalAmountCent(order.getTotalAmountCent());
        vo.setBusinessStatus(order.getBusinessStatus());
        vo.setBusinessStatusLabel(GzJpOrderStatus.labelOf(order.getBusinessStatus()));
        vo.setUserNote(order.getUserNote());
        vo.setAddress(readJson(order.getAddressSnapshotJson(), JpOrderSnapshot.Address.class));
        vo.setItems(itemVOs);
        vo.setItemCount(itemVOs.size());
        vo.setTotalQty(totalQty);
        vo.setCreateTime(toLocalDateTime(order.getCreateTime()));
        vo.setPaidTime(order.getPaidTime());
        vo.setCancelledTime(order.getCancelledTime());
        return vo;
    }

    @Override
    public TableDataInfo<GzJpOrderListItemVO> selectMyPage(Long userId, String businessStatus, PageQuery pageQuery) {
        if (ObjectUtil.isNull(userId)) {
            throw new ServiceException("未登录", 401);
        }
        LambdaQueryWrapper<GzJpOrder> lqw = Wrappers.<GzJpOrder>lambdaQuery()
            .eq(GzJpOrder::getUserId, userId)
            .eq(StrUtil.isNotBlank(businessStatus), GzJpOrder::getBusinessStatus, businessStatus)
            .orderByDesc(GzJpOrder::getId);
        Page<GzJpOrder> page = baseMapper.selectPage(pageQuery.build(), lqw);

        Page<GzJpOrderListItemVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        if (page.getRecords().isEmpty()) {
            voPage.setRecords(List.of());
            return TableDataInfo.build(voPage);
        }
        // 一次批量取本页所有订单的行（逐单查行就是 N+1）
        List<Long> orderIds = page.getRecords().stream().map(GzJpOrder::getId).toList();
        List<GzJpOrderItem> items = itemMapper.selectList(
            Wrappers.<GzJpOrderItem>lambdaQuery()
                .in(GzJpOrderItem::getOrderId, orderIds)
                .orderByAsc(GzJpOrderItem::getId));
        Map<Long, List<GzJpOrderItem>> byOrder = new HashMap<>();
        for (GzJpOrderItem item : items) {
            byOrder.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(item);
        }
        Map<Long, String> urlCache = new HashMap<>();
        voPage.setRecords(page.getRecords().stream()
            .map(o -> toListVO(o, byOrder.getOrDefault(o.getId(), List.of()), urlCache))
            .toList());
        return TableDataInfo.build(voPage);
    }

    private GzJpOrderListItemVO toListVO(GzJpOrder o, List<GzJpOrderItem> items, Map<Long, String> urlCache) {
        GzJpOrderListItemVO vo = new GzJpOrderListItemVO();
        vo.setId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setTotalAmountCent(o.getTotalAmountCent());
        vo.setBusinessStatus(o.getBusinessStatus());
        vo.setBusinessStatusLabel(GzJpOrderStatus.labelOf(o.getBusinessStatus()));
        vo.setItemCount(items.size());
        int totalQty = 0;
        List<String> thumbs = new ArrayList<>(LIST_THUMB_MAX);
        for (GzJpOrderItem item : items) {
            totalQty += ObjectUtil.defaultIfNull(item.getQty(), 0);
            if (thumbs.size() < LIST_THUMB_MAX) {
                JpOrderSnapshot.Product snap = readJson(item.getProductSnapshotJson(), JpOrderSnapshot.Product.class);
                thumbs.add(resolveImageUrl(snap == null ? null : snap.getMainImageId(), urlCache));
            }
        }
        vo.setTotalQty(totalQty);
        vo.setThumbUrls(thumbs);
        vo.setCreateTime(toLocalDateTime(o.getCreateTime()));
        vo.setPaidTime(o.getPaidTime());
        return vo;
    }

    private GzJpOrderItemVO toItemVO(GzJpOrderItem item, Map<Long, String> urlCache) {
        JpOrderSnapshot.Product snap = readJson(item.getProductSnapshotJson(), JpOrderSnapshot.Product.class);
        GzJpOrderItemVO vo = new GzJpOrderItemVO();
        vo.setId(item.getId());
        vo.setProductId(item.getProductId());
        vo.setQty(item.getQty());
        vo.setUnitPriceCent(item.getUnitPriceCent());
        vo.setAmountCent(item.getAmountCent());
        vo.setSource(item.getSource());
        vo.setFulfillStatus(item.getFulfillStatus());
        vo.setFulfillStatusLabel(GzJpFulfillStatus.labelOf(item.getFulfillStatus()));
        vo.setCarrierCode(item.getCarrierCode());
        vo.setTrackingNo(item.getTrackingNo());
        vo.setShippedAt(item.getShippedAt());
        vo.setRefundStatus(item.getRefundStatus());
        vo.setRefundStatusLabel(item.getRefundStatus() == null ? null : GzJpRefundStatus.labelOf(item.getRefundStatus()));
        vo.setRefundAmountCent(item.getRefundAmountCent());
        // ★ 商品信息一律读快照，不回查商品表：改名 / 下架 / 删除都不影响已下单的展示
        if (snap != null) {
            vo.setProductNo(snap.getProductNo());
            vo.setName(snap.getName());
            vo.setDeliveryDateText(snap.getDeliveryDateText());
            vo.setNoticeText(snap.getNoticeText());
            vo.setEventName(snap.getEventName());
            vo.setMainImageUrl(resolveImageUrl(snap.getMainImageId(), urlCache));
        } else {
            // 快照损坏（历史脏数据）：宁可少显示，也不要让整个订单详情 500
            vo.setName("商品");
            vo.setMainImageUrl(PLACEHOLDER_IMAGE_URL);
        }
        return vo;
    }

    /**
     * 快照里的 file id（string）→ 可渲染签名 URL。null / 已删 / 空串 → 占位图，<b>绝不返回 null</b>
     * （mp {@code <image>} 拿到 null 渲染成裂图）。
     */
    private String resolveImageUrl(String fileIdStr, Map<Long, String> urlCache) {
        if (StrUtil.isBlank(fileIdStr)) {
            return PLACEHOLDER_IMAGE_URL;
        }
        Long fileId;
        try {
            fileId = Long.valueOf(fileIdStr);
        } catch (NumberFormatException ex) {
            return PLACEHOLDER_IMAGE_URL;
        }
        String cached = urlCache.get(fileId);
        if (cached != null) {
            return cached;
        }
        String url;
        try {
            url = fileService.getPresignedUrl(fileId).getUrl();
        } catch (Exception ex) {
            log.warn("[gz-jp-order] 商品图片解析失败 fileId={}，回退占位图：{}", fileId, ex.getMessage());
            url = PLACEHOLDER_IMAGE_URL;
        }
        if (StrUtil.isBlank(url)) {
            url = PLACEHOLDER_IMAGE_URL;
        }
        urlCache.put(fileId, url);
        return url;
    }

    // ============================================================
    //  internal
    // ============================================================

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            // 快照写不出来 = 订单不完整，宁可整单失败也不要落一条没有快照的订单
            throw new ServiceException("下单快照序列化失败: " + e.getOriginalMessage());
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            // 读快照失败只影响展示，不该让订单详情整个 500（历史脏数据兜底）
            log.warn("[gz-jp-order] 快照反序列化失败 type={}：{}", type.getSimpleName(), e.getOriginalMessage());
            return null;
        }
    }

    /** ruoyi {@code BaseEntity.createTime} 是 {@code java.util.Date}，VO 统一用 LocalDateTime。 */
    private static LocalDateTime toLocalDateTime(java.util.Date date) {
        return date == null ? null
            : date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 分 → 元（只用于错误文案，不参与计算）。 */
    private static String yuan(long cent) {
        return String.format("%.2f", cent / 100.0);
    }
}
