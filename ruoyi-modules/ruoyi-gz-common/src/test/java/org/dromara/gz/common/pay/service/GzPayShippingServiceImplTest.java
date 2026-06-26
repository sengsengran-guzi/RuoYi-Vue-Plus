package org.dromara.gz.common.pay.service;

import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayShippingOrder;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.mapper.GzPayShippingOrderMapper;
import org.dromara.gz.common.pay.service.IGzPayShippingService.UploadStats;
import org.dromara.gz.common.pay.service.impl.GzPayShippingServiceImpl;
import org.dromara.gz.common.pay.shipping.ShippingInfo;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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

        ArgumentCaptor<GzPayShippingOrder> captor = ArgumentCaptor.forClass(GzPayShippingOrder.class);
        verify(shippingMapper, times(2)).updateById(captor.capture());
        List<GzPayShippingOrder> updates = captor.getAllValues();
        assertEquals(GzPayShippingOrder.STATUS_SUCCESS, updates.get(0).getUploadStatus());
        assertEquals(GzPayShippingOrder.STATUS_FAILED, updates.get(1).getUploadStatus());
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
