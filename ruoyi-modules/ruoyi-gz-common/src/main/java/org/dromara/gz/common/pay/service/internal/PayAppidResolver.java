package org.dromara.gz.common.pay.service.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.entity.GzPayChannel;
import org.dromara.gz.common.pay.mapper.GzPayChannelMapper;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 「这一单该用哪个小程序的 appid」解析器（ADR-0019 §3，GZ-SYS-022）。
 *
 * <p>JSAPI 的 {@code prepay_id} 与 appid 绑定，调起支付 5 参签名的第一个因子也是 appid。商户号
 * （{@code mch_id}）两个小程序共用，<b>appid 不可共用</b>。本类是统一下单（{@code createJsapiOrder}）
 * 与调起签名（{@code buildPayParams}）唯一的 appid 来源，取代改造前写死的全局 {@code gz.pay.appid}。</p>
 *
 * <h3>解析优先级（越靠前越权威）</h3>
 * <ol>
 *   <li><b>{@code gz_pay_channel.appid}</b>（按 {@code client_id} 一行，ADR-0019 §3 指定真源）——
 *       该列建表即有、此前从未被读过，本卡起激活。用途是「某个小程序的收款 appid 需要与它的登录 appid
 *       不同」这类运维级覆盖，配了就赢。值为空 / 建表 seed 占位值时视同<b>没配</b>，继续往下走。</li>
 *   <li><b>{@code wx.miniapp.apps.<clientid>.appid}</b>（登录侧 appid，GZ-SYS-020 已 Map 化）——
 *       默认走这条。这样做不是图省事：{@code payer.openid} 是登录时用<b>这个 appid</b> 换来的，
 *       openid 是 appid 维度标识，两者必须同源，否则微信返「openid 与 appid 不匹配」。
 *       让默认值直接取自签发 openid 的那个 appid，同源是<b>结构上成立</b>的，而不是靠两处配置对齐来维持。</li>
 *   <li><b>{@code gz.pay.appid}</b>（改造前的全局标量）—— 纯兜底，保证任何历史配置形态下行为不劣化。</li>
 * </ol>
 *
 * <p><b>为什么 clientid 用宽松口径</b>（{@link WxAppResolver#currentAppOrDefault()} 而非 {@code currentApp()}）：
 * 下单不只来自小程序 —— {@code POST /system/gz/pay/test} 是 plus-ui 后台发起的通道测试单，它带的
 * {@code clientid} 是 PC 端客户端 id（{@code e5cd7e...}）而非小程序。用严格口径会让后台测试单直接抛
 * 「未配置的小程序客户端」（GZ-SYS-020 §6 已经因为同类错误打挂过后台发货补报按钮）。认不出 = 调用方不是
 * 小程序 → 落 {@code default-client-id}，即多 appid 改造前的行为。</p>
 *
 * <p><b>DB 查询失败绝不阻断下单</b>：第 ① 步是可选覆盖，不是新增的硬依赖。查询抛异常（迁移未跑 /
 * 表锁 / 租户上下文缺失）时只记 WARN 并降级到第 ② 步 —— 在一条正在跑的真实收款链路上，
 * 新引入的「可选增强」不允许成为新的失败点。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-022)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayAppidResolver {

    /**
     * 视同「未配置」的占位 appid：
     * {@code wx_placeholder_appid} = {@code gz_pay_channel} 建表 seed 值（V202606041200）；
     * {@code wx_dev_placeholder} = {@code application-dev.yml} 的 {@code gz.pay.appid} 默认值。
     */
    private static final Set<String> PLACEHOLDER_APPIDS = Set.of("wx_placeholder_appid", "wx_dev_placeholder");

    private final WxAppResolver appResolver;
    private final GzPayChannelMapper channelMapper;
    private final WechatPayProperties payProperties;

    /**
     * 解析当前下单上下文（当前请求 clientid）应使用的小程序 appid。
     *
     * <p>本方法<b>不</b>判断 appid 是否是「真实可收款」的值 —— mock 通道（{@code gz.pay.client-mode=mock}）
     * 下 {@code wxMOCK} 是完全正常的取值。真实性校验放在 {@code WechatPayV3ClientImpl}（real 通道）里做，
     * 那里才是「拿这个 appid 去打微信」的地方。</p>
     *
     * @return 小程序 appid
     * @throws ServiceException 三级都解析不出任何非空 appid（配置全空，属于部署事故）
     */
    public String resolveForCurrentApp() {
        String clientId = appResolver.currentClientId();

        // ① gz_pay_channel.appid（按 clientid 一行）—— 配了就赢
        String fromChannel = appidFromChannel(clientId);
        if (fromChannel != null) {
            log.debug("[gz-pay] appid 解析 clientid={} → {}（来源 gz_pay_channel）", clientId, fromChannel);
            return fromChannel;
        }

        // ② 登录侧 appid（与 payer.openid 同源，默认路径）
        String fromMiniapp = trimToNull(appResolver.currentAppOrDefault().getAppid());
        if (fromMiniapp != null) {
            log.debug("[gz-pay] appid 解析 clientid={} → {}（来源 wx.miniapp）", clientId, fromMiniapp);
            return fromMiniapp;
        }

        // ③ 改造前的全局标量 —— 纯兜底
        String legacy = trimToNull(payProperties.getAppid());
        if (legacy != null) {
            log.warn("[gz-pay] appid 解析 clientid={} 回落到全局 gz.pay.appid={}（gz_pay_channel 与 wx.miniapp 都没给出）",
                clientId, legacy);
            return legacy;
        }

        throw new ServiceException("无法确定本单使用的小程序 appid（clientid=" + clientId
            + "）：gz_pay_channel.appid / wx.miniapp.apps.<clientid>.appid / gz.pay.appid 均为空，请补配置（ADR-0019 §3）");
    }

    /**
     * 从 {@code gz_pay_channel} 取该 clientid 的 appid；没有该行 / 没配 / 是占位值 / 查询失败 → 返回 null（降级）。
     */
    private String appidFromChannel(String clientId) {
        if (trimToNull(clientId) == null) {
            return null;
        }
        try {
            List<GzPayChannel> rows = channelMapper.selectList(Wrappers.<GzPayChannel>lambdaQuery()
                .eq(GzPayChannel::getClientId, clientId)
                .eq(GzPayChannel::getEnabled, 1)
                .orderByAsc(GzPayChannel::getId));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            for (GzPayChannel row : rows) {
                String appid = trimToNull(row.getAppid());
                if (appid != null && !isPlaceholderAppid(appid)) {
                    return appid;
                }
            }
            return null;
        } catch (Exception e) {
            // 可选覆盖，不是硬依赖：查不了就当没配，绝不让下单失败（见类 javadoc）
            log.warn("[gz-pay] 读取 gz_pay_channel 失败（clientid={}），降级用 wx.miniapp 的 appid：{}",
                clientId, e.getMessage());
            return null;
        }
    }

    /** 是否是建表 / dev 配置里的占位 appid（视同未配置）。 */
    public static boolean isPlaceholderAppid(String appid) {
        return appid != null && PLACEHOLDER_APPIDS.contains(appid.trim());
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
