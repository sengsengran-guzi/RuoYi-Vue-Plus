package org.dromara.gz.coupon.service;

import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;
import org.dromara.gz.coupon.domain.bo.GzCouponIssueBo;
import org.dromara.gz.coupon.domain.vo.GzCouponIssueResultVO;

import java.util.List;

/**
 * 优惠券发放编排服务（GZ-COUPON-001 AC 4/5/6 + ADR-0010 条件筛选）。
 *
 * <p>按模板 {@code issue_strategy} 路由到 {@link org.dromara.gz.coupon.strategy.ICouponIssuanceStrategy}，
 * 事务内执行发放（乐观锁占配额 + 批量 INSERT unused 券）。admin 主动发放放行 manual / filtered 策略。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001 / ADR-0010)
 */
public interface IGzCouponIssuanceService {

    /**
     * admin 批量发放（manual / filtered 策略，AC 5/6 + ADR-0010）。
     *
     * <p>校验：① 模板存在 + active；② 策略为 manual / filtered（event 走监听器）；
     * ③ manual 名单非空（userIds 优先，否则按 userKeyword 解析）；filtered 由策略按 issue_config_json
     * 解析 audience。乐观锁防超发（配额耗尽拦截，AC 6）。</p>
     *
     * @param bo 发放请求（templateId + manual 的 userIds / userKeyword）
     * @return 发放结果（实际发放数 + 剩余配额）
     */
    GzCouponIssueResultVO issue(GzCouponIssueBo bo);

    /**
     * 条件筛选「预览命中人数」（ADR-0010）：与 filtered 发放共用 audience 解析器（预览口径 = 实发口径）。
     *
     * @param conditions 条件列表（AND，≥1；非法即抛）
     * @return 命中用户数
     */
    long previewAudience(List<CouponAudienceConditionDto> conditions);

    /**
     * 自动发放扫描结果（GZ-COUPON-003，SnailJob 回传执行器记日志）。
     *
     * @param templatesScanned 本次扫到的待自动发放模板数（active + auto_issue=1 + filtered）
     * @param issued           本次累计实际发放张数（已去重已持券用户）
     */
    record AutoIssueResult(int templatesScanned, int issued) {
    }

    /**
     * 自动发放批量扫描（GZ-COUPON-003，SnailJob gzCouponAutoIssueTask）。
     *
     * <p>{@code TenantHelper.ignore} 全租户扫 {@code status='active' AND auto_issue=1 AND issue_strategy='filtered'}
     * 的模板。每模板独立 try/catch 隔离（配额满 / 条件解析异常不拖垮整批）：按 issue_config_json 解析 audience，
     * <b>减去已持本模板券的用户</b>（{@link org.dromara.gz.coupon.mapper.GzUserCouponMapper#selectHolderUserIds}
     * 去重，一人一模板一次），剩余新增用户走配额乐观锁 + 批量 INSERT，成功后写 last_auto_issue_time。</p>
     *
     * @return 扫描模板数 + 累计发放张数
     */
    AutoIssueResult autoIssueBatch();

    /**
     * 单模板「立即试跑」自动发放（GZ-COUPON-003，admin 验证用 / 复用 autoIssueBatch 单模板分支）。
     *
     * <p>校验：模板存在 + active + filtered + auto_issue=1（非法即抛）。逻辑与批量扫描的单模板分支一致：
     * 解析 audience → 减去已持券用户 → 发剩余 → 写 last_auto_issue_time。命中新用户为 0 时返回 0（非异常）。</p>
     *
     * @param templateId 模板主键
     * @return 本次实际发放张数
     */
    int autoIssueOnce(Long templateId);
}
