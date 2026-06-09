package org.dromara.gz.gacha.controller.applet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.gacha.domain.vo.GzGachaCollectionVo;
import org.dromara.gz.gacha.service.IGzGachaCollectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-GACHA-107 mp 端「我的图鉴」Controller。
 *
 * <p>路径前缀 {@code /app/gz/gacha/collection}（mp {@code /app/} 域）。<b>需登录态</b>（{@link SaCheckLogin}）——
 * 图鉴按 user_id 归属查询。纯顾客可调，无需 {@code @SaCheckPermission}（mp C 端只读接口）。</p>
 *
 * <p>端点：{@code GET /my?machineId=}（machineId 可空）— 返按机器分组的图鉴（每机器含 已得X/总Y +
 * 是否集齐 + 奖品全集格子）。数据源 {@code gz_user_gacha_collection}（强约束 #1，非 draw 聚合）。</p>
 *
 * <p><b>盲盒语义</b>（README §B）：本端无用户可见文案（纯接口）；UI 文案在 mp i18n（{@code gacha.collection.*}）
 * 用「图鉴 / 获得 / 开盒 / 已收集」，禁「抽奖 / 中奖 / 开奖」。</p>
 *
 * <p>关联文档：doc/10 §8 N9 / doc/11 §7.5 / doc/12 §MP-ME-COLLECTION / doc/tokens.md §1.4 / GZ-GACHA-107 AC1。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-107)
 */
@Slf4j
@SaCheckLogin
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/gacha/collection")
public class GzGachaCollectionMpController {

    private final IGzGachaCollectionService collectionService;

    /**
     * 我的图鉴（GZ-GACHA-107 AC1）。
     *
     * <pre>
     * GET /app/gz/gacha/collection/my?machineId=1001   （machineId 可空 → 全部已收集机器）
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "machines": [ {
     *       "machineId": "1001",
     *       "machineName": "初音未来 Q版盲盒机",
     *       "coverImageUrl": "https://...",
     *       "totalCount": 6,
     *       "ownedCount": 4,
     *       "isCompleteSet": false,
     *       "prizes": [ {
     *         "prizeId": "2001", "name": "初音 应援款", "imageUrl": "https://...",
     *         "rarity": "SSR", "owned": true, "drawnCount": 3, "firstDrawnTime": "2026-06-18T20:31:05"
     *       }, {
     *         "prizeId": "2002", "name": "限定盒", "imageUrl": "/static/images/mock-product.png",
     *         "rarity": "N", "owned": false, "drawnCount": 0, "firstDrawnTime": null
     *       } ]
     *     } ]
     *   }
     * }
     * </pre>
     *
     * <p>集齐进度（每机器 ownedCount/totalCount + isCompleteSet）随分组返回，前端「已收集 X/Y 件」直取。
     * 空 collection / machineId 未收集过 → {@code machines: []}。</p>
     *
     * @param machineId 机器筛选（null = 用户收集过的所有机器分组）
     * @return 按机器分组的图鉴（含集齐进度）
     */
    @GetMapping("/my")
    public R<GzGachaCollectionVo> myCollection(@RequestParam(required = false) Long machineId) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(collectionService.getMyCollection(userId, machineId));
    }
}
