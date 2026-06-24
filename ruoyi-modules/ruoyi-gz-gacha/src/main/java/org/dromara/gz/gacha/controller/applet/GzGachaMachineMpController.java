package org.dromara.gz.gacha.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineDetailVo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineMpVo;
import org.dromara.gz.gacha.service.IGzGachaMachineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-GACHA-102 mp 端扭蛋机列表 Controller（C 端浏览）。
 *
 * <p>路径前缀 {@code /app/gz/gacha/machine}（mp {@code /app/} 与 admin {@code /system/gz/gacha/machine}
 * 域隔离；CLAUDE.md §6 mp-client 拦截）。</p>
 *
 * <p><b>匿名可读</b>（{@link SaIgnore}）：doc/10 §8.N1「U 进扭蛋机列表」，浏览不要求登录态（同 gz-ord
 * 预购列表 / gz-news 资讯）。投币开盒（GACHA-104 {@code /app/gz/gacha/draw/start}）才需登录，在对应端点
 * 单独挂 {@code @SaCheckLogin}。不挂 {@code @SaCheckPermission} —— C 端浏览无需后台权限。</p>
 *
 * <p><b>盲盒语义</b>（README §B）：本端无用户可见文案（纯数据接口）；扭蛋 / 库存提示语义在 mp i18n 渲染。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-102)
 */
@Slf4j
@SaIgnore
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/gacha/machine")
public class GzGachaMachineMpController {

    private final IGzGachaMachineService machineService;

    /**
     * 在售扭蛋机列表（GZ-GACHA-102 AC1）。
     *
     * <pre>
     * GET /app/gz/gacha/machine/list?pageNum=1&pageSize=20
     *   仅返回 status='on_shelf' + del_flag=0 的机器（排除 off_shelf / auto_off）
     *   排序 online_time DESC, id DESC（doc/11 §7.1 无 sort_order）
     *
     * 200 OK（http 拦截器识别 TableDataInfo rows/total）
     * {
     *   "code": 200,
     *   "rows": [
     *     { "id":"1001","name":"...","coverImageUrl":"https://...","singlePriceCent":1000,
     *       "tenPackPriceCent":9000,"ipTag":"...","stockRemainSum":42 }
     *   ],
     *   "total": 3
     * }
     * </pre>
     *
     * @param pageQuery 分页参数（pageNum / pageSize，ruoyi 自动绑定 query）
     * @return TableDataInfo&lt;GzGachaMachineMpVo&gt;（rows + total）
     */
    @GetMapping("/list")
    public TableDataInfo<GzGachaMachineMpVo> list(PageQuery pageQuery) {
        return machineService.listOnShelfForMp(pageQuery);
    }

    /**
     * 单机详情 — 产品列表（GZ-GACHA-103 AC1，doc/10 §8.N2，ADR-0013 去概率）。
     *
     * <pre>
     * GET /app/gz/gacha/machine/{id}/detail
     *   返回机器主体 + 该机器全部奖品（含售罄 / disabled，决策 D2 不后端过滤）。
     *   <b>ADR-0013（甲方拍板）：不展示任何概率百分比</b> —— 奖品名/图/参考价取产品库（join），稀有度取投放线。
     *   不限机器 status（详情可看非在售机器）；机器不存在 → R.fail(MACHINE_NOT_FOUND)。匿名可读（@SaIgnore）。
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "id":"1001","name":"...","coverImageUrl":"https://...","singlePriceCent":1000,
     *     "tenPackPriceCent":9000,"status":"on_shelf","stockRemainSum":42,
     *     "prizes":[
     *       { "id":"2001","name":"SSR 限定","imageUrl":"https://...","rarity":"SSR",
     *         "stockRemain":5,"referenceValueCent":29900,"enabled":1 },
     *       { "id":"2002","name":"已抽完款","rarity":"R","stockRemain":0,"enabled":1 }
     *     ]
     *   }
     * }
     * </pre>
     *
     * <p>路由 {@code id} 是 string 机器主键（跨层契约 #1，非 machine_no）；Spring 自动 parse Long。</p>
     *
     * @param id 机器主键（路由 path）
     * @return 机器详情 + 产品列表（无概率字段）
     */
    @GetMapping("/{id}/detail")
    public R<GzGachaMachineDetailVo> getDetail(@PathVariable("id") Long id) {
        return R.ok(machineService.getDetailForMp(id));
    }
}
