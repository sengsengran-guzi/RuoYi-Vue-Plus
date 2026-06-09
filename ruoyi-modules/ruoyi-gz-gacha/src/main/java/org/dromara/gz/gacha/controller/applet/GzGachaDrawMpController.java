package org.dromara.gz.gacha.controller.applet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.gacha.domain.vo.GachaStartDrawVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawHistoryVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawMachineFilterVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawStatusVo;
import org.dromara.gz.gacha.service.IGzGachaDrawService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-GACHA-104 mp 端扭蛋开盒 Controller（投币开盒）。
 *
 * <p>路径前缀 {@code /app/gz/gacha/draw}（mp {@code /app/} 域）。<b>需登录态</b>（{@link SaCheckLogin}）——
 * 开盒要 user_id 归属 + openid 拉起支付。</p>
 *
 * <p>端点：{@code POST /start}（machineId）— 付款前缺货拦截 → 调 PAY-101 建支付单 → 返 out_trade_no + mp 5 参。
 * 整机无货 → {@code R.fail(8009, MACHINE_EMPTY)}（不收款）；mp 据 code 提示「该扭蛋机暂时缺货」。</p>
 *
 * <p><b>盲盒语义</b>（README §B）：本端无用户可见文案（纯接口）；扭蛋 / 开盒文案在 mp i18n（GACHA-105 揭晓页）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Slf4j
@SaCheckLogin
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/gacha/draw")
public class GzGachaDrawMpController {

    private final IGzGachaDrawService gachaDrawService;

    /**
     * 投币开盒（AC2 + AC2.5）。
     *
     * <pre>
     * POST /app/gz/gacha/draw/start?machineId=1001
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "outTradeNo": "GACHA-20260617-000001",
     *     "payParams": { "timeStamp":"...","nonceStr":"...","packageVal":"prepay_id=...","signType":"RSA","paySign":"...","outTradeNo":"GACHA-..." }
     *   }
     * }
     * 整机无货：R.fail(8009, "该扭蛋机暂时缺货")  ← 不收款（付款前缺货拦截）
     * </pre>
     *
     * @param machineId 扭蛋机 id
     * @return out_trade_no + 微信 5 参签名（mp 唤起 wx.requestPayment；支付成功后开盒事务异步落 draw）
     */
    @Log(title = "扭蛋开盒", businessType = BusinessType.INSERT)
    @PostMapping("/start")
    public R<GachaStartDrawVo> start(@RequestParam Long machineId) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(gachaDrawService.startDraw(machineId, userId));
    }

    /**
     * 揭晓状态轮询（GZ-GACHA-105 AC1，仅查不改库）。
     *
     * <p>mp 支付成功后用 start 返回的 out_trade_no（或 drawNo）轮询本端点，驱动三段分镜揭晓动画。</p>
     *
     * <pre>
     * GET /app/gz/gacha/draw/status?outTradeNo=GACHA-20260617-000001
     * 200 OK（开盒中）
     * { "code":200, "data": { "status":"drawing" } }
     * 200 OK（已出奖）
     * {
     *   "code":200,
     *   "data": {
     *     "status":"drawn",
     *     "drawNo":"DRW-20260617-000001",
     *     "machineId":"1001",
     *     "prize": { "name":"...", "imageUrl":"https://...", "rarity":"SSR", "referenceValueCent":12900 }
     *   }
     * }
     * 非本人：R.fail(8011, "无权查看该开盒记录")
     * </pre>
     *
     * <p>付款必出 1 件，事务只会 drawing→drawn，<b>无 refunded 揭晓态</b>（扭蛋域无系统退款）。</p>
     *
     * @param outTradeNo 支付订单号（= start 返回 outTradeNo，与 drawNo 二选一，优先此参）
     * @param drawNo     开盒业务码 DRW-yyyyMMdd-6位（outTradeNo 为空时用）
     * @return 揭晓状态（drawing / drawn + 揭晓数据）
     */
    @GetMapping("/status")
    public R<GzGachaDrawStatusVo> getStatus(@RequestParam(required = false) String outTradeNo,
                                            @RequestParam(required = false) String drawNo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(gachaDrawService.getStatusForMp(outTradeNo, drawNo, userId));
    }

    /**
     * 我开过的 — 开盒历史分页（GZ-GACHA-106 AC1/AC2，doc/12 §MP-GACHA-HISTORY）。
     *
     * <pre>
     * GET /app/gz/gacha/draw/my?machineId=&pageNum=1&pageSize=20
     * 200 OK { "code":200, "rows":[ {
     *   "drawId":"9001","drawNo":"DRW-20260617-000001",
     *   "machineId":"1001","machineName":"初音未来 Q版盲盒机",
     *   "prizeName":"初音未来 应援款","rarity":"SSR",
     *   "prizeImageUrl":"https://...","referenceValueCent":12900,
     *   "drawnTime":"2026-06-17T20:31:05","businessStatus":"pending_ship"
     * } ], "total": 37 }
     * </pre>
     *
     * <p>sa-token 取当前 user_id（同租户 1001），仅查本人；按 drawn_time DESC 分页（索引
     * {@code (tenant_id, user_id, drawn_time DESC)}）。该表每行即一次成功开盒（doc/10 §8.N6），历史天然全部成功、
     * 无退款记录。分页响应直返 {@link TableDataInfo}（rows + total，mp http 拦截器识别）。</p>
     *
     * <p><b>盲盒语义</b>（README §B）：本端无用户可见文案（纯接口）；UI 文案在 mp i18n（{@code gacha.history.*}）。</p>
     *
     * @param machineId 机器筛选（null = 「全部」不过滤）
     * @param pageQuery pageNum / pageSize
     * @return 历史列表卡分页
     */
    @GetMapping("/my")
    public TableDataInfo<GzGachaDrawHistoryVo> myHistory(@RequestParam(required = false) Long machineId,
                                                         PageQuery pageQuery) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return TableDataInfo.build();
        }
        return gachaDrawService.listMyHistory(userId, machineId, pageQuery);
    }

    /**
     * 我开过的 — 机器筛选 chip 列表（GZ-GACHA-106 AC3，本人历史 distinct machine）。
     *
     * <pre>
     * GET /app/gz/gacha/draw/my/machines
     * 200 OK { "code":200, "data":[ {"machineId":"1001","machineName":"初音未来 Q版盲盒机"},
     *                               {"machineId":"1002","machineName":"咒术回战 立牌机"} ] }
     * </pre>
     *
     * <p>来源 = 本人 draw 的 distinct machine（不全量拉 {@code gz_gacha_machine}，决策 D3）；按最近开盒时间 DESC。
     * 无开盒历史 → 空 list（前端仅显「全部」chip）。</p>
     *
     * @return [{machineId, machineName}]
     */
    @GetMapping("/my/machines")
    public R<List<GzGachaDrawMachineFilterVo>> myHistoryMachines() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(gachaDrawService.listMyHistoryMachines(userId));
    }
}
