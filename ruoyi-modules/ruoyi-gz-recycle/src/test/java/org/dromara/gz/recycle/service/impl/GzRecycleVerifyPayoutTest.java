package org.dromara.gz.recycle.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayPayoutTransactionVO;
import org.dromara.gz.common.pay.enums.PayoutStatus;
import org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayPayoutService;
import org.dromara.gz.common.pay.service.IGzPayPayoutService.InitiateBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.exception.GzRecycleErrorCode;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.dromara.gz.recycle.service.IGzRecyclePriceRuleService;
import org.dromara.gz.recycle.service.internal.RecycleApptNoGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecycleAppointmentServiceImpl} 店员核对 + 触发反向打款状态机单测（GZ-RECYCLE-003 AC9，≥ 80% 核心）。
 *
 * <p>覆盖 doc/10 §13 回收状态机 + 触发打款幂等（mapper / payoutService @Mock，驱动状态机分支）：</p>
 * <ol>
 *   <li>核对确认 → submitted→confirmed_onsite + 留痕（verify_image_ids / final_amount / verified_by / verify_time）</li>
 *   <li>触发打款 → confirmed_onsite→paying（回填 out_payout_no，调 PAY-105 initiatePayout）</li>
 *   <li>查单 success 钩子 → paying→paid（syncPayoutResult，markPaid 被调）</li>
 *   <li>查单 failed 钩子 → paying→payout_failed（markPayoutFailed 被调，留人工）</li>
 *   <li>重复核对幂等：version 漂移 markConfirmedOnsite affected=0 → NOT_VERIFIABLE 抛错、不二次触发打款</li>
 *   <li>非 submitted 单核对 → NOT_VERIFIABLE 拒绝</li>
 *   <li>failed 重试：payout_failed→paying（retryPayout，PAY-105 retryPayout + markRetryPaying 被调）</li>
 *   <li>微调留痕：finalAmountCent 与 estimated 不同时，markConfirmedOnsite 收到微调后金额（不是估价）</li>
 *   <li>no_show 兜底：过期 submitted → no_show（markNoShow 被调）</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleVerifyPayoutTest {

    @Mock
    private GzRecycleAppointmentMapper baseMapper;
    @Mock
    private IGzRecyclePriceRuleService priceRuleService;
    @Mock
    private GzUserMapper gzUserMapper;
    @Mock
    private RecycleApptNoGenerator apptNoGenerator;
    @Mock
    private IGzPayPayoutService payoutService;
    @Mock
    private GzPayPayoutTransactionMapper payoutMapper;
    @Mock
    private org.dromara.common.core.service.ConfigService configService;

    private GzRecycleAppointmentServiceImpl service;

    @BeforeEach
    void setUp() {
        // configService 未 stub → getConfigValue 返 null → final_amount 校验走默认（×3 / ¥1000），
        // 现有 verifyAndPayout 测试金额（≤ 估价 5000×3）均放行。
        service = new GzRecycleAppointmentServiceImpl(
            baseMapper, priceRuleService, gzUserMapper, apptNoGenerator, new ObjectMapper(),
            payoutService, payoutMapper, configService);
    }

    /** 把 TenantHelper.ignore(Supplier) 直接执行 supplier（脱离租户上下文）。 */
    private MockedStatic<TenantHelper> mockTenant() {
        MockedStatic<TenantHelper> mocked = mockStatic(TenantHelper.class);
        mocked.when(() -> TenantHelper.ignore(any(Supplier.class)))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        return mocked;
    }

    /** 构造一条 submitted 预约单（估价 5000 分）。 */
    private GzRecycleAppointment submittedAppt(Long id, int version) {
        return GzRecycleAppointment.builder()
            .id(id)
            .appointmentNo("RCY-20260623-000001")
            .userId(1001L)
            .storeId(1L)
            .productSnapshotJson("[{\"category\":\"card\",\"qty\":5}]")
            .totalQty(5)
            .estimatedAmountCent(5000L)
            .receiverOpenid("openid_user_001")
            .status("submitted")
            .version(version)
            .delFlag("0")
            .build();
    }

    private GzRecycleVerifyBo verifyBo(Long apptId, long finalCent, Long... imageIds) {
        GzRecycleVerifyBo bo = new GzRecycleVerifyBo();
        bo.setAppointmentId(apptId);
        bo.setFinalAmountCent(finalCent);
        bo.setVerifyImageIds(List.of(imageIds));
        return bo;
    }

    private GzPayPayoutTransactionVO payoutVo(String outPayoutNo, String status) {
        GzPayPayoutTransactionVO vo = new GzPayPayoutTransactionVO();
        vo.setOutPayoutNo(outPayoutNo);
        vo.setStatus(status);
        return vo;
    }

    @Test
    @DisplayName("AC1/AC2 核对确认 → confirmed_onsite + 留痕 + 触发打款 → paying（全链路第一步）")
    void verify_confirmsAndTriggersPayout_toPaying() {
        GzRecycleAppointment appt = submittedAppt(7001L, 0);
        when(baseMapper.selectById(7001L)).thenReturn(appt);
        when(baseMapper.markConfirmedOnsite(eq(7001L), eq(0), anyString(), eq(5000L), eq("成都门店运营"), any()))
            .thenReturn(1);
        when(payoutService.initiatePayout(any(InitiateBo.class)))
            .thenReturn(payoutVo("PAYOUT-20260623-000001", PayoutStatus.PROCESSING));
        when(baseMapper.markPaying(eq(7001L), eq(1), eq("PAYOUT-20260623-000001"))).thenReturn(1);
        // toAdminVO 重读：返回 paying 态
        GzRecycleAppointment afterPaying = submittedAppt(7001L, 2);
        afterPaying.setStatus("paying");
        afterPaying.setFinalAmountCent(5000L);
        afterPaying.setOutPayoutNo("PAYOUT-20260623-000001");
        when(baseMapper.selectById(7001L)).thenReturn(appt, afterPaying);

        GzRecycleAppointmentAdminVO vo = service.verifyAndPayout(verifyBo(7001L, 5000L, 11L, 12L), "成都门店运营");

        assertEquals("paying", vo.getStatus());
        assertEquals("PAYOUT-20260623-000001", vo.getOutPayoutNo());
        // 留痕：核对照逗号分隔 + final_amount + verified_by 进 markConfirmedOnsite
        verify(baseMapper).markConfirmedOnsite(eq(7001L), eq(0), eq("11,12"), eq(5000L), eq("成都门店运营"), any());
        // 触发打款金额 = final_amount_cent（doc/11 §4.8）
        verify(payoutService).initiatePayout(any(InitiateBo.class));
        verify(baseMapper).markPaying(eq(7001L), eq(1), eq("PAYOUT-20260623-000001"));
    }

    @Test
    @DisplayName("AC9 微调留痕：finalAmount ≠ estimated 时 markConfirmedOnsite 收到微调后金额（非估价）")
    void verify_adjustedFinalAmount_persistsAdjustedNotEstimated() {
        GzRecycleAppointment appt = submittedAppt(7002L, 0); // estimated=5000
        when(baseMapper.selectById(7002L)).thenReturn(appt);
        when(baseMapper.markConfirmedOnsite(eq(7002L), eq(0), anyString(), eq(4200L), anyString(), any()))
            .thenReturn(1);
        when(payoutService.initiatePayout(any(InitiateBo.class)))
            .thenReturn(payoutVo("PAYOUT-20260623-000002", PayoutStatus.PROCESSING));
        lenient().when(baseMapper.markPaying(anyLong(), anyInt(), anyString())).thenReturn(1);
        GzRecycleAppointment after = submittedAppt(7002L, 2);
        after.setStatus("paying");
        when(baseMapper.selectById(7002L)).thenReturn(appt, after);

        service.verifyAndPayout(verifyBo(7002L, 4200L, 21L), "店员A"); // 微调到 4200（< 5000 估价）

        // 触发打款金额取微调后 4200，不是估价 5000
        verify(baseMapper).markConfirmedOnsite(eq(7002L), eq(0), eq("21"), eq(4200L), eq("店员A"), any());
    }

    @Test
    @DisplayName("D16 P5 final_amount 超估价×3 上限 → FINAL_AMOUNT_EXCEEDS_LIMIT 拦截、不核对不打款")
    void verify_finalAmountExceedsLimit_rejected() {
        GzRecycleAppointment appt = submittedAppt(7009L, 0); // estimated=5000 → 软上限 15000
        when(baseMapper.selectById(7009L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.verifyAndPayout(verifyBo(7009L, 20000L, 91L), "店员A")); // 多打一位 20000 > 15000
        assertEquals(GzRecycleErrorCode.FINAL_AMOUNT_EXCEEDS_LIMIT, ex.getCode());
        // 资金安全：超限即拦截，不核对、不触发真打款
        verify(baseMapper, never()).markConfirmedOnsite(anyLong(), anyInt(), anyString(), anyLong(), anyString(), any());
        verify(payoutService, never()).initiatePayout(any(InitiateBo.class));
    }

    @Test
    @DisplayName("AC9 重复核对幂等：version 漂移 markConfirmedOnsite=0 → NOT_VERIFIABLE 抛错、不触发打款")
    void verify_concurrentVersionDrift_throwsNotVerifiable_noPayout() {
        GzRecycleAppointment appt = submittedAppt(7003L, 0);
        when(baseMapper.selectById(7003L)).thenReturn(appt);
        when(baseMapper.markConfirmedOnsite(eq(7003L), eq(0), anyString(), anyLong(), anyString(), any()))
            .thenReturn(0); // 已被并发核对推进

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.verifyAndPayout(verifyBo(7003L, 5000L, 31L), "店员A"));
        assertEquals(GzRecycleErrorCode.NOT_VERIFIABLE, ex.getCode());
        // 关键：核对未成功 → 不触发打款（防重复转账）
        verify(payoutService, never()).initiatePayout(any(InitiateBo.class));
        verify(baseMapper, never()).markPaying(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("AC1 非 submitted 单核对 → NOT_VERIFIABLE 拒绝")
    void verify_nonSubmitted_rejected() {
        GzRecycleAppointment paid = submittedAppt(7004L, 5);
        paid.setStatus("paid");
        when(baseMapper.selectById(7004L)).thenReturn(paid);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.verifyAndPayout(verifyBo(7004L, 5000L, 41L), "店员A"));
        assertEquals(GzRecycleErrorCode.NOT_VERIFIABLE, ex.getCode());
        verify(payoutService, never()).initiatePayout(any(InitiateBo.class));
    }

    @Test
    @DisplayName("AC2 paid 回写钩子：payout success → paying→paid（markPaid 被调）")
    void sync_payoutSuccess_writesBackPaid() {
        GzRecycleAppointment paying = submittedAppt(7005L, 2);
        paying.setStatus("paying");
        paying.setOutPayoutNo("PAYOUT-20260623-000005");
        when(baseMapper.selectSyncablePayoutIds(anyInt())).thenReturn(List.of(7005L));
        when(baseMapper.selectById(7005L)).thenReturn(paying);
        when(payoutMapper.selectByOutPayoutNo("PAYOUT-20260623-000005"))
            .thenReturn(payoutEntity("PAYOUT-20260623-000005", PayoutStatus.SUCCESS));
        when(baseMapper.markPaid(7005L)).thenReturn(1);

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            int paid = service.syncPayoutResult();
            assertEquals(1, paid);
        }
        verify(baseMapper).markPaid(7005L);
        verify(baseMapper, never()).markPayoutFailed(anyLong());
    }

    @Test
    @DisplayName("AC2/AC3 paid 回写钩子：payout failed → paying→payout_failed（markPayoutFailed 被调，留人工）")
    void sync_payoutFailed_writesBackPayoutFailed() {
        GzRecycleAppointment paying = submittedAppt(7006L, 2);
        paying.setStatus("paying");
        paying.setOutPayoutNo("PAYOUT-20260623-000006");
        when(baseMapper.selectSyncablePayoutIds(anyInt())).thenReturn(List.of(7006L));
        when(baseMapper.selectById(7006L)).thenReturn(paying);
        when(payoutMapper.selectByOutPayoutNo("PAYOUT-20260623-000006"))
            .thenReturn(payoutEntity("PAYOUT-20260623-000006", PayoutStatus.FAILED));
        when(baseMapper.markPayoutFailed(7006L)).thenReturn(1);

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            int paid = service.syncPayoutResult();
            assertEquals(0, paid); // failed 不计 paid
        }
        verify(baseMapper).markPayoutFailed(7006L);
        verify(baseMapper, never()).markPaid(anyLong());
    }

    @Test
    @DisplayName("AC2 paid 回写钩子：payout 仍 processing → 保持 paying 不回写（等下轮）")
    void sync_payoutProcessing_keepsPaying() {
        GzRecycleAppointment paying = submittedAppt(7007L, 2);
        paying.setStatus("paying");
        paying.setOutPayoutNo("PAYOUT-20260623-000007");
        when(baseMapper.selectSyncablePayoutIds(anyInt())).thenReturn(List.of(7007L));
        when(baseMapper.selectById(7007L)).thenReturn(paying);
        when(payoutMapper.selectByOutPayoutNo("PAYOUT-20260623-000007"))
            .thenReturn(payoutEntity("PAYOUT-20260623-000007", PayoutStatus.PROCESSING));

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            assertEquals(0, service.syncPayoutResult());
        }
        verify(baseMapper, never()).markPaid(anyLong());
        verify(baseMapper, never()).markPayoutFailed(anyLong());
    }

    @Test
    @DisplayName("D16 B4 收敛：owner 从打款单页重试 → 回收单停 payout_failed，payout 查单 success → markPaid 把 payout_failed→paid（不再卡死/资金单据脱钩）")
    void sync_payoutFailedAppt_payoutSuccess_convergesToPaid() {
        // 回收单停在 payout_failed（owner 在 admin『打款单管理』页重试，PAY-105 只推进 payout 单、未回写回收单）
        GzRecycleAppointment stuck = submittedAppt(7008L, 2);
        stuck.setStatus("payout_failed");
        stuck.setOutPayoutNo("PAYOUT-20260623-000008");
        // 收敛扫描集纳入 payout_failed 单
        when(baseMapper.selectSyncablePayoutIds(anyInt())).thenReturn(List.of(7008L));
        when(baseMapper.selectById(7008L)).thenReturn(stuck);
        // payout 单经打款单页重试 + 查单已 success
        when(payoutMapper.selectByOutPayoutNo("PAYOUT-20260623-000008"))
            .thenReturn(payoutEntity("PAYOUT-20260623-000008", PayoutStatus.SUCCESS));
        when(baseMapper.markPaid(7008L)).thenReturn(1);

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            assertEquals(1, service.syncPayoutResult()); // 收敛回写 paid
        }
        verify(baseMapper).markPaid(7008L);
        verify(baseMapper, never()).markPayoutFailed(anyLong());
    }

    @Test
    @DisplayName("AC3 失败重试：payout_failed → paying（retryPayout + markRetryPaying 被调）")
    void retry_payoutFailed_backToPaying() {
        GzRecycleAppointment failed = submittedAppt(7008L, 3);
        failed.setStatus("payout_failed");
        failed.setOutPayoutNo("PAYOUT-20260623-000008");
        GzRecycleAppointment afterRetry = submittedAppt(7008L, 4);
        afterRetry.setStatus("paying");
        when(baseMapper.selectById(7008L)).thenReturn(failed, afterRetry);
        when(payoutService.retryPayout(eq("RCY-20260623-000001"), anyString()))
            .thenReturn(payoutVo("PAYOUT-20260623-000008", PayoutStatus.PROCESSING));
        when(baseMapper.markRetryPaying(7008L)).thenReturn(1);

        GzRecycleAppointmentAdminVO vo = service.retryPayout(7008L);

        assertEquals("paying", vo.getStatus());
        verify(payoutService).retryPayout(eq("RCY-20260623-000001"), anyString());
        verify(baseMapper).markRetryPaying(7008L);
    }

    @Test
    @DisplayName("AC3 非 payout_failed 单重试 → 拒绝（不调 PAY-105）")
    void retry_nonFailed_rejected() {
        GzRecycleAppointment paying = submittedAppt(7009L, 2);
        paying.setStatus("paying");
        when(baseMapper.selectById(7009L)).thenReturn(paying);

        assertThrows(ServiceException.class, () -> service.retryPayout(7009L));
        verify(payoutService, never()).retryPayout(anyString(), anyString());
    }

    @Test
    @DisplayName("AC7 no_show 兜底：过期 submitted → no_show（markNoShow 被调）")
    void noShow_expiredSubmitted_marked() {
        when(baseMapper.selectExpiredSubmittedIds(any(), anyInt())).thenReturn(List.of(7010L, 7011L));
        when(baseMapper.markNoShow(7010L)).thenReturn(1);
        when(baseMapper.markNoShow(7011L)).thenReturn(1);

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            assertEquals(2, service.markExpiredNoShow());
        }
        verify(baseMapper).markNoShow(7010L);
        verify(baseMapper).markNoShow(7011L);
    }

    private GzPayPayoutTransaction payoutEntity(String outPayoutNo, String status) {
        return GzPayPayoutTransaction.builder()
            .outPayoutNo(outPayoutNo)
            .status(status)
            .build();
    }
}
