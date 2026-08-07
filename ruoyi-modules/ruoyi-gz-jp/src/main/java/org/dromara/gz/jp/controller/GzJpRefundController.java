package org.dromara.gz.jp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.jp.domain.bo.GzJpRefundQueryBo;
import org.dromara.gz.jp.domain.vo.GzJpRefundAdminVO;
import org.dromara.gz.jp.service.IGzJpRefundService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-JP-107 拼团行级退款单管理（admin 端）。
 *
 * <p><b>这两个端点存在的理由就是 AC 的最后一条</b>：「退款失败要有明确落库状态 + <b>admin 可见</b>，
 * 不静默吞」。没有列表页，{@code refund_failed} 就只是一行谁也看不到的数据；
 * 没有重试按钮，那笔钱就只能靠改库退出去。</p>
 *
 * <p>路径前缀 {@code /system/gz/jp/refund}。<b>发起退款不在这里</b> ——
 * 那是履约看板的动作（{@code POST /system/gz/jp/fulfill/mark-failed}）。</p>
 *
 * <p><b>权限</b>（菜单与按钮 seed 归 GZ-JP-108，menu_id 14030 段，<b>本卡不占号</b>）：</p>
 * <ul>
 *   <li>{@code gz:jp:refund:list} — 退款单列表</li>
 *   <li>{@code gz:jp:refund:retry} — 重新发起退款（会真的再调一次微信）</li>
 * </ul>
 * <p>108 seed 菜单前，超管（admin）可直接调用；普通角色 403 —— 这是预期不是 bug。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/jp/refund")
public class GzJpRefundController extends BaseController {

    private final IGzJpRefundService refundService;

    /**
     * 退款单列表（★ 失败的单恒排最前，前端别按时间倒序覆盖这个排序）。
     *
     * <pre>
     * GET /system/gz/jp/refund/list?pageNum=1&amp;pageSize=20
     *     &amp;status=refund_failed&amp;orderNo=JPO-20260807-000001
     *     &amp;keyword=小王&amp;userId=13&amp;beginDate=2026-08-01&amp;endDate=2026-08-07
     * </pre>
     *
     * <p>读 {@code res.rows}（TableDataInfo）不是 {@code res.data}。</p>
     */
    @SaCheckPermission("gz:jp:refund:list")
    @GetMapping("/list")
    public TableDataInfo<GzJpRefundAdminVO> list(GzJpRefundQueryBo query, PageQuery pageQuery) {
        return refundService.selectAdminPage(query, pageQuery);
    }

    /**
     * 重新发起一笔退款（admin 人工确认失败原因后）。
     *
     * <pre>POST /system/gz/jp/refund/{refundId}/retry</pre>
     *
     * <p><b>★ 复用同一个 {@code refund_no}</b> —— 微信按 {@code out_refund_no} 幂等，
     * 已成功的会原样返回，不会退第二次。</p>
     *
     * <p><b>放行条件</b>（后端算好，列表里的 {@code retryable} 字段直接用）：
     * {@code refund_failed} 随时可重试；{@code refunding} 只有超过 10 分钟仍无回调才放行
     * （覆盖「事务提交后进程挂了、根本没提交给微信」这个崩溃窗口）；
     * {@code refunded} <b>永不放行</b>（钱已经退了）。</p>
     *
     * @return 重试后的退款单快照
     * @throws org.dromara.common.core.exception.ServiceException 4114 不存在 / 4115 当前状态不允许
     */
    @SaCheckPermission("gz:jp:refund:retry")
    @Log(title = "拼团重新发起退款", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{refundId}/retry")
    public R<GzJpRefundAdminVO> retry(@PathVariable Long refundId) {
        return R.ok("已重新发起", refundService.retry(refundId, LoginHelper.getUsername()));
    }
}
