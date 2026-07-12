package org.dromara.gz.recycle.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayPayoutService;
import org.dromara.gz.recycle.config.GzRecycleQrProperties;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.dromara.gz.recycle.service.IGzRecycleTimeSlotService;
import org.dromara.gz.recycle.service.internal.RecycleApptNoGenerator;
import org.dromara.gz.recycle.service.internal.RecycleQrSigner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecycleAppointmentServiceImpl#listStaffByDate} 单测（mp 店员当天回收核对列表）。
 *
 * <p>覆盖：① happy —— 当天多单，复用 toAdminVO 组装 + 保序返回 + 查询按 appt_date 过滤且 ORDER BY
 * slot_start IS NULL, slot_start ASC, id ASC（null 排最后 + 升序）；② empty —— 当天无单返空 list；
 * ③ date=null 短路返空、不打 mapper（不全表扫）。</p>
 *
 * <p>ordering 真值靠 Tier 1B 集成打真 DB 验；单测在 wrapper 层断言 ORDER BY 子句正确构建 +
 * service 保序透传 mapper 返回。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE staff list)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleStaffListTest {

    @Mock
    private GzRecycleAppointmentMapper baseMapper;
    @Mock
    private GzUserMapper gzUserMapper;
    @Mock
    private RecycleApptNoGenerator apptNoGenerator;
    @Mock
    private IGzRecycleQtyRangeService qtyRangeService;
    @Mock
    private IGzRecycleTimeSlotService timeSlotService;
    @Mock
    private IGzPayPayoutService payoutService;
    @Mock
    private GzPayPayoutTransactionMapper payoutMapper;
    @Mock
    private org.dromara.common.core.service.ConfigService configService;

    private GzRecycleAppointmentServiceImpl service;

    /**
     * 初始化 GzRecycleAppointment 的 MyBatis-Plus lambda 列缓存（幂等、无副作用）。
     *
     * <p>本测试断言处调 {@code wrapper.getSqlSegment()} 会急切把 lambda(getApptDate) 解析为列名，需 TableInfo 缓存；
     * 纯 Mockito 单测无 Spring 容器故缓存空 → 报 "can not find lambda cache"。此处显式 init 补上（仅 init 用到的实体）。</p>
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
            new MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
            GzRecycleAppointment.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzRecycleAppointmentServiceImpl(
            baseMapper, gzUserMapper, apptNoGenerator, qtyRangeService, timeSlotService,
            new RecycleQrSigner(new GzRecycleQrProperties()), new ObjectMapper(),
            payoutService, payoutMapper, configService);
    }

    /** 构造一条给定 id / 编号 / 状态 / slotStart 的预约单（product 空对象，out_payout_no 空 → 不拉转账段）。 */
    private GzRecycleAppointment appt(Long id, String no, String status, LocalTime slotStart) {
        return GzRecycleAppointment.builder()
            .id(id)
            .appointmentNo(no)
            .userId(2000L + id)
            .storeId(1L)
            .productSnapshotJson("{\"categories\":[\"card\"]}")
            .apptDate(LocalDate.of(2026, 7, 12))
            .slotStart(slotStart)
            .slotEnd(slotStart == null ? null : slotStart.plusHours(1))
            .status(status)
            .version(0)
            .delFlag("0")
            .build();
    }

    @Test
    @DisplayName("happy：当天多单 → 复用 toAdminVO 组装 + 保序返回；查询按 appt_date 过滤 + ORDER BY slot_start(null last)/id")
    void listStaffByDate_multipleAppts_assemblesAndPreservesOrder() {
        LocalDate date = LocalDate.of(2026, 7, 12);
        // mapper 返回已按 SQL 排序结果（10:00 → 14:00 → slot_start null 排最后）；service 保序透传。
        GzRecycleAppointment a1 = appt(101L, "RCY-20260712-000001", "submitted", LocalTime.of(10, 0));
        GzRecycleAppointment a2 = appt(102L, "RCY-20260712-000002", "confirmed_onsite", LocalTime.of(14, 0));
        GzRecycleAppointment a3 = appt(103L, "RCY-20260712-000003", "no_show", null);
        when(baseMapper.selectStoreNameById(1L)).thenReturn("成都太古里店");
        when(baseMapper.selectList(any())).thenReturn(List.of(a1, a2, a3));

        List<GzRecycleAppointmentAdminVO> result = service.listStaffByDate(date);

        // 组装 + 保序：3 条，appointmentNo / status / slotStart 与入参一一对应，storeName join 生效
        assertEquals(3, result.size());
        assertEquals(List.of("RCY-20260712-000001", "RCY-20260712-000002", "RCY-20260712-000003"),
            result.stream().map(GzRecycleAppointmentAdminVO::getAppointmentNo).toList());
        assertEquals(List.of("submitted", "confirmed_onsite", "no_show"),
            result.stream().map(GzRecycleAppointmentAdminVO::getStatus).toList());
        assertEquals("成都太古里店", result.get(0).getStoreName());
        assertEquals(LocalTime.of(10, 0), result.get(0).getSlotStart());
        // slot_start null 单排最后，slotStart 为 null
        assertEquals(null, result.get(2).getSlotStart());

        // 查询层：appt_date 过滤 + ORDER BY slot_start IS NULL, slot_start ASC, id ASC（null 排最后）
        ArgumentCaptor<LambdaQueryWrapper<GzRecycleAppointment>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("ORDER BY slot_start IS NULL, slot_start ASC, id ASC"),
            "应按 slot_start 升序 null last 再 id 升序，实际 sqlSegment=" + sql);
        assertTrue(sql.contains("appt_date"), "应含 appt_date 过滤条件，实际 sqlSegment=" + sql);
    }

    @Test
    @DisplayName("empty：当天无单 → 返回空 list（mapper 被调，无 NPE）")
    void listStaffByDate_noAppts_returnsEmpty() {
        LocalDate date = LocalDate.of(2026, 7, 13);
        when(baseMapper.selectList(any())).thenReturn(List.of());

        List<GzRecycleAppointmentAdminVO> result = service.listStaffByDate(date);

        assertTrue(result.isEmpty());
        verify(baseMapper).selectList(any());
    }

    @Test
    @DisplayName("date=null → 短路返回空 list、不打 mapper（不全表扫）")
    void listStaffByDate_nullDate_shortCircuits() {
        List<GzRecycleAppointmentAdminVO> result = service.listStaffByDate(null);

        assertTrue(result.isEmpty());
        verify(baseMapper, never()).selectList(any());
    }
}
