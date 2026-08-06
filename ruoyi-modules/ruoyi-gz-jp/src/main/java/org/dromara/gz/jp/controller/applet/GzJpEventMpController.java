package org.dromara.gz.jp.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.vo.GzJpEventMpVO;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-JP-101 拼团场（mp 端，FLOW:F-JP-02.step1 浏览进行中的场）。
 *
 * <p>路径 {@code /app/gz/jp/event}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p><b>匿名可读</b>（{@link SaIgnore}）：浏览场列表不需要登录态，与资讯一致；
 * 加购 / 下单（GZ-JP-201 / 103）才要求登录。</p>
 *
 * <p><b>可见性（AC3）</b>：只返回<b>生效状态 = open</b> 的场 —— 未开场（draft）、店员已关场（closed）、
 * 以及 end_time 已过（读时惰性判定为结束）三种情况一律查不到。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Slf4j
@SaIgnore
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/jp/event")
public class GzJpEventMpController {

    private final IGzJpEventService eventService;

    /**
     * 可下单场列表（分页）。
     *
     * <pre>
     * GET /app/gz/jp/event/list?pageNum=1&amp;pageSize=10
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "rows": [
     *     { "id":"3","eventNo":"EVT-20260807-000001","name":"8月上旬快闪",
     *       "coverImageUrl":"https://...","description":"...","status":"open",
     *       "startTime":"2026-08-07 10:00:00","endTime":"2026-08-14 22:00:00" }
     *   ],
     *   "total": 1
     * }
     * </pre>
     *
     * @param pageQuery 分页（pageNum / pageSize，ruoyi 自动绑定）
     * @return 场列表（无可下单场时 rows 为空数组，不是 fail）
     */
    @GetMapping("/list")
    public TableDataInfo<GzJpEventMpVO> list(PageQuery pageQuery) {
        return eventService.selectMpPage(pageQuery);
    }

    /**
     * 场详情（进场页头）。
     *
     * @param id 场主键
     * @return R&lt;detail&gt;；场不存在 / 未开场 / 已结束 → R.fail（mp 按 code != 200 走「该场已结束」）
     */
    @GetMapping("/{id}")
    public R<GzJpEventMpVO> detail(@NotNull @PathVariable Long id) {
        GzJpEventMpVO vo = eventService.selectMpDetail(id);
        if (vo == null) {
            return R.fail("该场不存在或已结束");
        }
        return R.ok(vo);
    }
}
