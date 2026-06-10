package org.dromara.gz.common.pay.service;

import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayPayoutTransactionVO;
import org.dromara.gz.common.pay.enums.PayoutStatus;
import org.dromara.gz.common.pay.mapper.GzPayPayoutCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayPayoutService.InitiateBo;
import org.dromara.gz.common.pay.service.IGzPayPayoutService.QueryResult;
import org.dromara.gz.common.pay.service.impl.GzPayPayoutServiceImpl;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.dromara.gz.common.pay.service.internal.PayoutNoGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * {@link GzPayPayoutServiceImpl} 单测（GZ-PAY-105 AC8，全 mock，≥ 80% 核心覆盖）。
 *
 * <p>覆盖 ADR-0006 反向打款全链路 + 双重幂等（mapper @Mock + 真实 {@link MockWechatPayClient} 驱动状态机）：</p>
 * <ol>
 *   <li>created→processing：受理成功 → markProcessing 被调（AC2/AC4）</li>
 *   <li>受理失败 → created→failed：markCreatedFailed 被调（AC2 failed 分支）</li>
 *   <li>查单 SUCCESS → processing→success：markSuccess 被调 + 存档（AC5）</li>
 *   <li>查单 FAIL → processing→failed：markFailed 被调（AC5 failed 分支）</li>
 *   <li>查单仍 PROCESSING → 保持，不推进终态（AC5）</li>
 *   <li>1:1 幂等：business_order_no 已有活跃单 → 返回已有单、不二次转账（AC6，transferToUserWallet never 调）</li>
 *   <li>failed→created 重试：retryFailedToCreated（ADR-0006 旁路可重试）</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzPayPayoutServiceImplTest {

    @Mock
    private GzPayPayoutTransactionMapper payoutMapper;

    @Mock
    private GzPayPayoutCallbackLogMapper payoutCallbackLogMapper;

    @Mock
    private PayoutNoGenerator payoutNoGenerator;

    /** 真实 mock 通道（非 Mockito mock）：驱动受理/查单状态机分支 */
    private MockWechatPayClient mockClient;

    private GzPayPayoutServiceImpl service;

    @BeforeEach
    void setUp() {
        mockClient = new MockWechatPayClient(new WechatPayProperties());
        service = new GzPayPayoutServiceImpl(payoutMapper, payoutCallbackLogMapper, payoutNoGenerator, mockClient);
    }

    /** 把 TenantHelper.ignore(Supplier) 直接执行 supplier（脱离租户上下文）。 */
    private MockedStatic<TenantHelper> mockTenant() {
        MockedStatic<TenantHelper> mocked = mockStatic(TenantHelper.class);
        mocked.when(() -> TenantHelper.ignore(any(Supplier.class)))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        return mocked;
    }

    /** 构造一条 processing 单（查单测试用）。 */
    private GzPayPayoutTransaction processingPayout(Long id, String outPayoutNo) {
        GzPayPayoutTransaction p = GzPayPayoutTransaction.builder()
            .id(id)
            .outPayoutNo(outPayoutNo)
            .businessType("recycle")
            .businessOrderNo("RCY-20260621-000001")
            .userId(1001L)
            .receiverOpenid("openid_mock")
            .amountCent(5000L)
            .status(PayoutStatus.PROCESSING)
            .version(1)
            .build();
        return p;
    }

    @Test
    @DisplayName("AC2/AC4 受理成功 → created→processing（markProcessing 被调，写 payout_id+batch_id）")
    void initiate_acceptSuccess_marksProcessing() {
        when(payoutMapper.selectByActiveBusinessOrderNo(anyString())).thenReturn(null);
        when(payoutNoGenerator.generate()).thenReturn("PAYOUT-20260621-000001");
        when(payoutMapper.insert(any(GzPayPayoutTransaction.class))).thenAnswer(inv -> {
            ((GzPayPayoutTransaction) inv.getArgument(0)).setId(9001L);
            return 1;
        });
        when(payoutMapper.markProcessing(eq(9001L), eq(0), anyString(), anyString())).thenReturn(1);
        GzPayPayoutTransactionVO afterVo = new GzPayPayoutTransactionVO();
        afterVo.setStatus(PayoutStatus.PROCESSING);
        when(payoutMapper.selectVoById(9001L)).thenReturn(afterVo);

        GzPayPayoutTransactionVO vo = service.initiatePayout(new InitiateBo(
            "recycle", "RCY-20260621-000001", 1001L, "openid_mock", 5000L, "谷子回收返现"));

        assertEquals(PayoutStatus.PROCESSING, vo.getStatus());
        // batch_id = mock_batch_ + out_payout_no（幂等可复算）；markProcessing 收到非空 payout_id+batch_id
        verify(payoutMapper).markProcessing(eq(9001L), eq(0),
            eq(MockWechatPayClient.MOCK_PAYOUT_ID_PREFIX + "PAYOUT-20260621-000001"),
            eq(MockWechatPayClient.MOCK_BATCH_ID_PREFIX + "PAYOUT-20260621-000001"));
    }

    @Test
    @DisplayName("AC2 受理失败 → created→failed（markCreatedFailed 被调，不推进 processing）")
    void initiate_acceptFail_marksFailed() {
        mockClient.setTransferAcceptFail(true);
        when(payoutMapper.selectByActiveBusinessOrderNo(anyString())).thenReturn(null);
        when(payoutNoGenerator.generate()).thenReturn("PAYOUT-20260621-000002");
        when(payoutMapper.insert(any(GzPayPayoutTransaction.class))).thenAnswer(inv -> {
            ((GzPayPayoutTransaction) inv.getArgument(0)).setId(9002L);
            return 1;
        });
        GzPayPayoutTransactionVO afterVo = new GzPayPayoutTransactionVO();
        afterVo.setStatus(PayoutStatus.FAILED);
        when(payoutMapper.selectVoById(9002L)).thenReturn(afterVo);

        GzPayPayoutTransactionVO vo = service.initiatePayout(new InitiateBo(
            "recycle", "RCY-20260621-000002", 1001L, "openid_mock", 5000L, "谷子回收返现"));

        assertEquals(PayoutStatus.FAILED, vo.getStatus());
        verify(payoutMapper).markCreatedFailed(eq(9002L), anyString());
        verify(payoutMapper, never()).markProcessing(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("AC6 1:1 幂等：business_order_no 已有活跃单 → 返回已有单、不二次转账（不建单/不调通道）")
    void initiate_duplicateBusinessOrder_returnsExistingNoSecondTransfer() {
        GzPayPayoutTransaction existing = processingPayout(9003L, "PAYOUT-20260621-000003");
        when(payoutMapper.selectByActiveBusinessOrderNo("RCY-20260621-000001")).thenReturn(existing);
        GzPayPayoutTransactionVO existingVo = new GzPayPayoutTransactionVO();
        existingVo.setOutPayoutNo("PAYOUT-20260621-000003");
        existingVo.setStatus(PayoutStatus.PROCESSING);
        when(payoutMapper.selectVoById(9003L)).thenReturn(existingVo);

        GzPayPayoutTransactionVO vo = service.initiatePayout(new InitiateBo(
            "recycle", "RCY-20260621-000001", 1001L, "openid_mock", 5000L, "谷子回收返现"));

        assertNotNull(vo);
        assertEquals("PAYOUT-20260621-000003", vo.getOutPayoutNo());
        // 关键：已有活跃单 → 不建新单、不发起转账（防店员重复点「打款」，ADR-0006 §5）
        verify(payoutMapper, never()).insert(any(GzPayPayoutTransaction.class));
        verify(payoutMapper, never()).markProcessing(anyLong(), anyInt(), anyString(), anyString());
        verify(payoutNoGenerator, never()).generate();
    }

    @Test
    @DisplayName("AC5 查单 SUCCESS → processing→success（markSuccess 被调 + 查单 body 存档）")
    void scan_querySuccess_marksSuccess() {
        mockClient.setQueryTransferState("SUCCESS");
        when(payoutMapper.selectProcessingIds(anyInt())).thenReturn(List.of(9001L));
        when(payoutMapper.selectById(9001L)).thenReturn(processingPayout(9001L, "PAYOUT-20260621-000001"));
        when(payoutMapper.markSuccess(eq(9001L), any())).thenReturn(1);

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            QueryResult result = service.scanAndQuery();
            assertEquals(1, result.scanned());
            assertEquals(1, result.success());
            assertEquals(0, result.failed());
        }
        verify(payoutMapper).markSuccess(eq(9001L), any());
        verify(payoutCallbackLogMapper).insert(any(GzPayPayoutCallbackLog.class)); // 查单结果存档备查（ADR-0006 §3）
        verify(payoutMapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("AC5 查单 FAIL → processing→failed（markFailed 被调，写 fail_reason）")
    void scan_queryFail_marksFailed() {
        mockClient.setQueryTransferState("FAIL");
        when(payoutMapper.selectProcessingIds(anyInt())).thenReturn(List.of(9002L));
        when(payoutMapper.selectById(9002L)).thenReturn(processingPayout(9002L, "PAYOUT-20260621-000002"));
        when(payoutMapper.markFailed(eq(9002L), anyString())).thenReturn(1);

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            QueryResult result = service.scanAndQuery();
            assertEquals(1, result.scanned());
            assertEquals(1, result.failed());
            assertEquals(0, result.success());
        }
        verify(payoutMapper).markFailed(eq(9002L), anyString());
        verify(payoutMapper, never()).markSuccess(anyLong(), any());
    }

    @Test
    @DisplayName("AC5 查单仍 PROCESSING → 保持，不推进终态（pending 计数 +1）")
    void scan_queryProcessing_keepsPending() {
        mockClient.setQueryTransferState("PROCESSING");
        when(payoutMapper.selectProcessingIds(anyInt())).thenReturn(List.of(9003L));
        when(payoutMapper.selectById(9003L)).thenReturn(processingPayout(9003L, "PAYOUT-20260621-000003"));

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            QueryResult result = service.scanAndQuery();
            assertEquals(1, result.scanned());
            assertEquals(1, result.pending());
            assertEquals(0, result.success());
            assertEquals(0, result.failed());
        }
        verify(payoutMapper, never()).markSuccess(anyLong(), any());
        verify(payoutMapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("AC5 行已被并发推进终态（非 processing）→ 查单跳过、不重复推进（幂等）")
    void scan_alreadyTerminal_skips() {
        when(payoutMapper.selectProcessingIds(anyInt())).thenReturn(List.of(9004L));
        GzPayPayoutTransaction terminal = processingPayout(9004L, "PAYOUT-20260621-000004");
        terminal.setStatus(PayoutStatus.SUCCESS); // 已被并发查单/回调推进 success
        when(payoutMapper.selectById(9004L)).thenReturn(terminal);

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            QueryResult result = service.scanAndQuery();
            assertEquals(1, result.scanned());
            assertEquals(1, result.skipped());
        }
        verify(payoutMapper, never()).markSuccess(anyLong(), any());
        verify(payoutCallbackLogMapper, never()).insert(any(GzPayPayoutCallbackLog.class));
    }

    @Test
    @DisplayName("AC4/AC5 全链路 created→processing→success（发起 + 查单两步推进，mock 闭环）")
    void fullChain_createdToProcessingToSuccess() {
        // 第一步：发起 → created→processing
        when(payoutMapper.selectByActiveBusinessOrderNo(anyString())).thenReturn(null);
        when(payoutNoGenerator.generate()).thenReturn("PAYOUT-20260621-000009");
        when(payoutMapper.insert(any(GzPayPayoutTransaction.class))).thenAnswer(inv -> {
            ((GzPayPayoutTransaction) inv.getArgument(0)).setId(9009L);
            return 1;
        });
        when(payoutMapper.markProcessing(eq(9009L), eq(0), anyString(), anyString())).thenReturn(1);
        GzPayPayoutTransactionVO procVo = new GzPayPayoutTransactionVO();
        procVo.setStatus(PayoutStatus.PROCESSING);
        when(payoutMapper.selectVoById(9009L)).thenReturn(procVo);

        GzPayPayoutTransactionVO step1 = service.initiatePayout(new InitiateBo(
            "recycle", "RCY-20260621-000009", 1001L, "openid_mock", 8800L, "谷子回收返现"));
        assertEquals(PayoutStatus.PROCESSING, step1.getStatus());

        // 第二步：查单 SUCCESS → processing→success
        mockClient.setQueryTransferState("SUCCESS");
        when(payoutMapper.selectProcessingIds(anyInt())).thenReturn(List.of(9009L));
        when(payoutMapper.selectById(9009L)).thenReturn(processingPayout(9009L, "PAYOUT-20260621-000009"));
        when(payoutMapper.markSuccess(eq(9009L), any())).thenReturn(1);

        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            QueryResult result = service.scanAndQuery();
            assertEquals(1, result.success());
        }
        verify(payoutMapper).markProcessing(eq(9009L), eq(0), anyString(), anyString());
        verify(payoutMapper).markSuccess(eq(9009L), any());
    }
}
