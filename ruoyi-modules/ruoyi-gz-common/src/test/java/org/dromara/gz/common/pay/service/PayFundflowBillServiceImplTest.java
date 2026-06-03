package org.dromara.gz.common.pay.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.mapper.GzPayBillFundflowMapper;
import org.dromara.gz.common.pay.service.PayFundflowBillService.ReconcileResult;
import org.dromara.gz.common.pay.service.impl.PayFundflowBillServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.FundFlowBill;
import org.dromara.gz.common.pay.service.internal.bill.FundFlowBillCsvBuilder;
import org.dromara.gz.common.pay.service.internal.bill.FundFlowBillCsvBuilder.MockTxn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link PayFundflowBillServiceImpl} 单测（GZ-PAY-104，全 mock，脱离 Spring / SnailJob / DB）。
 *
 * <p>覆盖（任务卡 Tier 1A）：</p>
 * <ol>
 *   <li>CSV 解析 — 正常行 / 表头存在 / 空行容错 / 关键列缺失抛错（AC3）</li>
 *   <li>transaction_id 关联回写 fee_cent（AC4）+ 覆盖式（非累加）</li>
 *   <li>AC5 两类告警计数 — 孤儿账单（transactionNotFound）+ 缺账（paidNoBill）</li>
 *   <li>AC6 同日重跑幂等 — upsert 调用次数稳定 + fee 覆盖式（第二次值不叠加）</li>
 *   <li>hash 校验失败抛错（AC3，不静默回写）</li>
 * </ol>
 *
 * <p>account：用 {@link FundFlowBillCsvBuilder} 构造可控账单 CSV，mock {@link IWechatPayClient}
 * 返回它；mock {@link GzPayBillFundflowMapper} 控制回写 affected / 缺账集合；{@code TenantHelper.ignore}
 * 用 MockedStatic 直接执行 supplier（脱离租户上下文）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PayFundflowBillServiceImplTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 6, 5);
    private static final String TENANT_ID = "1001";

    @Mock
    private IWechatPayClient wechatPayClient;

    @Mock
    private GzPayBillFundflowMapper billFundflowMapper;

    private PayFundflowBillServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PayFundflowBillServiceImpl(wechatPayClient, billFundflowMapper);
    }

    /** 构造一份含 N 笔交易（带手续费）的可控账单，包进 mock client 返回（hash 自匹配）。 */
    private void stubBill(List<MockTxn> txns) {
        String csv = FundFlowBillCsvBuilder.build(BIZ_DATE, txns);
        when(wechatPayClient.downloadFundFlowBill(BIZ_DATE))
            .thenReturn(new FundFlowBill(BIZ_DATE, csv, FundFlowBillCsvBuilder.sha1Hex(csv)));
    }

    /** 在 TenantHelper.ignore 内直接执行 supplier 跑断言。 */
    private ReconcileResult runReconcile() {
        try (MockedStatic<TenantHelper> mocked = mockStatic(TenantHelper.class)) {
            mocked.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
            return service.reconcileFee(BIZ_DATE);
        }
    }

    // ============================================================
    //  1. happy path：解析 2 笔 + 全部命中回写 + 无对不上
    // ============================================================

    @Test
    @DisplayName("happy path：账单 2 笔均匹配系统 paid 交易 → 全部回写 fee_cent，无孤儿/缺账")
    void reconcile_happyPath_allMatched() {
        stubBill(List.of(
            new MockTxn("4200WX0001", "TEST-20260605-000001", "0.01", "0.01"),    // fee 1 分
            new MockTxn("4200WX0002", "PREORD-20260605-000001", "99.00", "0.59")  // fee 59 分
        ));
        // 两笔回写均命中（affected=1）
        when(billFundflowMapper.updateFeeCentByTransactionId(eq(TENANT_ID), anyString(), anyLong()))
            .thenReturn(1);
        // 系统当日 paid 交易号 = 账单两笔（无缺账）
        when(billFundflowMapper.selectPaidTransactionIdsByDate(TENANT_ID, BIZ_DATE))
            .thenReturn(List.of("4200WX0001", "4200WX0002"));

        ReconcileResult r = runReconcile();

        assertEquals(2, r.billTotal());
        assertEquals(2, r.matched());
        assertEquals(0, r.transactionNotFoundCount());
        assertEquals(0, r.paidNoBillCount());
        assertFalse(r.hasMismatch());

        // 每笔都先 upsert 账单明细，再覆盖式回写
        verify(billFundflowMapper, times(2)).upsertBill(eq(TENANT_ID), eq(BIZ_DATE), anyString(), anyString(), anyLong(), anyString());
        // 回写的 fee 值正确（1 分 + 59 分）
        ArgumentCaptor<Long> feeCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> txnCap = ArgumentCaptor.forClass(String.class);
        verify(billFundflowMapper, times(2)).updateFeeCentByTransactionId(eq(TENANT_ID), txnCap.capture(), feeCap.capture());
        assertEquals(List.of("4200WX0001", "4200WX0002"), txnCap.getAllValues());
        assertEquals(List.of(1L, 59L), feeCap.getAllValues(), "元→分换算：0.01→1 / 0.59→59");
    }

    // ============================================================
    //  2. AC4 覆盖式回写：fee_cent SET 绝对值（用 UPDATE 语句保证，非累加）
    // ============================================================

    @Test
    @DisplayName("AC4：按 transaction_id 调 updateFeeCentByTransactionId 覆盖式回写（fee 取账单聚合绝对值）")
    void reconcile_writeback_overwrite_notAccumulate() {
        // 同一交易号在账单里有 2 个手续费行（0.30 + 0.29）→ 解析器聚合 = 59 分（一次性覆盖，不是两次累加）
        String csv = FundFlowBillCsvBuilder.build(BIZ_DATE, List.of(
            new MockTxn("4200WX0009", "PREORD-20260605-000009", "99.00", "0.30"),
            new MockTxn("4200WX0009", "PREORD-20260605-000009", "0.00", "0.29")
        ));
        when(wechatPayClient.downloadFundFlowBill(BIZ_DATE))
            .thenReturn(new FundFlowBill(BIZ_DATE, csv, FundFlowBillCsvBuilder.sha1Hex(csv)));
        when(billFundflowMapper.updateFeeCentByTransactionId(eq(TENANT_ID), anyString(), anyLong())).thenReturn(1);
        when(billFundflowMapper.selectPaidTransactionIdsByDate(TENANT_ID, BIZ_DATE))
            .thenReturn(List.of("4200WX0009"));

        ReconcileResult r = runReconcile();

        assertEquals(1, r.billTotal(), "同交易号聚合为 1 笔");
        // 同一 transaction_id 只回写 1 次，fee = 30+29 = 59 分（聚合后的绝对值，覆盖式）
        verify(billFundflowMapper, times(1))
            .updateFeeCentByTransactionId(TENANT_ID, "4200WX0009", 59L);
    }

    // ============================================================
    //  3. AC5 ①：孤儿账单（账单有但系统无 paid 交易）→ transactionNotFound 计数
    // ============================================================

    @Test
    @DisplayName("AC5 ①：账单有 transaction_id 但系统无对应 paid 交易（回写 affected=0）→ transactionNotFound++")
    void reconcile_orphanBillRow_transactionNotFound() {
        stubBill(List.of(
            new MockTxn("4200WX_MATCH", "TEST-20260605-000001", "0.01", "0.01"),   // 命中
            new MockTxn("4200WX_ORPHAN", "GHOST-20260605-000001", "5.00", "0.10")  // 孤儿
        ));
        // 命中笔 affected=1；孤儿笔 affected=0（系统无该 paid 交易）
        when(billFundflowMapper.updateFeeCentByTransactionId(TENANT_ID, "4200WX_MATCH", 1L)).thenReturn(1);
        when(billFundflowMapper.updateFeeCentByTransactionId(TENANT_ID, "4200WX_ORPHAN", 10L)).thenReturn(0);
        when(billFundflowMapper.selectPaidTransactionIdsByDate(TENANT_ID, BIZ_DATE))
            .thenReturn(List.of("4200WX_MATCH"));

        ReconcileResult r = runReconcile();

        assertEquals(2, r.billTotal());
        assertEquals(1, r.matched());
        assertEquals(1, r.transactionNotFoundCount(), "1 笔孤儿账单");
        assertEquals(List.of("4200WX_ORPHAN"), r.transactionNotFound());
        assertEquals(0, r.paidNoBillCount());
        assertTrue(r.hasMismatch());
    }

    // ============================================================
    //  4. AC5 ②：缺账（系统当日 paid 交易但账单缺其行）→ paidNoBill 计数
    // ============================================================

    @Test
    @DisplayName("AC5 ②：系统当日有 paid 交易但账单缺其行 → paidNoBill++")
    void reconcile_paidNoBill_missingFromBill() {
        stubBill(List.of(
            new MockTxn("4200WX_IN_BILL", "TEST-20260605-000001", "0.01", "0.01")
        ));
        when(billFundflowMapper.updateFeeCentByTransactionId(TENANT_ID, "4200WX_IN_BILL", 1L)).thenReturn(1);
        // 系统当日有 2 笔 paid，但账单只含 1 笔 → 另一笔缺账
        when(billFundflowMapper.selectPaidTransactionIdsByDate(TENANT_ID, BIZ_DATE))
            .thenReturn(List.of("4200WX_IN_BILL", "4200WX_NO_BILL"));

        ReconcileResult r = runReconcile();

        assertEquals(1, r.billTotal());
        assertEquals(1, r.matched());
        assertEquals(0, r.transactionNotFoundCount());
        assertEquals(1, r.paidNoBillCount(), "1 笔缺账");
        assertEquals(List.of("4200WX_NO_BILL"), r.paidNoBill());
        assertTrue(r.hasMismatch());
    }

    // ============================================================
    //  5. AC5 综合：孤儿 + 缺账同时触发（mock 模式 AC7 驱动的全链路计数）
    // ============================================================

    @Test
    @DisplayName("AC5/AC7：1 正常匹配 + 1 孤儿 + 1 缺账 → 两类计数均 > 0")
    void reconcile_bothMismatchTypesTriggered() {
        // 账单含：匹配笔 + 孤儿笔（系统无）；系统当日 paid 含：匹配笔 + 缺账笔（账单无）
        stubBill(List.of(
            new MockTxn("4200WX_OK", "TEST-20260605-000001", "0.01", "0.01"),
            new MockTxn("4200WX_ORPHAN", "GHOST-1", "5.00", "0.10")
        ));
        when(billFundflowMapper.updateFeeCentByTransactionId(TENANT_ID, "4200WX_OK", 1L)).thenReturn(1);
        when(billFundflowMapper.updateFeeCentByTransactionId(TENANT_ID, "4200WX_ORPHAN", 10L)).thenReturn(0);
        when(billFundflowMapper.selectPaidTransactionIdsByDate(TENANT_ID, BIZ_DATE))
            .thenReturn(List.of("4200WX_OK", "4200WX_MISSING"));

        ReconcileResult r = runReconcile();

        assertEquals(2, r.billTotal());
        assertEquals(1, r.matched());
        assertEquals(1, r.transactionNotFoundCount());
        assertEquals(1, r.paidNoBillCount());
        assertTrue(r.hasMismatch(), "两类对不上同时触发");
    }

    // ============================================================
    //  6. AC6 同日重跑幂等：行数不翻倍（upsert 次数稳定）+ fee 覆盖非累加
    // ============================================================

    @Test
    @DisplayName("AC6：同一 bizDate 连跑 2 次 → upsert 调用次数对称（每次 N 笔，不翻倍）+ 同笔 fee 覆盖值相同")
    void reconcile_idempotent_rerun_noDoubleAndOverwrite() {
        List<MockTxn> txns = List.of(
            new MockTxn("4200WX_RERUN", "TEST-20260605-000001", "0.01", "0.01")
        );
        stubBill(txns);
        when(billFundflowMapper.updateFeeCentByTransactionId(eq(TENANT_ID), anyString(), anyLong())).thenReturn(1);
        when(billFundflowMapper.selectPaidTransactionIdsByDate(TENANT_ID, BIZ_DATE))
            .thenReturn(List.of("4200WX_RERUN"));

        ReconcileResult r1 = runReconcile();
        ReconcileResult r2 = runReconcile();

        // 两次结果一致（幂等）
        assertEquals(r1.billTotal(), r2.billTotal());
        assertEquals(1, r2.matched());
        // upsert 总调用 = 2 次（每次 1 笔），DB 层 UNIQUE 保证行数不翻倍；同笔 fee 始终 1（覆盖非累加）
        verify(billFundflowMapper, times(2)).upsertBill(
            eq(TENANT_ID), eq(BIZ_DATE), eq("4200WX_RERUN"), eq("TEST-20260605-000001"), eq(1L), anyString());
        // 两次回写的 fee 均为 1 分（不会变 2 分 = 没有累加）
        verify(billFundflowMapper, times(2)).updateFeeCentByTransactionId(TENANT_ID, "4200WX_RERUN", 1L);
    }

    // ============================================================
    //  7. AC3：hash 校验失败 → 抛错，不落库不回写（不静默回写脏数据）
    // ============================================================

    @Test
    @DisplayName("AC3：下载文件 sha1 与微信声明 hash 不匹配 → ServiceException + 不 upsert 不回写")
    void reconcile_hashMismatch_throwsAndNoWrite() {
        String csv = FundFlowBillCsvBuilder.build(BIZ_DATE, List.of(
            new MockTxn("4200WX_X", "TEST-20260605-000001", "0.01", "0.01")
        ));
        // 微信声明一个坏 hash（与 csv 真实 sha1 不符）
        when(wechatPayClient.downloadFundFlowBill(BIZ_DATE))
            .thenReturn(new FundFlowBill(BIZ_DATE, csv, "deadbeef_bad_hash"));

        ServiceException ex = assertThrows(ServiceException.class, this::runReconcile);
        assertTrue(ex.getMessage().contains("hash 校验失败"));
        verify(billFundflowMapper, never()).upsertBill(anyString(), any(), anyString(), anyString(), anyLong(), anyString());
        verify(billFundflowMapper, never()).updateFeeCentByTransactionId(anyString(), anyString(), anyLong());
    }

    // ============================================================
    //  8. AC3：CSV 关键列缺失 → 解析抛错
    // ============================================================

    @Test
    @DisplayName("AC3：账单缺关键明细列（如「业务类型」）→ 解析抛 ServiceException")
    void reconcile_csvMissingKeyColumn_throws() {
        // 构造一份缺「业务类型」列的明细表头（含交易号列以通过 headerIdx 定位）
        String badCsv = "`记账时间,`微信支付业务单号,`收支金额(元)\r\n"
            + "`2026-06-05 10:00:00,`4200WX_X,`0.01\r\n";
        when(wechatPayClient.downloadFundFlowBill(BIZ_DATE))
            .thenReturn(new FundFlowBill(BIZ_DATE, badCsv, FundFlowBillCsvBuilder.sha1Hex(badCsv)));

        ServiceException ex = assertThrows(ServiceException.class, this::runReconcile);
        assertTrue(ex.getMessage().contains("缺列") || ex.getMessage().contains("表头"),
            "缺关键列应抛带「缺列/表头」的错: " + ex.getMessage());
        verify(billFundflowMapper, never()).updateFeeCentByTransactionId(anyString(), anyString(), anyLong());
    }

    // ============================================================
    //  9. 空行容错：账单中段空行不致解析失败（正常产出）
    // ============================================================

    @Test
    @DisplayName("CSV 容错：明细段中段空行被跳过，不影响正常笔解析")
    void reconcile_blankLineInDetail_tolerated() {
        String csv = FundFlowBillCsvBuilder.build(BIZ_DATE, List.of(
            new MockTxn("4200WX_A", "TEST-20260605-000001", "0.01", "0.01")
        ));
        // 在明细表头后插入一个空行（模拟账单中段空行）
        String[] lines = csv.split("\r\n", 2);
        String csvWithBlank = lines[0] + "\r\n\r\n" + lines[1];
        when(wechatPayClient.downloadFundFlowBill(BIZ_DATE))
            .thenReturn(new FundFlowBill(BIZ_DATE, csvWithBlank, FundFlowBillCsvBuilder.sha1Hex(csvWithBlank)));
        when(billFundflowMapper.updateFeeCentByTransactionId(eq(TENANT_ID), anyString(), anyLong())).thenReturn(1);
        when(billFundflowMapper.selectPaidTransactionIdsByDate(TENANT_ID, BIZ_DATE))
            .thenReturn(List.of("4200WX_A"));

        ReconcileResult r = runReconcile();

        assertEquals(1, r.billTotal(), "空行被跳过，正常笔仍解析");
        assertEquals(1, r.matched());
    }

    // ============================================================
    //  10. bizDate=null → 早失败（不调通道 / 不回写）
    // ============================================================

    @Test
    @DisplayName("入参校验：bizDate=null → ServiceException，不拉账单不回写")
    void reconcile_nullBizDate_throws() {
        // bizDate 校验在 TenantHelper.ignore 之前，直接调实现即可
        ServiceException ex = assertThrows(ServiceException.class, () -> service.reconcileFee(null));
        assertTrue(ex.getMessage().contains("业务日"));
        verify(wechatPayClient, never()).downloadFundFlowBill(any());
        verify(billFundflowMapper, never()).updateFeeCentByTransactionId(anyString(), anyString(), anyLong());
    }
}
