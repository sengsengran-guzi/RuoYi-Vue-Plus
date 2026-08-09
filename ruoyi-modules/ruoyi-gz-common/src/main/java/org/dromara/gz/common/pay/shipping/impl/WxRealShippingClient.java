package org.dromara.gz.common.pay.shipping.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.shipping.ShippingInfo;
import org.dromara.gz.common.pay.shipping.ShippingPackage;
import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.dromara.gz.common.wechat.WxAccessTokenManager;
import org.dromara.gz.common.wechat.WxAdapterDispatcher;
import org.dromara.gz.common.wechat.WxDeliveryListResolver;
import org.dromara.gz.common.wechat.impl.WxRealAccessTokenManager;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 微信发货信息录入 real 实现（{@code upload_shipping_info}）。
 *
 * <p><b>装配</b>（ADR-0019 §1）：无条件注册，与 {@link WxMockShippingClient} 同时在容器里，由
 * {@link WxAdapterDispatcher} 按<b>发货任务归属的</b> clientid 对应小程序的 mode 运行时选择。调用链：
 * access_token（{@link WxRealAccessTokenManager} Redis 缓存，按 appid 分槽）→ POST upload_shipping_info。</p>
 *
 * <p><b>依赖具体的 {@link WxRealAccessTokenManager} 而非 {@link WxAccessTokenManager} 接口</b>：
 * 接口的 {@code @Primary} 实现是 dispatcher，注入它会构成 dispatcher → 本类 → dispatcher 的构造器
 * 循环依赖（Spring Boot 3 默认禁止）。</p>
 *
 * <p><b>⚠️ 上报是 {@code @Async} 执行（无请求上下文）</b>：故 token / 通道一律按
 * {@link UploadCommand#clientId()}（发货任务落库时记下的归属小程序）取，为空才回落
 * {@code wx.miniapp.default-client-id}。<b>不能靠当前请求推断</b> —— 多小程序下那等于拿 A 的 token
 * 报 B 的订单，微信恒回 {@code 10060001}「支付单不存在」，重试永远好不了。</p>
 *
 * <p><b>两种商品形态</b>（微信文档口径，见类内常量注释）：</p>
 * <ul>
 *   <li><b>虚拟商品</b>（{@code logistics_type=3}，拼豆预约）：{@code delivery_mode=1} 统一发货，
 *       {@code shipping_list} 只有一个 {@code item_desc}，<b>不带</b> {@code is_all_delivered}。
 *       —— 线上在跑的路径，请求体与多包裹改造前逐字一致（有回归单测钉死）。</li>
 *   <li><b>实物商品</b>（{@code logistics_type=1}，拼团代购）：{@code delivery_mode=2} 分拆发货，
 *       {@code shipping_list} 带该支付单<b>截至目前的全部包裹</b>（每个含 {@code tracking_no} /
 *       {@code express_company} / {@code item_desc} / {@code contact.receiver_contact}），
 *       并用 {@code is_all_delivered} 在最后一批发出时收口。</li>
 * </ul>
 *
 * <p><b>为什么每次带全量包裹而不是只带新增的那个</b>：微信文档<b>没有</b>写明多次上报是覆盖还是追加合并
 * （只有社区旧帖说合并，且其包裹上限与现行文档的 15 不符，不可采信）。带全量在两种语义下结果都对。</p>
 *
 * <p><b>容错</b>：本类不抛异常 —— 网络/微信错误落 {@link UploadResult#fail}；access_token 失效
 * （40001/42001/40014）强刷一次重试。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxRealShippingClient implements WxShippingClient {

    /** 发货信息录入接口。 */
    private static final String UPLOAD_URL = "https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    /** item_desc 最大长度（微信限制 120 字，留余量截断）。 */
    private static final int ITEM_DESC_MAX_LEN = 120;

    /** order_number_type=2 → 用微信支付单号 transaction_id 定位订单。 */
    private static final int ORDER_NUMBER_TYPE_TRANSACTION_ID = 2;

    /** access_token 失效错误码 → 强刷重试。 */
    private static final int ERR_TOKEN_INVALID = 40001;
    private static final int ERR_TOKEN_EXPIRED = 42001;
    private static final int ERR_TOKEN_BAD = 40014;

    /** 「订单已发货」幂等错误码（微信重复发货保护）。 */
    private static final int ERR_ALREADY_SHIPPED = 268440065;

    /**
     * 「已上传该订单的物流信息」幂等错误码（errmsg 为「发货信息未更新。支付单信息不变」，措辞误导）。
     * 内容与上次完全一致再报一次即返此码 —— 视为成功，避免历史卡单永卡 failed。
     */
    private static final int ERR_SHIPPING_NOT_UPDATED = 10060023;

    /** 「支付单不存在」—— 支付后微信订单索引尚未就绪的时序竞态，可重试（非终态失败）。 */
    private static final int ERR_ORDER_NOT_READY = 10060001;

    /**
     * 「支付单已完成发货，无法继续发货」—— <b>终态</b>。
     *
     * <p>微信口径：对已完成发货（{@code is_all_delivered=true}）的支付单再调本接口视为<b>重新发货</b>，
     * 每笔单<b>仅有一次</b>重新发货机会。继续重试只会把这次机会烧掉然后撞 {@link #ERR_RESHIP_USED}，
     * 所以这里必须停下来留人工。</p>
     */
    private static final int ERR_ALREADY_FINISHED = 10060002;

    /** 「支付单已使用重新发货机会」—— <b>终态</b>，再报永远失败。 */
    private static final int ERR_RESHIP_USED = 10060003;

    /** 本地前置校验失败（未触网）：实体物流缺运单号 / 快递编码 / 收件人联系方式。自定义码，非微信码。 */
    static final int ERR_LOCAL_INVALID_PARAM = -2;

    private final WxRealAccessTokenManager accessTokenManager;
    private final WxDeliveryListResolver deliveryListResolver;

    @Override
    public UploadResult uploadShippingInfo(UploadCommand cmd) {
        // ★ 实物件先把「本项目快递中文名 → 微信运力 id」解析补齐（缓存命中时零网络开销），
        //   再做本地校验：缺什么写在 last_error 里，admin 才知道该补运单号还是换快递公司。
        resolveExpressCompanies(cmd);
        UploadResult invalid = validate(cmd);
        if (invalid != null) {
            log.warn("[wx-shipping] 上报参数不合法，未调用微信 transaction_id={} reason={}",
                cmd.transactionId(), invalid.errmsg());
            return invalid;
        }
        JSONObject resp = post(cmd, accessTokenManager.getTokenFor(cmd.clientId(), false));
        Integer errcode = resp.getInt("errcode");
        // token 失效 → 强刷一次重试
        if (errcode != null && (errcode == ERR_TOKEN_INVALID || errcode == ERR_TOKEN_EXPIRED || errcode == ERR_TOKEN_BAD)) {
            log.warn("[wx-shipping] access_token 失效 errcode={}，强刷重试 transaction_id={}", errcode, cmd.transactionId());
            resp = post(cmd, accessTokenManager.getTokenFor(cmd.clientId(), true));
            errcode = resp.getInt("errcode");
        }
        String errmsg = resp.getStr("errmsg");
        UploadResult result = interpret(errcode, errmsg);
        if (result.success()) {
            log.info("[wx-shipping] 发货信息上报成功（含幂等）transaction_id={} packages={} allDelivered={} errcode={}",
                cmd.transactionId(), cmd.packages().size(), cmd.allDelivered(), errcode);
        } else if (result.terminal()) {
            log.error("[wx-shipping] ★ 微信侧已终态、停止重试 transaction_id={} errcode={} errmsg={} —— "
                + "该单已完成发货或已用掉唯一一次重新发货机会，需人工在小程序后台核对", cmd.transactionId(), errcode, errmsg);
        } else if (errcode != null && errcode == ERR_ORDER_NOT_READY) {
            log.warn("[wx-shipping] 微信订单索引未就绪，稍后重试 transaction_id={} errcode={}", cmd.transactionId(), errcode);
        } else {
            log.warn("[wx-shipping] 发货信息上报失败 transaction_id={} errcode={} errmsg={}",
                cmd.transactionId(), errcode, errmsg);
        }
        return result;
    }

    /**
     * 给还没有微信运力 id 的包裹按中文名反查补齐（就地写回 {@link ShippingPackage#setExpressCompany}）。
     *
     * <p>刻意<b>每次上报都解析</b>而不是落库存下来：运力表在 Redis 里缓存 24h，解析几乎零成本；
     * 而落库会让「首次拉运力表失败 → 存了个空编码 → 之后重试永远带着空编码」这种坏数据固化下来。</p>
     */
    private void resolveExpressCompanies(UploadCommand cmd) {
        if (!cmd.requiresTracking()) {
            return;
        }
        for (ShippingPackage pkg : cmd.packages()) {
            if (StrUtil.isBlank(pkg.getExpressCompany())) {
                pkg.setExpressCompany(deliveryListResolver.resolveByName(pkg.getCarrierName(), cmd.clientId()));
            }
        }
    }

    /**
     * 上报前的本地校验（虚拟商品无要求）。抽成纯函数便于单测（不触网）。
     *
     * @param cmd 上报指令
     * @return null = 参数合法；否则为带人话原因的失败结果
     */
    UploadResult validate(UploadCommand cmd) {
        if (!cmd.requiresTracking()) {
            return null;
        }
        List<ShippingPackage> packages = cmd.packages();
        if (packages == null || packages.isEmpty()) {
            return UploadResult.fail(ERR_LOCAL_INVALID_PARAM, "实体物流上报没有任何包裹（shipping_list 为空）");
        }
        // 微信硬上限：一笔支付单最多 15 个包裹（10060024）。本地先拦，别浪费一次调用
        if (packages.size() > ShippingInfo.MAX_PACKAGES) {
            return UploadResult.fail(ERR_LOCAL_INVALID_PARAM,
                "包裹数 " + packages.size() + " 超过微信上限 " + ShippingInfo.MAX_PACKAGES + "，请人工合并运单");
        }
        for (ShippingPackage pkg : packages) {
            if (StrUtil.isBlank(pkg.getTrackingNo())) {
                return UploadResult.fail(ERR_LOCAL_INVALID_PARAM, "实体物流上报缺运单号（tracking_no）");
            }
            if (StrUtil.isBlank(pkg.getExpressCompany())) {
                return UploadResult.fail(ERR_LOCAL_INVALID_PARAM, StrUtil.format(
                    "运单 {} 的快递公司「{}」在微信运力表里查不到编码（express_company），"
                        + "请确认该快递公司在微信 get_delivery_list 中的名称，或改用其他快递公司重新发货",
                    pkg.getTrackingNo(), pkg.getCarrierName()));
            }
            if (StrUtil.isBlank(pkg.getReceiverContact())) {
                return UploadResult.fail(ERR_LOCAL_INVALID_PARAM,
                    "运单 " + pkg.getTrackingNo() + " 缺收件人联系方式（contact.receiver_contact，顺丰必填）");
            }
        }
        if (cmd.deliveryMode() == ShippingInfo.DELIVERY_MODE_UNIFIED && packages.size() != 1) {
            // 微信 268485228：统一发货模式下物流信息列表长度必须为 1
            return UploadResult.fail(ERR_LOCAL_INVALID_PARAM,
                "统一发货模式下只能有 1 个包裹，实际 " + packages.size() + " 个（应改用分拆发货）");
        }
        return null;
    }

    /**
     * 把微信响应错误码/描述归类为成功 / 可重试失败 / 终态失败。抽成纯函数便于单测（不触网）。
     *
     * <p>成功：{@code errcode==0} 或 null；{@code 268440065} / {@code 10060023}（微信侧已登记发货，幂等）；
     * errmsg 含「已发货」。<br>
     * 终态：{@code 10060002}「已完成发货」/ {@code 10060003}「已用掉重新发货机会」—— 重试有害必须停。<br>
     * 其余（含 {@code 10060001} 时序竞态）→ 可重试失败。</p>
     *
     * @param errcode 微信错误码（可能为 null = 成功）
     * @param errmsg  微信错误描述
     * @return 上报结果
     */
    UploadResult interpret(Integer errcode, String errmsg) {
        if (errcode == null || errcode == 0) {
            return UploadResult.ok();
        }
        if (errcode == ERR_ALREADY_SHIPPED || errcode == ERR_SHIPPING_NOT_UPDATED
            || (errmsg != null && (errmsg.contains("已发货") || errmsg.contains("已经发货")))) {
            return UploadResult.ok();
        }
        if (errcode == ERR_ALREADY_FINISHED || errcode == ERR_RESHIP_USED) {
            return UploadResult.terminalFail(errcode, errmsg);
        }
        return UploadResult.fail(errcode, errmsg);
    }

    /** 调 upload_shipping_info，返回原始响应 JSON（错误码留给调用方判定，便于 token 重试）。 */
    private JSONObject post(UploadCommand cmd, String accessToken) {
        String url = UPLOAD_URL + "?access_token=" + accessToken;
        String reqBody = buildBody(cmd).toString();
        String body;
        try {
            body = HttpUtil.createPost(url)
                .header("Content-Type", "application/json;charset=UTF-8")
                .body(reqBody)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();
        } catch (Exception e) {
            log.error("[wx-shipping] upload_shipping_info 网络异常 transaction_id={}", cmd.transactionId(), e);
            // 网络异常归一为可重试失败码（非微信码，自定义 -1）
            return JSONUtil.createObj().set("errcode", -1).set("errmsg", "网络异常: " + e.getMessage());
        }
        log.debug("[wx-shipping] upload_shipping_info resp={}", body);
        return JSONUtil.parseObj(body);
    }

    /**
     * 构造 {@code upload_shipping_info} 请求体。
     *
     * <p><b>虚拟商品</b>：{@code shipping_list} 仅 {@code item_desc}，{@code delivery_mode=1}，
     * <b>不带</b> {@code is_all_delivered} —— 与多包裹改造前逐字一致。</p>
     *
     * <p><b>实物商品</b>：{@code shipping_list} 是该支付单的<b>累计</b>包裹清单；
     * {@code delivery_mode=2} 时 {@code is_all_delivered} 必填（{@code 10060007}），
     * 它是微信判断「这单还有没有货在路上」的唯一依据，漏了或永远传 false 订单会一直停在部分发货。</p>
     *
     * <p><b>{@code delivery_mode} / {@code logistics_type} 都是 number 不是字符串</b>：
     * 文档里 {@code UNIFIED_DELIVERY} / {@code SPLIT_DELIVERY} 只是枚举的可读名称，
     * 传字符串会撞 {@code 268485224}「发货模式非法」。</p>
     *
     * <p>包级可见以便单测直接断言请求体（同 {@link #interpret}），不要收窄成 private。</p>
     *
     * @param cmd 上报指令
     * @return 请求体 JSON
     */
    JSONObject buildBody(UploadCommand cmd) {
        JSONObject orderKey = JSONUtil.createObj()
            .set("order_number_type", ORDER_NUMBER_TYPE_TRANSACTION_ID)
            .set("transaction_id", cmd.transactionId());

        JSONArray shippingList = new JSONArray();
        if (cmd.requiresTracking()) {
            for (ShippingPackage pkg : cmd.packages()) {
                shippingList.add(JSONUtil.createObj()
                    .set("tracking_no", pkg.getTrackingNo())
                    .set("express_company", pkg.getExpressCompany())
                    .set("item_desc", truncate(pkg.getItemDesc()))
                    // contact：微信仅「顺丰必填」，收件人 / 寄件人二选一。统一填收件人掩码号，少一个分支少一类事故
                    .set("contact", JSONUtil.createObj().set("receiver_contact", pkg.getReceiverContact())));
            }
        } else {
            shippingList.add(JSONUtil.createObj().set("item_desc", truncate(cmd.itemDesc())));
        }

        String uploadTime = OffsetDateTime.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        JSONObject body = JSONUtil.createObj()
            .set("order_key", orderKey)
            .set("logistics_type", cmd.logisticsType())
            .set("delivery_mode", cmd.deliveryMode());
        if (cmd.deliveryMode() == ShippingInfo.DELIVERY_MODE_SPLIT) {
            // null 视为 false（还有货在路上）——「还没发完」是安全的默认：错报 true 会让微信提前收口，
            // 之后的包裹只能动用唯一一次「重新发货」机会
            body.set("is_all_delivered", Boolean.TRUE.equals(cmd.allDelivered()));
        }
        return body
            .set("shipping_list", shippingList)
            .set("upload_time", uploadTime)
            .set("payer", JSONUtil.createObj().set("openid", cmd.openid()));
    }

    /** item_desc 截断到微信上限（超长报 10060009）。 */
    private String truncate(String desc) {
        String v = desc == null ? "" : desc;
        return v.length() > ITEM_DESC_MAX_LEN ? v.substring(0, ITEM_DESC_MAX_LEN) : v;
    }
}
