package org.dromara.gz.common.pay.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.service.IGzPayShippingService;
import org.dromara.gz.common.pay.service.IGzPayShippingService.UploadStats;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ExpireResult;
import org.dromara.gz.common.pay.service.IPayOrderQueryService;
import org.dromara.gz.common.pay.service.IPayOrderQueryService.ReconcileResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付域本地补偿定时任务（路 C —— 不依赖 SnailJob 服务端，单机部署用 Spring {@code @Scheduled} 直接跑）。
 *
 * <p><b>背景</b>：生产/预发未部署 SnailJob 服务端（{@code SNAIL_JOB_ENABLED=false}），所有
 * {@code @JobExecutor} 触发壳（{@link PayOrderQueryJobExecutor} / {@link GzShippingUploadJob} /
 * {@link GzPayExpireOrderJob}）从不触发。本类把其中<b>影响资金正确性</b>的三个补偿任务用 Spring 调度直接跑：
 * 支付主动查单（补丢失回调）、发货信息上传兜底重试、支付单超时关闭。低频对账 / 看板快照 / 券 / 资讯等非资金
 * 任务不在此列（低频、且 admin 已有手动入口）。</p>
 *
 * <p><b>开关</b>：{@code gz.local-scheduler.enabled}（prod 默认 true，dev/test 缺省即 false）。日后若部署
 * SnailJob 集群（路 A）→ 注入 {@code GZ_LOCAL_SCHEDULER_ENABLED=false} 关掉本地版，避免与控制台 cron 双跑。</p>
 *
 * <p><b>只是触发壳</b>：核心逻辑全在各 service（幂等 + 单条异常隔离 + TenantHelper.ignore 全租户扫），与
 * {@code @JobExecutor} 壳调的是同一批方法，单测直接测 service，无需 SnailJob。每轮吞异常只记日志，绝不让调度线程挂。</p>
 *
 * @author kevin-coder (sensenran-guzi · 本地补偿调度)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gz.local-scheduler", name = "enabled", havingValue = "true")
public class GzLocalPayScheduler {

    private final IPayOrderQueryService payOrderQueryService;
    private final IGzPayShippingService shippingService;
    private final IGzPayTransactionService transactionService;

    /**
     * 支付主动查单补偿（对齐 {@link PayOrderQueryJobExecutor}）：扫 processing/paying 单 → 微信查单 → 推进终态，
     * 补回「用户已付款但回调丢失」的单。默认每 90s（可 {@code gz.local-scheduler.pay-order-query-ms} 调）。
     */
    @Scheduled(fixedDelayString = "${gz.local-scheduler.pay-order-query-ms:90000}",
        initialDelayString = "${gz.local-scheduler.initial-delay-ms:60000}")
    public void payOrderQuery() {
        try {
            ReconcileResult r = payOrderQueryService.scanAndReconcile();
            if (r.scanned() > 0) {
                log.info("[gz-local-sched] pay-order-query scanned={} paid={} closed={} failed={} timeout={} pending={} skipped={}",
                    r.scanned(), r.paid(), r.closed(), r.failed(), r.timeout(), r.pending(), r.skipped());
            }
        } catch (Exception ex) {
            log.error("[gz-local-sched] pay-order-query FAILED", ex);
        }
    }

    /**
     * 发货信息上传兜底重试（对齐 {@link GzShippingUploadJob}）：扫历史上传失败单重报，防「发货未上传」影响微信资金结算
     * （拼豆虚拟商品 type3 强制）。默认每 5min（可 {@code gz.local-scheduler.shipping-upload-ms} 调）。
     */
    @Scheduled(fixedDelayString = "${gz.local-scheduler.shipping-upload-ms:300000}",
        initialDelayString = "${gz.local-scheduler.initial-delay-ms:60000}")
    public void shippingUpload() {
        try {
            UploadStats s = shippingService.uploadPending();
            if (s.scanned() > 0) {
                log.info("[gz-local-sched] shipping-upload scanned={} success={} failed={}",
                    s.scanned(), s.success(), s.failed());
            }
        } catch (Exception ex) {
            log.error("[gz-local-sched] shipping-upload FAILED", ex);
        }
    }

    /**
     * 支付单超时关闭（对齐 {@link GzPayExpireOrderJob}）：扫超时未付支付单 → 关单，收敛悬挂 paying。
     * 默认每 5min（可 {@code gz.local-scheduler.pay-expire-ms} 调）。
     */
    @Scheduled(fixedDelayString = "${gz.local-scheduler.pay-expire-ms:300000}",
        initialDelayString = "${gz.local-scheduler.initial-delay-ms:60000}")
    public void payExpire() {
        try {
            ExpireResult r = transactionService.expireTimeoutOrders();
            if (r.scanned() > 0) {
                log.info("[gz-local-sched] pay-expire scanned={} closed={} skipped={}",
                    r.scanned(), r.closed(), r.skipped());
            }
        } catch (Exception ex) {
            log.error("[gz-local-sched] pay-expire FAILED", ex);
        }
    }
}
