package org.dromara.gz.common.pay.service;

import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayShippingOrder;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.mapper.GzPayShippingOrderMapper;
import org.dromara.gz.common.pay.service.IGzPayShippingService.UploadStats;
import org.dromara.gz.common.pay.service.impl.GzPayShippingServiceImpl;
import org.dromara.gz.common.pay.shipping.ShippingInfo;
import org.dromara.gz.common.pay.shipping.ShippingPackage;
import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadCommand;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzPayShippingServiceImpl} 单测（订单中心发货信息上报，全 mock 脱离 Spring / DB / 网络）。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>enqueue happy path — 落 pending 行（insert 调用 + 关键字段正确）</li>
 *   <li>uploadPending — 成功条置 success / 失败条置 failed + 统计正确</li>
 *   <li>uploadPending — 已 success 的行不重复上报（幂等跳过）</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzPayShippingServiceImplTest {

    @Mock
    private GzPayShippingOrderMapper shippingMapper;
    @Mock
    private WxShippingClient shippingClient;
    @Mock
    private ObjectProvider<IGzPayShippingService> selfProvider;

    private GzPayShippingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzPayShippingServiceImpl(shippingMapper, shippingClient, selfProvider);
        // 守卫回写默认命中（=期间内容没变过）；测「被并发改掉」时单独桩成 0
        lenient().when(shippingMapper.updateStatusGuarded(any(), any(), any(), any(), any(), any()))
            .thenReturn(1);
        // 行有 id 时 registerAfterCommitUpload 会 selfProvider.getObject().tryUploadAsync(id)；
        // 不桩的话拿到 null → NPE 被 service 的兜底 catch 吞掉，测试看到的是「返回 false」而不是真实行为
        lenient().when(selfProvider.getObject()).thenReturn(mock(IGzPayShippingService.class));
    }

    @Test
    @DisplayName("enqueue：落 pending 行，物流类型 + openid + transaction_id 正确")
    void enqueue_insertsPendingRow() {
        GzPayTransaction txn = GzPayTransaction.builder()
            .outTradeNo("PINDOU-20260704-000001")
            .businessType("pindou")
            .businessOrderNo("PD-20260704-0001")
            .openid("o_test_openid")
            .transactionId("4200001234202607041234")
            .paidTime(LocalDateTime.of(2026, 7, 4, 12, 0))
            .build();

        // mapper.insert 不回填 id（mock）→ id 为 null → registerAfterCommit 提前返回，不触 selfProvider
        service.enqueue(txn, ShippingInfo.virtual("谷子宇宙·拼豆预约 PD-20260704-0001"));

        ArgumentCaptor<GzPayShippingOrder> captor = ArgumentCaptor.forClass(GzPayShippingOrder.class);
        verify(shippingMapper).insert(captor.capture());
        GzPayShippingOrder row = captor.getValue();
        assertEquals("4200001234202607041234", row.getTransactionId());
        assertEquals("o_test_openid", row.getOpenid());
        assertEquals(ShippingInfo.VIRTUAL, row.getLogisticsType());
        assertEquals(GzPayShippingOrder.STATUS_PENDING, row.getUploadStatus());
        assertEquals(0, row.getAttemptCount());
    }

    @Test
    @DisplayName("uploadPending：成功条 success / 失败条 failed，统计正确")
    void uploadPending_marksSuccessAndFailed() {
        GzPayShippingOrder ok = row(1L, GzPayShippingOrder.STATUS_PENDING);
        GzPayShippingOrder bad = row(2L, GzPayShippingOrder.STATUS_FAILED);
        when(shippingMapper.selectList(any())).thenReturn(List.of(ok, bad));
        when(shippingClient.uploadShippingInfo(any(UploadCommand.class)))
            .thenReturn(UploadResult.ok())
            .thenReturn(UploadResult.fail(40003, "invalid openid"));

        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());

            UploadStats stats = service.uploadPending();

            assertEquals(2, stats.scanned());
            assertEquals(1, stats.success());
            assertEquals(1, stats.failed());
        }

        // 回写走带守卫的 updateStatusGuarded（不再是无条件 updateById）
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(shippingMapper, times(2)).updateStatusGuarded(any(), any(), status.capture(), any(), any(), any());
        assertEquals(GzPayShippingOrder.STATUS_SUCCESS, status.getAllValues().get(0));
        assertEquals(GzPayShippingOrder.STATUS_FAILED, status.getAllValues().get(1));
    }

    @Test
    @DisplayName("uploadPending：已 success 行幂等跳过，不重复调微信")
    void uploadPending_skipsAlreadySuccess() {
        GzPayShippingOrder done = row(3L, GzPayShippingOrder.STATUS_SUCCESS);
        when(shippingMapper.selectList(any())).thenReturn(List.of(done));

        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());

            UploadStats stats = service.uploadPending();

            assertEquals(1, stats.scanned());
            assertEquals(1, stats.success());
            assertEquals(0, stats.failed());
        }
        // 已 success → 不调微信、不再 updateById
        verify(shippingClient, times(0)).uploadShippingInfo(any());
        verify(shippingMapper, times(0)).updateById(any(GzPayShippingOrder.class));
    }

    @Test
    @DisplayName("backfillPending：扫全部 pending/failed（不受窗口/尝试上限约束）批量补报，统计正确")
    void backfillPending_marksSuccessAndFailed() {
        GzPayShippingOrder ok = row(11L, GzPayShippingOrder.STATUS_FAILED);
        ok.setAttemptCount(20); // 远超 MAX_ATTEMPT=8，手动补报仍应处理
        GzPayShippingOrder bad = row(12L, GzPayShippingOrder.STATUS_PENDING);
        when(shippingMapper.selectList(any())).thenReturn(List.of(ok, bad));
        when(shippingClient.uploadShippingInfo(any(UploadCommand.class)))
            .thenReturn(UploadResult.ok())                       // 已发货单命中幂等 → success
            .thenReturn(UploadResult.fail(10060001, "支付单不存在")); // 时序未就绪 → failed

        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());

            UploadStats stats = service.backfillPending();

            assertEquals(2, stats.scanned());
            assertEquals(1, stats.success());
            assertEquals(1, stats.failed());
        }
        verify(shippingClient, times(2)).uploadShippingInfo(any());
    }

    @Test
    @DisplayName("retryOne：单条命中 → 调微信 + 回写 success，返回 true")
    void retryOne_uploadsAndReturnsTrue() {
        GzPayShippingOrder pending = row(21L, GzPayShippingOrder.STATUS_PENDING);
        when(shippingMapper.selectById(21L)).thenReturn(pending);
        when(shippingClient.uploadShippingInfo(any(UploadCommand.class))).thenReturn(UploadResult.ok());

        boolean result;
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            result = service.retryOne(21L);
        }

        assertEquals(true, result);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(shippingMapper).updateStatusGuarded(any(), any(), status.capture(), any(), any(), any());
        assertEquals(GzPayShippingOrder.STATUS_SUCCESS, status.getValue());
    }

    @Test
    @DisplayName("retryOne：id 为 null 直接返 false，不触 DB")
    void retryOne_nullIdReturnsFalse() {
        assertEquals(false, service.retryOne(null));
        verify(shippingMapper, times(0)).selectById(any());
    }

    // ============================================================
    //  多包裹并发与收口（D6 QA 第 2 轮逮到的三个「静默丢数据」缺陷的回归）
    // ============================================================

    /** 造一行实物件发货任务（已带 N 个包裹）。 */
    private GzPayShippingOrder physicalRow(Long id, String status, String... trackings) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < trackings.length; i++) {
            json.append(i > 0 ? "," : "")
                .append("{\"trackingNo\":\"").append(trackings[i]).append("\",\"carrierName\":\"顺丰速运\"}");
        }
        return GzPayShippingOrder.builder()
            .id(id).transactionId("txn-" + id).outTradeNo("JPO-" + id).businessType("jp")
            .openid("o_jp").clientId("mp-applet-gz-jp")
            .logisticsType(ShippingInfo.PHYSICAL).deliveryMode(ShippingInfo.DELIVERY_MODE_SPLIT)
            .isAllDelivered(Boolean.FALSE)
            .shippingListJson(json.append("]").toString())
            .uploadStatus(status).attemptCount(0)
            .paidTime(LocalDateTime.now().minusMinutes(5))
            .build();
    }

    private static ShippingInfo physical(String tracking, boolean allDelivered) {
        return ShippingInfo.physicalPackage("货",
            new ShippingPackage(tracking, "sf", "顺丰速运", "货", "138****5678"),
            allDelivered, "mp-applet-gz-jp");
    }

    @Test
    @DisplayName("★★ 追加包裹走行锁读（FOR UPDATE），不是普通 selectOne —— 不然并发会丢包裹")
    void appendPackage_readsWithRowLock() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            GzPayShippingOrder row1 = physicalRow(1L, GzPayShippingOrder.STATUS_SUCCESS, "TRK-A");
            when(shippingMapper.selectOne(any())).thenReturn(row1);
            when(shippingMapper.selectByTransactionIdForUpdate("txn-1")).thenReturn(row1);

            service.enqueue(txn("txn-1"), physical("TRK-B", false));

            // ★ 必须走加锁读；一旦有人改回 selectOne，这条就红
            verify(shippingMapper).selectByTransactionIdForUpdate("txn-1");
            ArgumentCaptor<GzPayShippingOrder> captor = ArgumentCaptor.forClass(GzPayShippingOrder.class);
            verify(shippingMapper).updateById(captor.capture());
            String json = captor.getValue().getShippingListJson();
            assertTrue(json.contains("TRK-A") && json.contains("TRK-B"),
                "两个包裹都要在清单里，实际=" + json);
        }
    }

    @Test
    @DisplayName("★★ 并发建行撞唯一键 → 回头把包裹并进既有行，绝不能直接 return（那会丢掉本次运单）")
    void enqueue_duplicateKeyThenAppends() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            // 第一次加锁读没读到（行还没被对方提交）→ 走 insert → 撞唯一键 → 再读就有了
            when(shippingMapper.selectOne(any())).thenReturn(null);   // 快路径读不到 → 走 insert
            when(shippingMapper.selectByTransactionIdForUpdate("txn-2"))
                .thenReturn(physicalRow(2L, GzPayShippingOrder.STATUS_PENDING, "TRK-WINNER"));
            when(shippingMapper.insert(any(GzPayShippingOrder.class))).thenThrow(new DuplicateKeyException("uk_transaction_id"));

            service.enqueue(txn("txn-2"), physical("TRK-MINE", false));

            ArgumentCaptor<GzPayShippingOrder> captor = ArgumentCaptor.forClass(GzPayShippingOrder.class);
            verify(shippingMapper).updateById(captor.capture());
            String json = captor.getValue().getShippingListJson();
            assertTrue(json.contains("TRK-WINNER") && json.contains("TRK-MINE"),
                "★ 输的那一笔的运单不能凭空消失，实际=" + json);
        }
    }

    @Test
    @DisplayName("★ 入队炸了要把原因写进 last_error（只写日志的话运营完全看不见包裹丢了）")
    void enqueueFailure_writesLastError() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            GzPayShippingOrder row3 = physicalRow(3L, GzPayShippingOrder.STATUS_SUCCESS, "TRK-A");
            when(shippingMapper.selectOne(any())).thenReturn(row3);
            when(shippingMapper.selectByTransactionIdForUpdate("txn-3")).thenReturn(row3);
            // 追加时写库炸（模拟列装不下 / 连接问题）—— 语句级失败，应被吞掉并记 last_error
            when(shippingMapper.updateById(any(GzPayShippingOrder.class))).thenThrow(new RuntimeException("Data too long for column"));

            service.enqueue(txn("txn-3"), physical("TRK-B", false));   // 不抛

            ArgumentCaptor<GzPayShippingOrder> captor = ArgumentCaptor.forClass(GzPayShippingOrder.class);
            verify(shippingMapper, atLeastOnce()).updateById(captor.capture());
            boolean wroteError = captor.getAllValues().stream()
                .anyMatch(r -> r.getLastError() != null && r.getLastError().contains("TRK-B"));
            assertTrue(wroteError, "★ 必须把失败原因落到 last_error，admin 才看得见");
        }
    }

    @Test
    @DisplayName("★★ markAllDelivered：整单发完（最后一款是购买失败）→ 收口并重新排队")
    void markAllDelivered_setsFlagAndRequeues() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            when(shippingMapper.markAllDeliveredAtomic("txn-4")).thenReturn(1);
            when(shippingMapper.selectOne(any())).thenReturn(physicalRow(4L, GzPayShippingOrder.STATUS_PENDING, "TRK-A"));

            assertTrue(service.markAllDelivered("txn-4"));

            // ★ 判定与写入必须在同一条原子 UPDATE 里（autocommit 下 FOR UPDATE 等于没锁，
            //   「读→判定→updateById」中间会被 in-flight 的上报把 blocked 写进来又被覆盖掉）
            verify(shippingMapper).markAllDeliveredAtomic("txn-4");
            verify(shippingMapper, times(0)).updateById(any(GzPayShippingOrder.class));
        }
    }

    @Test
    @DisplayName("markAllDelivered：没有发货任务行（整单全部购买失败）→ 什么都不做，不报错")
    void markAllDelivered_noRowIsNoop() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            when(shippingMapper.markAllDeliveredAtomic("txn-5")).thenReturn(0);

            assertEquals(false, service.markAllDelivered("txn-5"));
            verify(shippingMapper, times(0)).updateById(any(GzPayShippingOrder.class));
        }
    }

    @Test
    @DisplayName("★ markAllDelivered：blocked 行只置标记、不自动重试（那次机会不能烧在 cron 上）")
    void markAllDelivered_blockedDoesNotRequeue() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            when(shippingMapper.markAllDeliveredAtomic("txn-6")).thenReturn(1);
            when(shippingMapper.selectOne(any())).thenReturn(physicalRow(6L, GzPayShippingOrder.STATUS_BLOCKED, "TRK-A"));

            assertEquals(false, service.markAllDelivered("txn-6"));

            // blocked 的保护写在 SQL 的 CASE WHEN 里；服务层不许再补一发 updateById 把它盖掉
            verify(shippingMapper, times(0)).updateById(any(GzPayShippingOrder.class));
        }
    }

    @Test
    @DisplayName("★★ is_all_delivered 单调收敛：已 true 的行不能被后到的 false 踩回去")
    void isAllDeliveredNeverGoesBackToFalse() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            GzPayShippingOrder already = physicalRow(9L, GzPayShippingOrder.STATUS_SUCCESS, "TRK-A");
            already.setIsAllDelivered(Boolean.TRUE);          // 另一笔并发已经收过口
            when(shippingMapper.selectOne(any())).thenReturn(already);
            when(shippingMapper.selectByTransactionIdForUpdate("txn-9")).thenReturn(already);

            // 晚到的这笔在自己事务里算出的是 false（看不见对方的提交）
            service.enqueue(txn("txn-9"), physical("TRK-B", false));

            ArgumentCaptor<GzPayShippingOrder> captor = ArgumentCaptor.forClass(GzPayShippingOrder.class);
            verify(shippingMapper).updateById(captor.capture());
            assertEquals(Boolean.TRUE, captor.getValue().getIsAllDelivered(),
                "★ 被踩回 false 的话，整单其实已发完、微信侧却永远停在「部分发货」");
        }
    }

    @Test
    @DisplayName("★★ 上报回写必须带守卫：期间被追加包裹改过 → 不许把 success 盖上去（否则新包裹永不上报）")
    void uploadWriteBackIsGuarded() {
        GzPayShippingOrder row = physicalRow(31L, GzPayShippingOrder.STATUS_PENDING, "TRK-A");
        row.setAttemptCount(0);
        row.setContentVersion(7L);
        when(shippingMapper.selectById(31L)).thenReturn(row);
        when(shippingClient.uploadShippingInfo(any(UploadCommand.class))).thenReturn(UploadResult.ok());

        service.retryOne(31L);

        // ★ 守卫必须用 content_version 做代际 —— 不能用 (upload_status, attempt_count)：
        //   追加包裹写入的正是 (pending, 0)，与首次上报读到的逐字相同 ⇒ ABA 恒命中、等于没守。
        verify(shippingMapper).updateStatusGuarded(eq(31L), eq(7L),
            eq(GzPayShippingOrder.STATUS_SUCCESS), eq(1), any(), eq(null));
        // 绝不能再有无条件的 updateById 把状态盖掉
        verify(shippingMapper, times(0)).updateById(any(GzPayShippingOrder.class));
    }

    @Test
    @DisplayName("守卫未命中（并发追加已把行改回 pending/0）→ 静默放弃本次结果，交给新一次上报")
    void guardMissDropsStaleResult() {
        GzPayShippingOrder row = physicalRow(32L, GzPayShippingOrder.STATUS_PENDING, "TRK-A");
        row.setAttemptCount(0);
        when(shippingMapper.selectById(32L)).thenReturn(row);
        when(shippingClient.uploadShippingInfo(any(UploadCommand.class))).thenReturn(UploadResult.ok());
        when(shippingMapper.updateStatusGuarded(any(), any(), any(), any(), any(), any())).thenReturn(0);

        // 不抛、不改成别的状态；这一笔的结果被丢弃是正确行为
        service.retryOne(32L);

        verify(shippingMapper, times(0)).updateById(any(GzPayShippingOrder.class));
    }

    private static GzPayTransaction txn(String transactionId) {
        return GzPayTransaction.builder()
            .transactionId(transactionId).outTradeNo("JPO-x").businessType("jp").openid("o_jp")
            .paidTime(LocalDateTime.now()).build();
    }

    private GzPayShippingOrder row(Long id, String status) {
        return GzPayShippingOrder.builder()
            .id(id)
            .transactionId("txn-" + id)
            .outTradeNo("PINDOU-20260704-00000" + id)
            .businessType("pindou")
            .openid("o_openid_" + id)
            .logisticsType(ShippingInfo.VIRTUAL)
            .itemDesc("谷子宇宙·拼豆预约")
            .paidTime(LocalDateTime.now().minusMinutes(5))
            .uploadStatus(status)
            .attemptCount(status.equals(GzPayShippingOrder.STATUS_FAILED) ? 1 : 0)
            .build();
    }
}
