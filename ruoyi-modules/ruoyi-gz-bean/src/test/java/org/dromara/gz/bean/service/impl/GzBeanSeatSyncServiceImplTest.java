package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.vo.GzBeanSeatSyncResultVO;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 座位单元「同步 + 移除守卫」单测（GZ-BEAN-055）。
 *
 * <p><b>背景</b>：桌型配置的 {@code quantity × capacity} 是<b>小程序售卖配额分母</b>，
 * 而看板计时格 / 核销可分座池是 {@code gz_bean_seat} 的<b>实际行数</b>。这两个数只在手点
 * 「批量生成」那一刻对齐过；改数量不动座位表、批量生成又只增不减 → 会朝两个方向漂：
 * <b>配额多</b> = 卖得出但核销时没座可分（客人已付款）；<b>座位多</b> = 看板格子线上永远卖不掉。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>移除守卫：挂着活跃单的座位不许删 / 不许停用（这是<b>事故级</b>——看板遍历座位，
 *       孤儿单被静默丢弃，那笔已付款单会从看板上彻底消失）</li>
 *   <li>同步补齐：数量调大后按<b>已有编号前缀</b>接着编（前缀推错会造出凑不成桌的孤立编号）</li>
 *   <li>同步缩减：多余的按顺序移除；<b>挂着预约的一律保留</b>并回报编号</li>
 *   <li>前缀反推：≥2 个取最长公共前缀 / 恰好 1 个去掉末尾的 "1" / 一个都没有回退桌型派生</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-055)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GzBeanSeatSyncServiceImplTest {

    @Mock
    private GzBeanSeatMapper baseMapper;
    @Mock
    private GzBeanSeatTypeConfigMapper configMapper;
    @Mock
    private GzBeanBookingMapper bookingMapper;

    private GzBeanSeatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanSeatServiceImpl(baseMapper, configMapper, bookingMapper);
    }

    // ============ fixtures ============

    private GzBeanSeatTypeConfig config(String bookMode, int capacity, int quantity) {
        GzBeanSeatTypeConfig c = new GzBeanSeatTypeConfig();
        c.setId(7L);
        c.setStoreId(1L);
        c.setSeatType("st7");
        c.setName("仅后台临时桌");
        c.setBookMode(bookMode);
        c.setCapacity(capacity);
        c.setQuantity(quantity);
        return c;
    }

    private GzBeanSeat seat(Long id, String seatNo, String tableNo, int sortNo) {
        GzBeanSeat s = new GzBeanSeat();
        s.setId(id);
        s.setTenantId("1001");
        s.setStoreId(1L);
        s.setSeatTypeConfigId(7L);
        s.setSeatNo(seatNo);
        s.setTableNo(tableNo);
        s.setSortNo(sortNo);
        s.setEnabled(1);
        s.setDelFlag("0"); // DB 该列 NOT NULL DEFAULT '0'，fixture 也照实设，别让测试跑在生产不会出现的状态上
        return s;
    }

    /**
     * 按 seat_no 命中已有座位 —— {@code upsertSeatUnit} 靠它判断「这个编号是不是已经有了」。
     *
     * <p>不 stub 它（恒返回 null）会让幂等跳过分支永不触发，已存在的座位被重复 insert，
     * 于是「只补缺的那几个」这件事根本没被验证到。</p>
     */
    private void stubSeatNoLookup(List<GzBeanSeat> existing) {
        when(baseMapper.selectRawBySeatNo(anyLong(), anyString()))
            .thenAnswer(inv -> existing.stream()
                .filter(x -> x.getSeatNo().equals(inv.getArgument(1)))
                .findFirst().orElse(null));
    }

    /** 让 selectList 依次返回「同步前 / 缩减判定 / 同步后」三批（syncSeatUnits 内部查三次） */
    @SuppressWarnings("unchecked")
    private void stubUnitSnapshots(List<GzBeanSeat> before, List<GzBeanSeat> mid, List<GzBeanSeat> after) {
        when(baseMapper.selectList(any(Wrapper.class))).thenReturn(before, mid, after);
    }

    // ============ 移除守卫（坑 1：事故级） ============

    @Test
    @DisplayName("删除座位：仍挂今天及以后的活跃单 → 拒绝，并把座位编号写进报错（删了那单会从看板上消失）")
    void deleteByIds_blockedWhenSeatStillHoldsActiveBooking() {
        GzBeanSeat s1 = seat(101L, "Q3-1", "Q3", 31);
        when(baseMapper.selectByIds(anyCollection())).thenReturn(List.of(s1));
        when(bookingMapper.selectSeatIdsWithActiveBookings(eq("1001"), anyCollection(), any(LocalDate.class)))
            .thenReturn(List.of(101L));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteByIds(List.of(101L)));

        assertTrue(ex.getMessage().contains("Q3-1"), "报错必须点名是哪个座位，否则店员不知道去看板改派哪一单");
        verify(baseMapper, never()).deleteByIds(anyCollection());
    }

    @Test
    @DisplayName("删除座位：没有活跃单 → 正常软删")
    void deleteByIds_passesWhenNoActiveBooking() {
        when(baseMapper.selectByIds(anyCollection())).thenReturn(List.of(seat(101L, "Q3-1", "Q3", 31)));
        when(bookingMapper.selectSeatIdsWithActiveBookings(anyString(), anyCollection(), any(LocalDate.class)))
            .thenReturn(List.of());
        when(baseMapper.deleteByIds(anyCollection())).thenReturn(1);

        assertTrue(service.deleteByIds(List.of(101L)));
        verify(baseMapper).deleteByIds(anyCollection());
    }

    @Test
    @DisplayName("停用座位：与删除等价（看板同样看不见）→ 挂着活跃单时也必须拒绝")
    void toggleEnabled_off_blockedWhenSeatStillHoldsActiveBooking() {
        when(baseMapper.selectByIds(anyCollection())).thenReturn(List.of(seat(101L, "Q3-1", "Q3", 31)));
        when(bookingMapper.selectSeatIdsWithActiveBookings(anyString(), anyCollection(), any(LocalDate.class)))
            .thenReturn(List.of(101L));

        assertThrows(ServiceException.class, () -> service.toggleEnabled(101L, 0));
        verify(baseMapper, never()).updateById(any(GzBeanSeat.class));
    }

    @Test
    @DisplayName("启用座位（enabled=1）不过闸 —— 加座位不会让任何单消失，拦它只会挡住恢复操作")
    void toggleEnabled_on_skipsGuard() {
        when(baseMapper.updateById(any(GzBeanSeat.class))).thenReturn(1);

        assertTrue(service.toggleEnabled(101L, 1));
        verify(bookingMapper, never()).selectSeatIdsWithActiveBookings(anyString(), anyCollection(), any());
    }

    // ============ 同步：补齐（症状①「改了数量看板没多格子」） ============

    @Test
    @DisplayName("按座桌型数量 1→2：按已有前缀 T1 补出第二桌 T12-1..T12-4，不另起一组编号")
    void sync_topUp_reusesExistingPrefix() {
        // 已有 T11-1..T11-4（前缀 T1、第 1 桌），配置现在是 2 桌 × 4 座 = 8
        List<GzBeanSeat> existing = new ArrayList<>(List.of(
            seat(1L, "T11-1", "T11", 11), seat(2L, "T11-2", "T11", 12),
            seat(3L, "T11-3", "T11", 13), seat(4L, "T11-4", "T11", 14)));
        when(configMapper.selectById(7L)).thenReturn(config("seat", 4, 2));
        stubUnitSnapshots(existing, existing, existing);
        stubSeatNoLookup(existing);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        GzBeanSeatSyncResultVO r = service.syncSeatUnits(7L);

        assertEquals("T1", r.getPrefix(), "前缀必须从已有座位反推 —— 推错会造出跟原来凑不成桌的孤立编号");
        assertEquals(8, r.getExpected());
        ArgumentCaptor<GzBeanSeat> cap = ArgumentCaptor.forClass(GzBeanSeat.class);
        verify(baseMapper, times(4)).insert(cap.capture());
        assertEquals(List.of("T12-1", "T12-2", "T12-3", "T12-4"),
            cap.getAllValues().stream().map(GzBeanSeat::getSeatNo).toList());
    }

    @Test
    @DisplayName("整桌桌型：前缀从 seat_no 反推（whole 模式没有 table_no）")
    void sync_topUp_wholeModeDerivesPrefixFromSeatNo() {
        List<GzBeanSeat> existing = new ArrayList<>(List.of(
            seat(1L, "S1", null, 1), seat(2L, "S2", null, 2)));
        when(configMapper.selectById(7L)).thenReturn(config("whole", 1, 3));
        stubUnitSnapshots(existing, existing, existing);
        stubSeatNoLookup(existing);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        GzBeanSeatSyncResultVO r = service.syncSeatUnits(7L);

        assertEquals("S", r.getPrefix());
        assertEquals(3, r.getExpected());
        ArgumentCaptor<GzBeanSeat> cap = ArgumentCaptor.forClass(GzBeanSeat.class);
        verify(baseMapper, times(1)).insert(cap.capture());
        assertEquals("S3", cap.getValue().getSeatNo());
    }

    @Test
    @DisplayName("只剩 1 个单位时前缀 = 去掉末尾的 1（T11→T1；否则 LCP 会把它整个当前缀）")
    void sync_prefixFromSingleUnit() {
        List<GzBeanSeat> existing = new ArrayList<>(List.of(seat(1L, "T11-1", "T11", 11)));
        when(configMapper.selectById(7L)).thenReturn(config("seat", 1, 2));
        stubUnitSnapshots(existing, existing, existing);
        stubSeatNoLookup(existing);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        assertEquals("T1", service.syncSeatUnits(7L).getPrefix());
    }

    @Test
    @DisplayName("一个座位都没有 → 回退桌型派生前缀（seat_type 首字母），不报错")
    void sync_prefixFallbackWhenNoUnits() {
        when(configMapper.selectById(7L)).thenReturn(config("seat", 2, 1));
        stubUnitSnapshots(List.of(), List.of(), List.of());
        when(baseMapper.selectRawBySeatNo(anyLong(), anyString())).thenReturn(null);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        assertEquals("S", service.syncSeatUnits(7L).getPrefix(), "seat_type=st7 → 首字母 S");
    }

    // ============ 同步：缩减（症状②「数量 2 却有 12 格」） ============

    @Test
    @DisplayName("按座桌型数量 3→2：多出的第 3 桌 4 个座被移除（批量生成只增不减留下的历史）")
    void sync_prune_removesSurplusUnits() {
        List<GzBeanSeat> twelve = new ArrayList<>();
        for (int t = 1; t <= 3; t++) {
            for (int s = 1; s <= 4; s++) {
                twelve.add(seat((long) (t * 10 + s), "Q" + t + "-" + s, "Q" + t, t * 10 + s));
            }
        }
        when(configMapper.selectById(7L)).thenReturn(config("seat", 4, 2));
        stubUnitSnapshots(twelve, twelve, twelve.subList(0, 8));
        when(baseMapper.selectRawBySeatNo(anyLong(), anyString()))
            .thenAnswer(inv -> twelve.stream()
                .filter(x -> x.getSeatNo().equals(inv.getArgument(1)))
                .findFirst().orElse(null));
        when(bookingMapper.selectSeatIdsWithActiveBookings(anyString(), anyCollection(), any(LocalDate.class)))
            .thenReturn(List.of());

        GzBeanSeatSyncResultVO r = service.syncSeatUnits(7L);

        assertEquals(8, r.getExpected());
        assertEquals(4, r.getPruned());
        assertEquals(List.of("Q3-1", "Q3-2", "Q3-3", "Q3-4"), r.getPrunedSeatNos());
        assertTrue(r.getBlockedSeatNos().isEmpty());
        verify(baseMapper).deleteByIds(anyCollection());
    }

    @Test
    @DisplayName("★ 多余座位挂着预约 → 保留不删，只回报编号（删了那笔已付款单会从看板上彻底消失）")
    void sync_prune_keepsSurplusSeatsThatStillHoldBookings() {
        List<GzBeanSeat> nine = new ArrayList<>();
        for (int n = 1; n <= 9; n++) {
            nine.add(seat((long) n, "S" + n, null, n));
        }
        when(configMapper.selectById(7L)).thenReturn(config("whole", 1, 7));
        stubUnitSnapshots(nine, nine, nine);
        when(baseMapper.selectRawBySeatNo(anyLong(), anyString()))
            .thenAnswer(inv -> nine.stream()
                .filter(x -> x.getSeatNo().equals(inv.getArgument(1)))
                .findFirst().orElse(null));
        // 多余的是 S8 / S9，其中 S8(id=8) 还挂着单
        when(bookingMapper.selectSeatIdsWithActiveBookings(anyString(), anyCollection(), any(LocalDate.class)))
            .thenReturn(List.of(8L));

        GzBeanSeatSyncResultVO r = service.syncSeatUnits(7L);

        assertEquals(List.of("S8"), r.getBlockedSeatNos(), "挂着单的座位必须留着，且要点名让店员去改派");
        assertEquals(List.of("S9"), r.getPrunedSeatNos(), "同批里没挂单的照常移除，不因为一个受阻就整批放弃");
        assertEquals(1, r.getPruned());
        ArgumentCaptor<Collection<Long>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(baseMapper).deleteByIds(cap.capture());
        assertEquals(List.of(9L), List.copyOf(cap.getValue()));
    }

    @Test
    @DisplayName("数量刚好对上 → 不删不建，pruned/created 都是 0")
    void sync_noopWhenAlreadyAligned() {
        List<GzBeanSeat> two = new ArrayList<>(List.of(seat(1L, "S1", null, 1), seat(2L, "S2", null, 2)));
        when(configMapper.selectById(7L)).thenReturn(config("whole", 1, 2));
        stubUnitSnapshots(two, two, two);
        when(baseMapper.selectRawBySeatNo(anyLong(), anyString()))
            .thenAnswer(inv -> two.stream()
                .filter(x -> x.getSeatNo().equals(inv.getArgument(1)))
                .findFirst().orElse(null));

        GzBeanSeatSyncResultVO r = service.syncSeatUnits(7L);

        assertEquals(0, r.getCreated());
        assertEquals(0, r.getPruned());
        verify(baseMapper, never()).insert(any(GzBeanSeat.class));
        verify(baseMapper, never()).deleteByIds(anyCollection());
    }

    @Test
    @DisplayName("桌型不存在 → 抛异常，不要静默返回一个全 0 的结果让人以为同步过了")
    void sync_throwsWhenConfigMissing() {
        when(configMapper.selectById(7L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.syncSeatUnits(7L));
    }

    // ============ 软删座复活（曾经永久失效的分支） ============

    /**
     * 回归：软删座位必须能被复活，且判定不能依赖某个写死的删除值。
     *
     * <p><b>踩过的坑</b>：代码原本判 {@code del_flag.equals("2")}，而全局
     * {@code logicDeleteValue} 其实是 <b>1</b> —— 这条复活分支<b>从来没触发过</b>。
     * 而 {@code uk_tenant_store_seat_no} 不含 {@code del_flag}，软删行永久占着编号，
     * 于是<b>删过的座位再也生成不回来</b>，界面还显示「生成 0 个」不报错。
     * 参数化跑 '1'（真实值）与 '2'（历史值），把「非 '0' 即已删」这条判定钉死。</p>
     */
    @ParameterizedTest(name = "del_flag=''{0}'' 的软删座位必须被复活而不是当成已存在跳过")
    @ValueSource(strings = {"1", "2"})
    void sync_revivesSoftDeletedSeatRegardlessOfDeleteValue(String delFlag) {
        GzBeanSeat dead = seat(2L, "S2", null, 2);
        dead.setDelFlag(delFlag);
        List<GzBeanSeat> alive = new ArrayList<>(List.of(seat(1L, "S1", null, 1)));
        when(configMapper.selectById(7L)).thenReturn(config("whole", 1, 2));
        stubUnitSnapshots(alive, alive, alive);
        when(baseMapper.selectRawBySeatNo(anyLong(), eq("S1"))).thenReturn(seat(1L, "S1", null, 1));
        when(baseMapper.selectRawBySeatNo(anyLong(), eq("S2"))).thenReturn(dead);
        when(baseMapper.reviveSoftDeleted(anyLong(), anyLong(), any(), any(), any(), any(), any())).thenReturn(1);

        GzBeanSeatSyncResultVO r = service.syncSeatUnits(7L);

        verify(baseMapper).reviveSoftDeleted(eq(2L), eq(7L), any(), any(), any(), any(), any());
        verify(baseMapper, never()).insert(any(GzBeanSeat.class));
        assertEquals(1, r.getCreated(), "复活要计入 created —— 否则店员看到「生成 0 个」以为没生效");
    }
}
