package org.dromara.gz.jp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.jp.domain.entity.GzJpEvent;
import org.dromara.gz.jp.domain.enums.GzJpEventStatus;
import org.dromara.gz.jp.domain.vo.GzJpEventMpVO;
import org.dromara.gz.jp.mapper.GzJpEventMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 场状态机 + mp 可见性单测（GZ-JP-101，accept STATE「关场后场不再出现在 mp 可下单列表」）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@link GzJpEventStatus#effective} 读时惰性判定：end_time 到点即 closed，不依赖 cron</li>
 *   <li>关场（close）后 mp 详情 / isBookable 立刻不可见</li>
 *   <li>mp 列表查询条件确实带 {@code status='open' AND end_time > now}（关场 / 到点的场进不来）</li>
 *   <li>draft 场 mp 不可见（AC3：场未 open 时 mp 侧查不到）</li>
 *   <li>开场 / 关场的前置校验与幂等提示</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzJpEventStatusTest {

    private static final Long EVENT_ID = 3001L;
    private static final Long COVER_FILE_ID = 777L;
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    @Mock
    private GzJpEventMapper baseMapper;

    @Mock
    private IGzFileService fileService;

    private GzJpEventServiceImpl service;

    /**
     * 纯 mock 单测下 LambdaQueryWrapper.eq(GzJpEvent::getXxx) 在 getSqlSegment() 时需要
     * mybatis-plus 的 TableInfo 缓存，否则报 "can not find lambda cache for this entity"。
     *
     * <p>★ 必须用 {@link MybatisConfiguration} 而非裸 {@code org.apache.ibatis.session.Configuration}：
     * 裸 Configuration 拿不到 MP 全局 DbConfig，驼峰字段不会转下划线列名，
     * getSqlSegment() 会吐出 {@code endTime} 而不是 {@code end_time}，断言就成了假的。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzJpEvent.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzJpEventServiceImpl(baseMapper, fileService);
    }

    private GzJpEvent event(String status, LocalDateTime endTime) {
        GzJpEvent e = new GzJpEvent();
        e.setId(EVENT_ID);
        e.setEventNo("EVT-20260807-000001");
        e.setName("8月上旬快闪");
        e.setStartTime(endTime.minusDays(7));
        e.setEndTime(endTime);
        e.setStatus(status);
        e.setSortNo(0);
        e.setVersion(0);
        e.setCoverImageId(COVER_FILE_ID);
        return e;
    }

    // ============================================================
    //  读时惰性判定（FLOW:F-JP-01.step4，不依赖 cron）
    // ============================================================

    @Test
    @DisplayName("effective: end_time 未到 → 保持存库状态；到点 → 一律 closed（不写库）")
    void effectiveLazyByEndTime() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 12, 0);
        LocalDateTime future = now.plusHours(1);
        LocalDateTime past = now.minusSeconds(1);

        assertEquals(GzJpEventStatus.DRAFT, GzJpEventStatus.effective("draft", future, now));
        assertEquals(GzJpEventStatus.OPEN, GzJpEventStatus.effective("open", future, now));
        assertEquals(GzJpEventStatus.CLOSED, GzJpEventStatus.effective("closed", future, now));

        // 到点 / 已过 → closed（含 draft，窗口已过的场不该再被开场或展示）
        assertEquals(GzJpEventStatus.CLOSED, GzJpEventStatus.effective("open", past, now));
        assertEquals(GzJpEventStatus.CLOSED, GzJpEventStatus.effective("open", now, now), "endTime == now 即已结束");
        assertEquals(GzJpEventStatus.CLOSED, GzJpEventStatus.effective("draft", past, now));
    }

    @Test
    @DisplayName("isBookableForMp: 仅「存库 open 且未到点」为 true")
    void bookableOnlyWhenEffectiveOpen() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 12, 0);
        assertTrue(GzJpEventStatus.isBookableForMp("open", now.plusHours(1), now));
        assertFalse(GzJpEventStatus.isBookableForMp("draft", now.plusHours(1), now));
        assertFalse(GzJpEventStatus.isBookableForMp("closed", now.plusHours(1), now));
        assertFalse(GzJpEventStatus.isBookableForMp("open", now.minusHours(1), now));
    }

    // ============================================================
    //  accept STATE：关场后场不再出现在 mp 可下单列表
    // ============================================================

    @Test
    @DisplayName("accept: 开场 → mp 可见；关场后 → mp 详情不可见 + isBookable=false")
    void closedEventDisappearsFromMp() {
        LocalDateTime end = LocalDateTime.now().plusDays(3);

        // 1) 开场中：mp 详情可见，isBookable=true（封面文件已删 → 回落占位图，不阻断）
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("open", end));
        when(fileService.getPresignedUrl(COVER_FILE_ID)).thenThrow(new RuntimeException("file not found"));
        GzJpEventMpVO opened = service.selectMpDetail(EVENT_ID);
        assertNotNull(opened, "开场中的场 mp 必须能查到");
        assertEquals("EVT-20260807-000001", opened.getEventNo());
        assertEquals(PLACEHOLDER_IMAGE_URL, opened.getCoverImageUrl(), "封面解析失败必须回落占位图");
        assertTrue(service.isBookable(EVENT_ID));

        // 2) 店员关场（FLOW:F-JP-01.step4）
        GzJpEvent openEntity = event("open", end);
        when(baseMapper.selectById(EVENT_ID)).thenReturn(openEntity);
        when(baseMapper.updateById(any(GzJpEvent.class))).thenReturn(1);
        assertTrue(service.close(EVENT_ID));

        ArgumentCaptor<GzJpEvent> captor = ArgumentCaptor.forClass(GzJpEvent.class);
        verify(baseMapper, times(1)).updateById(captor.capture());
        assertEquals("closed", captor.getValue().getStatus(), "关场必须把 status 写成 closed");

        // 3) 关场后：mp 详情查不到，isBookable=false
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("closed", end));
        assertNull(service.selectMpDetail(EVENT_ID), "关场后 mp 详情必须查不到");
        assertFalse(service.isBookable(EVENT_ID), "关场后不可下单");
    }

    @Test
    @DisplayName("accept: mp 可下单列表的 SQL 条件带 status=open + end_time > now（关场/到点场天然进不来）")
    void mpListQueryFiltersClosedAndExpired() {
        Page<GzJpEvent> empty = new Page<>(1, 10, 0);
        empty.setRecords(List.of());
        when(baseMapper.selectPage(any(), any())).thenReturn(empty);

        TableDataInfo<GzJpEventMpVO> page = service.selectMpPage(new PageQuery(1, 10));
        assertTrue(page.getRows().isEmpty());
        assertEquals(0L, page.getTotal());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<GzJpEvent>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<GzJpEvent> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status"), "必须按 status 过滤，实际：" + sql);
        assertTrue(sql.contains("end_time"), "必须按 end_time 做读时惰性过滤，实际：" + sql);
        assertTrue(sql.contains(">"), "end_time 必须是「大于当前时间」，实际：" + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(GzJpEventStatus.OPEN.getCode()),
            "status 参数必须绑定 open，实际：" + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("契约: mp VO 只下发 open —— 回归包断言「不下发 draft」的锚点")
    void mpVoAlwaysReportsOpen() {
        Page<GzJpEvent> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(event("open", LocalDateTime.now().plusDays(1))));
        when(baseMapper.selectPage(any(), any())).thenReturn(page);
        when(fileService.getPresignedUrl(COVER_FILE_ID)).thenThrow(new RuntimeException("file not found"));

        List<GzJpEventMpVO> rows = service.selectMpPage(new PageQuery(1, 10)).getRows();
        assertEquals(1, rows.size());
        assertEquals(GzJpEventStatus.OPEN.getCode(), rows.get(0).getStatus());
    }

    @Test
    @DisplayName("AC3: 未开场（draft）的场 mp 侧查不到")
    void draftEventInvisibleToMp() {
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("draft", LocalDateTime.now().plusDays(3)));
        assertNull(service.selectMpDetail(EVENT_ID));
        assertFalse(service.isBookable(EVENT_ID));
    }

    @Test
    @DisplayName("到 end_time 后（存库仍是 open）mp 侧同样查不到 —— 惰性判定，无需 cron")
    void expiredOpenEventInvisibleToMp() {
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("open", LocalDateTime.now().minusMinutes(1)));
        assertNull(service.selectMpDetail(EVENT_ID));
        assertFalse(service.isBookable(EVENT_ID));
    }

    // ============================================================
    //  开场 / 关场前置校验
    // ============================================================

    @Test
    @DisplayName("open: draft → open 成功，status 写 open")
    void openDraftEvent() {
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("draft", LocalDateTime.now().plusDays(1)));
        when(baseMapper.updateById(any(GzJpEvent.class))).thenReturn(1);

        assertTrue(service.open(EVENT_ID));

        ArgumentCaptor<GzJpEvent> captor = ArgumentCaptor.forClass(GzJpEvent.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("open", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("open: 闭场时间已过 → 拒绝开场（窗口已过的场无从开起）")
    void openRejectedWhenWindowPassed() {
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("draft", LocalDateTime.now().minusMinutes(1)));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.open(EVENT_ID));
        assertTrue(ex.getMessage().contains("闭场时间已过"), ex.getMessage());
        verify(baseMapper, times(0)).updateById(any(GzJpEvent.class));
    }

    @Test
    @DisplayName("open: 已在进行中 → 拒绝重复开场")
    void openRejectedWhenAlreadyOpen() {
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("open", LocalDateTime.now().plusDays(1)));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.open(EVENT_ID));
        assertTrue(ex.getMessage().contains("已在进行中"), ex.getMessage());
    }

    @Test
    @DisplayName("open: 误关场后（closed 但窗口未过）允许重新开场 —— 店员唯一补救路径")
    void reopenMistakenlyClosedEvent() {
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("closed", LocalDateTime.now().plusDays(1)));
        when(baseMapper.updateById(any(GzJpEvent.class))).thenReturn(1);

        assertTrue(service.open(EVENT_ID));

        ArgumentCaptor<GzJpEvent> captor = ArgumentCaptor.forClass(GzJpEvent.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("open", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("close: 已关闭 → 拒绝重复关场")
    void closeRejectedWhenAlreadyClosed() {
        when(baseMapper.selectById(EVENT_ID)).thenReturn(event("closed", LocalDateTime.now().plusDays(1)));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.close(EVENT_ID));
        assertTrue(ex.getMessage().contains("已关闭"), ex.getMessage());
    }

    @Test
    @DisplayName("close: 场不存在 → ServiceException")
    void closeRejectedWhenEventMissing() {
        when(baseMapper.selectById(EVENT_ID)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.close(EVENT_ID));
        assertTrue(ex.getMessage().contains("场不存在"), ex.getMessage());
    }

    @Test
    @DisplayName("delete: 进行中的场不能删（先关场）")
    void deleteRejectedWhenEventOpen() {
        when(baseMapper.selectByIds(List.of(EVENT_ID)))
            .thenReturn(List.of(event("open", LocalDateTime.now().plusDays(1))));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteByIds(List.of(EVENT_ID)));
        assertTrue(ex.getMessage().contains("请先关场"), ex.getMessage());
    }
}
