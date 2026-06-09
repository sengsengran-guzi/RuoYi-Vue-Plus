package org.dromara.gz.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.user.domain.entity.GzLogisticsAudit;
import org.dromara.gz.user.domain.entity.writable.GzGachaLogisticsRow;
import org.dromara.gz.user.domain.entity.writable.GzLogisticsOrderRow;
import org.dromara.gz.user.domain.entity.writable.GzOrdLogisticsRow;
import org.dromara.gz.user.mapper.writable.GzGachaLogisticsMapper;
import org.dromara.gz.user.mapper.writable.GzOrdLogisticsMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link GzLogisticsSignServiceImpl} 单测（GZ-USER-104 AC7）。
 *
 * <p>纯 mock（不连库）：覆盖
 * <ul>
 *   <li>自动签收：扫两表 + 命中委托 txHelper + 计数（7 天阈值由 query wrapper 的 cn_dispatched_at &lt;= now-7d 编码，断言阈值）</li>
 *   <li>自动签收幂等：txHelper 返 false（条件未命中）不计数；单订单异常不阻塞同批（AC5）</li>
 *   <li>天数走 sys_config（默认 7 / 自定义 5 / 非数字兜底 7）</li>
 *   <li>确认收货：in_china_dispatching → delivered happy / 非法态抛异常 / 越权抛异常（AC3/AC4）</li>
 * </ul>
 * 单订单「条件 UPDATE + 写审计」的幂等 / 乐观锁见 {@link GzLogisticsSignTxHelperTest}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzLogisticsSignServiceImplTest {

    private static final Long USER = 1001L;
    private static final Long OTHER_USER = 2002L;

    @Mock
    private GzOrdLogisticsMapper ordLogisticsMapper;
    @Mock
    private GzGachaLogisticsMapper gachaLogisticsMapper;
    @Mock
    private GzLogisticsSignTxHelper txHelper;
    @Mock
    private IGzUserService userService;
    @Mock
    private ConfigService configService;

    private GzLogisticsSignServiceImpl service;

    /** LambdaQueryWrapper.eq(Entity::getXxx) 需 entity TableInfo 缓存（不连库）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, GzOrdLogisticsRow.class);
        TableInfoHelper.initTableInfo(assistant, GzGachaLogisticsRow.class);
        // service 用 GzLogisticsOrderRow::getX 抽象基类方法引用构 wrapper，单测 getSqlSegment() 需基类 lambda 缓存
        TableInfoHelper.initTableInfo(assistant, GzLogisticsOrderRow.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzLogisticsSignServiceImpl(
            ordLogisticsMapper, gachaLogisticsMapper, txHelper, userService, configService);
    }

    private GzOrdLogisticsRow ordRow(String orderNo, String logisticsStatus, LocalDateTime dispatchedAt) {
        GzOrdLogisticsRow r = new GzOrdLogisticsRow();
        r.setId(System.nanoTime());
        r.setOrderNo(orderNo);
        r.setUserId(USER);
        r.setLogisticsStatus(logisticsStatus);
        r.setCnDispatchedAt(dispatchedAt);
        r.setVersion(0);
        return r;
    }

    private GzGachaLogisticsRow gachaRow(String orderNo, String logisticsStatus, LocalDateTime dispatchedAt) {
        GzGachaLogisticsRow r = new GzGachaLogisticsRow();
        r.setId(System.nanoTime());
        r.setOrderNo(orderNo);
        r.setUserId(USER);
        r.setLogisticsStatus(logisticsStatus);
        r.setCnDispatchedAt(dispatchedAt);
        r.setVersion(0);
        return r;
    }

    // ============================================================
    //  自动签收（AC2 / AC5 / AC7）
    // ============================================================

    @Test
    @DisplayName("(1) 自动签收扫两表：命中各 1 笔 → 委托 txHelper 各推进，计数 = 2")
    void autoSign_scansBothTables_signsCandidates() {
        when(configService.getConfigValue("gz.delivery.auto_sign_days")).thenReturn("7");
        when(ordLogisticsMapper.selectList(any())).thenReturn(
            List.of(ordRow("PREORD-20260601-000001", "in_china_dispatching", LocalDateTime.now().minusDays(8))));
        when(gachaLogisticsMapper.selectList(any())).thenReturn(
            List.of(gachaRow("GACHA-20260601-000001", "in_china_dispatching", LocalDateTime.now().minusDays(9))));
        when(txHelper.markDeliveredAndAudit(any(), any(), any(), any(), anyString(),
            anyString(), eq(GzLogisticsAudit.ACTION_AUTO_DELIVERED), anyString(), anyString())).thenReturn(true);

        int signed = service.autoSign();

        assertEquals(2, signed);
        // 两表都扫了
        verify(ordLogisticsMapper).selectList(any());
        verify(gachaLogisticsMapper).selectList(any());
        // 委托 txHelper 两次，operator = system / action = auto_delivered
        verify(txHelper, times(2)).markDeliveredAndAudit(any(), any(), any(), any(), anyString(),
            anyString(), eq(GzLogisticsAudit.ACTION_AUTO_DELIVERED),
            eq(GzLogisticsAudit.OPERATOR_TYPE_SYSTEM), eq(GzLogisticsAudit.OPERATOR_SYSTEM));
    }

    @Test
    @DisplayName("(2) 7 天边界：query wrapper 的 cn_dispatched_at <= 阈值 ≈ now - 7d（默认 7 天）")
    void autoSign_sevenDayBoundary_thresholdIsNowMinus7d() {
        when(configService.getConfigValue("gz.delivery.auto_sign_days")).thenReturn("7");
        when(ordLogisticsMapper.selectList(any())).thenReturn(List.of());
        when(gachaLogisticsMapper.selectList(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusDays(7);
        service.autoSign();
        LocalDateTime after = LocalDateTime.now().minusDays(7);

        // 捕获预购表查询 wrapper，反查 cn_dispatched_at <= 的阈值参数
        ArgumentCaptor<LambdaQueryWrapper<GzOrdLogisticsRow>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ordLogisticsMapper).selectList(cap.capture());
        LocalDateTime threshold = extractLeThreshold(cap.getValue());
        assertNotNull(threshold, "wrapper 应含 cn_dispatched_at <= 阈值");
        // 阈值落在 [now-7d 前, now-7d 后] 区间（执行耗时容差）
        assertFalse(threshold.isBefore(before.minusSeconds(5)), "阈值不应早于 now-7d-5s");
        assertFalse(threshold.isAfter(after.plusSeconds(5)), "阈值不应晚于 now-7d+5s");
        // 阈值距今约 7 天（>6.9d，<7.1d）
        long minutes = ChronoUnit.MINUTES.between(threshold, LocalDateTime.now());
        assertTrue(minutes >= 7 * 24 * 60 - 5 && minutes <= 7 * 24 * 60 + 5,
            "阈值距今应约 7 天，实际分钟数=" + minutes);
    }

    @Test
    @DisplayName("(3) 自动签收幂等：txHelper 返 false（条件未命中）不计入签收数")
    void autoSign_idempotent_notCountedWhenTxHelperFalse() {
        when(configService.getConfigValue(anyString())).thenReturn("7");
        when(ordLogisticsMapper.selectList(any())).thenReturn(
            List.of(ordRow("PREORD-20260601-000002", "in_china_dispatching", LocalDateTime.now().minusDays(10))));
        when(gachaLogisticsMapper.selectList(any())).thenReturn(List.of());
        // 条件未命中（已被并发签收）→ false
        when(txHelper.markDeliveredAndAudit(any(), any(), any(), any(), anyString(),
            anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        int signed = service.autoSign();
        assertEquals(0, signed, "未命中不计数（幂等，AC4）");
    }

    @Test
    @DisplayName("(4) 单订单异常不阻塞同批：第 1 笔抛异常，第 2 笔仍签（AC5）")
    void autoSign_oneFailDoesNotBlockBatch() {
        when(configService.getConfigValue(anyString())).thenReturn("7");
        GzOrdLogisticsRow bad = ordRow("PREORD-BAD", "in_china_dispatching", LocalDateTime.now().minusDays(8));
        GzOrdLogisticsRow good = ordRow("PREORD-GOOD", "in_china_dispatching", LocalDateTime.now().minusDays(8));
        when(ordLogisticsMapper.selectList(any())).thenReturn(List.of(bad, good));
        when(gachaLogisticsMapper.selectList(any())).thenReturn(List.of());
        when(txHelper.markDeliveredAndAudit(any(), any(), any(), any(), eq("PREORD-BAD"),
            anyString(), anyString(), anyString(), anyString())).thenThrow(new RuntimeException("DB error"));
        when(txHelper.markDeliveredAndAudit(any(), any(), any(), any(), eq("PREORD-GOOD"),
            anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        int signed = service.autoSign();
        assertEquals(1, signed, "坏单异常被吞，好单仍签（AC5）");
    }

    @Test
    @DisplayName("(5) 天数走 sys_config：自定义 5 天 → 阈值 ≈ now-5d；非数字 → 兜底 7 天")
    void autoSign_daysFromConfig() {
        // 自定义 5 天
        when(configService.getConfigValue("gz.delivery.auto_sign_days")).thenReturn("5");
        when(ordLogisticsMapper.selectList(any())).thenReturn(List.of());
        when(gachaLogisticsMapper.selectList(any())).thenReturn(List.of());
        service.autoSign();
        ArgumentCaptor<LambdaQueryWrapper<GzOrdLogisticsRow>> cap5 = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ordLogisticsMapper).selectList(cap5.capture());
        long min5 = ChronoUnit.MINUTES.between(extractLeThreshold(cap5.getValue()), LocalDateTime.now());
        assertTrue(min5 >= 5 * 24 * 60 - 5 && min5 <= 5 * 24 * 60 + 5, "5 天阈值，实际分钟=" + min5);

        // 非数字 → 兜底 7
        reset(ordLogisticsMapper, gachaLogisticsMapper);
        when(configService.getConfigValue("gz.delivery.auto_sign_days")).thenReturn("abc");
        when(ordLogisticsMapper.selectList(any())).thenReturn(List.of());
        when(gachaLogisticsMapper.selectList(any())).thenReturn(List.of());
        service.autoSign();
        ArgumentCaptor<LambdaQueryWrapper<GzOrdLogisticsRow>> cap7 = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ordLogisticsMapper).selectList(cap7.capture());
        long min7 = ChronoUnit.MINUTES.between(extractLeThreshold(cap7.getValue()), LocalDateTime.now());
        assertTrue(min7 >= 7 * 24 * 60 - 5 && min7 <= 7 * 24 * 60 + 5, "非数字兜底 7 天，实际分钟=" + min7);
    }

    // ============================================================
    //  确认收货（AC3 / AC4）
    // ============================================================

    @Test
    @DisplayName("(6) 确认收货 happy：in_china_dispatching → 委托 txHelper user_confirmed + user_no")
    void confirmReceive_happy() {
        GzOrdLogisticsRow row = ordRow("PREORD-20260601-000010", "in_china_dispatching", LocalDateTime.now().minusDays(2));
        when(ordLogisticsMapper.selectOne(any())).thenReturn(row);
        GzUserVO vo = new GzUserVO();
        vo.setUserNo("U20260601000001");
        when(userService.selectVoById(USER)).thenReturn(vo);
        when(txHelper.markDeliveredAndAudit(any(), eq(GzOrdLogisticsRow.class), eq(row.getId()), eq(0),
            eq("PREORD-20260601-000010"), eq(GzLogisticsAudit.BIZ_PREORDER),
            eq(GzLogisticsAudit.ACTION_USER_CONFIRMED), eq(GzLogisticsAudit.OPERATOR_TYPE_USER),
            eq("U20260601000001"))).thenReturn(true);

        assertDoesNotThrow(() -> service.confirmReceive("PREORD-20260601-000010", USER));

        verify(txHelper).markDeliveredAndAudit(any(), eq(GzOrdLogisticsRow.class), eq(row.getId()), eq(0),
            eq("PREORD-20260601-000010"), eq(GzLogisticsAudit.BIZ_PREORDER),
            eq(GzLogisticsAudit.ACTION_USER_CONFIRMED), eq(GzLogisticsAudit.OPERATOR_TYPE_USER),
            eq("U20260601000001"));
    }

    @Test
    @DisplayName("(7) 确认收货状态非法：in_japan → 抛业务异常，不委托 txHelper")
    void confirmReceive_illegalStatus_throws() {
        GzOrdLogisticsRow row = ordRow("PREORD-20260601-000011", "in_japan", null);
        when(ordLogisticsMapper.selectOne(any())).thenReturn(row);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.confirmReceive("PREORD-20260601-000011", USER));
        assertEquals("订单状态不允许此操作", ex.getMessage());
        verify(txHelper, never()).markDeliveredAndAudit(any(), any(), any(), any(), anyString(),
            anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("(8) 确认收货已 delivered：再点 → 抛业务异常（幂等不重写，AC4）")
    void confirmReceive_alreadyDelivered_throws() {
        GzOrdLogisticsRow row = ordRow("PREORD-20260601-000012", "delivered", LocalDateTime.now().minusDays(3));
        when(ordLogisticsMapper.selectOne(any())).thenReturn(row);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.confirmReceive("PREORD-20260601-000012", USER));
        assertEquals("订单状态不允许此操作", ex.getMessage());
        verify(txHelper, never()).markDeliveredAndAudit(any(), any(), any(), any(), anyString(),
            anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("(9) 确认收货越权：他人订单 → 抛「无权操作」，不委托 txHelper")
    void confirmReceive_crossUser_throws() {
        GzOrdLogisticsRow row = ordRow("PREORD-20260601-000013", "in_china_dispatching", LocalDateTime.now().minusDays(2));
        row.setUserId(OTHER_USER); // 订单属他人
        when(ordLogisticsMapper.selectOne(any())).thenReturn(row);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.confirmReceive("PREORD-20260601-000013", USER));
        assertEquals("无权操作该订单", ex.getMessage());
        verify(txHelper, never()).markDeliveredAndAudit(any(), any(), any(), any(), anyString(),
            anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("(10) 确认收货非法前缀 / 不存在：抛「无权操作」")
    void confirmReceive_illegalPrefixOrNotFound_throws() {
        // 非法前缀
        ServiceException ex1 = assertThrows(ServiceException.class,
            () -> service.confirmReceive("TEST-XXX", USER));
        assertEquals("无权操作该订单", ex1.getMessage());

        // 合法前缀但查无
        when(gachaLogisticsMapper.selectOne(any())).thenReturn(null);
        ServiceException ex2 = assertThrows(ServiceException.class,
            () -> service.confirmReceive("GACHA-20260601-999999", USER));
        assertEquals("无权操作该订单", ex2.getMessage());
    }

    // ============================================================
    //  helper：从 LambdaQueryWrapper 提取 cn_dispatched_at <= 的阈值参数
    // ============================================================

    private LocalDateTime extractLeThreshold(LambdaQueryWrapper<?> wrapper) {
        // mybatis-plus 的 paramNameValuePairs 在 getSqlSegment() 拼 SQL 时才填充，先触发一次
        wrapper.getSqlSegment();
        for (Object v : wrapper.getParamNameValuePairs().values()) {
            if (v instanceof LocalDateTime ldt) {
                return ldt;
            }
        }
        return null;
    }
}
