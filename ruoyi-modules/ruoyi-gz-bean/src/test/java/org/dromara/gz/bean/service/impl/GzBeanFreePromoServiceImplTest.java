package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanFreePromoBo;
import org.dromara.gz.bean.domain.entity.GzBeanFreePromo;
import org.dromara.gz.bean.domain.vo.GzBeanFreePromoStatusVO;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanFreePromoMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link GzBeanFreePromoServiceImpl} 单测（GZ-BEAN-025，ADR-0015 §4）。
 *
 * <p>覆盖：周期桶计算（day/week/days，含锚点对齐 + 负向偏移）、mp 状态（生效 / 关 / 不在窗口 / 名额剩余下限 0）、
 * admin 跨字段校验（days 必须 anchorDate + periodDays≥1 / start≤end）。</p>
 *
 * <p>下单事务内 evaluateAndLockBucket / releaseBucket 依赖 RedisUtils 静态 + 事务同步，归集成测试覆盖
 * （同 GzBeanBookingServiceImplTest Redis 锁口径）；本单测聚焦纯逻辑与 mapper 可 mock 的部分。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
@Tag("dev")
@DisplayName("GzBeanFreePromoServiceImpl 单测")
@ExtendWith(MockitoExtension.class)
class GzBeanFreePromoServiceImplTest {

    @Mock private GzBeanFreePromoMapper baseMapper;
    @Mock private GzBeanStoreMapper storeMapper;
    @Mock private GzBeanBookingMapper bookingMapper;

    private GzBeanFreePromoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanFreePromoServiceImpl(baseMapper, storeMapper, bookingMapper);
    }

    // ------------------------------ computeBucketRange ------------------------------

    @Test
    @DisplayName("computeBucketRange · day → [当天 00:00, 次日 00:00)")
    void bucket_day() {
        LocalDate ref = LocalDate.of(2026, 6, 29); // 周一
        LocalDateTime[] r = service.computeBucketRange("day", 1, null, ref);
        assertEquals(LocalDateTime.of(2026, 6, 29, 0, 0), r[0]);
        assertEquals(LocalDateTime.of(2026, 6, 30, 0, 0), r[1]);
    }

    @Test
    @DisplayName("computeBucketRange · week → ISO 自然周 [周一 00:00, 下周一 00:00)（参考日是周三仍对齐到本周一）")
    void bucket_week() {
        LocalDate wed = LocalDate.of(2026, 7, 1); // 2026-06-29 是周一 → 本周一 = 06-29
        LocalDateTime[] r = service.computeBucketRange("week", 1, null, wed);
        assertEquals(LocalDateTime.of(2026, 6, 29, 0, 0), r[0], "对齐到 ISO 周一");
        assertEquals(LocalDateTime.of(2026, 7, 6, 0, 0), r[1]);
    }

    @Test
    @DisplayName("computeBucketRange · days(N=3) anchor=06-29 → 06-29~07-01 同桶，07-02 进下一桶")
    void bucket_days_aligned() {
        LocalDate anchor = LocalDate.of(2026, 6, 29);
        // 桶 0：[anchor, anchor+3)
        LocalDateTime[] b0 = service.computeBucketRange("days", 3, anchor, LocalDate.of(2026, 7, 1));
        assertEquals(LocalDateTime.of(2026, 6, 29, 0, 0), b0[0]);
        assertEquals(LocalDateTime.of(2026, 7, 2, 0, 0), b0[1]);
        // 07-02 → 桶 1：[anchor+3, anchor+6)
        LocalDateTime[] b1 = service.computeBucketRange("days", 3, anchor, LocalDate.of(2026, 7, 2));
        assertEquals(LocalDateTime.of(2026, 7, 2, 0, 0), b1[0]);
        assertEquals(LocalDateTime.of(2026, 7, 5, 0, 0), b1[1]);
    }

    @Test
    @DisplayName("computeBucketRange · days 参考日早于 anchor（负偏移）→ floorDiv 正确落到前一桶")
    void bucket_days_beforeAnchor() {
        LocalDate anchor = LocalDate.of(2026, 6, 29);
        // ref = anchor-1 → diff=-1, floorDiv(-1,3)=-1 → 桶起 = anchor-3
        LocalDateTime[] r = service.computeBucketRange("days", 3, anchor, LocalDate.of(2026, 6, 28));
        assertEquals(LocalDateTime.of(2026, 6, 26, 0, 0), r[0]);
        assertEquals(LocalDateTime.of(2026, 6, 29, 0, 0), r[1]);
    }

    // ------------------------------ getStatus ------------------------------

    @Test
    @DisplayName("getStatus · storeId=null → enabled=false 占位（不查库）")
    void status_nullStore() {
        GzBeanFreePromoStatusVO vo = service.getStatus(null);
        assertFalse(vo.getEnabled());
        assertEquals(0, vo.getRemaining());
    }

    @Test
    @DisplayName("getStatus · 促销关 → enabled=false")
    void status_disabled() {
        GzBeanFreePromo promo = newPromo("day", 10, 0); // enabled=0
        when(baseMapper.selectOne(any(Wrapper.class))).thenReturn(promo);
        GzBeanFreePromoStatusVO vo = service.getStatus(1L);
        assertFalse(vo.getEnabled());
    }

    @Test
    @DisplayName("getStatus · 生效中已发 7 / 名额 10 → remaining=3 + periodLabel=今日")
    void status_active_remaining() {
        GzBeanFreePromo promo = newPromo("day", 10, 1);
        when(baseMapper.selectOne(any(Wrapper.class))).thenReturn(promo);
        when(bookingMapper.countBucketIssuedFree(eq("1001"), eq(1L), any(), any())).thenReturn(7L);
        GzBeanFreePromoStatusVO vo = service.getStatus(1L);
        assertTrue(vo.getEnabled());
        assertEquals(10, vo.getFreeCount());
        assertEquals(3, vo.getRemaining());
        assertEquals("今日", vo.getPeriodLabel());
    }

    @Test
    @DisplayName("getStatus · 已发 ≥ 名额 → remaining 下限 0（不出现负数）")
    void status_remaining_floorZero() {
        GzBeanFreePromo promo = newPromo("week", 5, 1);
        when(baseMapper.selectOne(any(Wrapper.class))).thenReturn(promo);
        when(bookingMapper.countBucketIssuedFree(anyString(), anyLong(), any(), any())).thenReturn(8L);
        GzBeanFreePromoStatusVO vo = service.getStatus(1L);
        assertTrue(vo.getEnabled());
        assertEquals(0, vo.getRemaining());
        assertEquals("本周", vo.getPeriodLabel());
    }

    @Test
    @DisplayName("getStatus · 不在促销窗口（end_date 已过）→ enabled=false")
    void status_outsideWindow() {
        GzBeanFreePromo promo = newPromo("day", 10, 1);
        promo.setEndDate(LocalDate.now().minusDays(1)); // 窗口已过
        when(baseMapper.selectOne(any(Wrapper.class))).thenReturn(promo);
        GzBeanFreePromoStatusVO vo = service.getStatus(1L);
        assertFalse(vo.getEnabled());
    }

    // ------------------------------ admin 跨字段校验（error path）------------------------------

    @Test
    @DisplayName("insertByBo · days 周期未填 anchorDate → ServiceException")
    void insert_days_missingAnchor() {
        GzBeanFreePromoBo bo = newBo("days", 5, 1);
        bo.setPeriodDays(3);
        bo.setAnchorDate(null);
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("insertByBo · days 周期 periodDays<1 → ServiceException")
    void insert_days_invalidPeriodDays() {
        GzBeanFreePromoBo bo = newBo("days", 5, 1);
        bo.setPeriodDays(0);
        bo.setAnchorDate(LocalDate.of(2026, 6, 29));
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("insertByBo · start>end → ServiceException")
    void insert_startAfterEnd() {
        GzBeanFreePromoBo bo = newBo("day", 5, 1);
        bo.setStartDate(LocalDate.of(2026, 7, 10));
        bo.setEndDate(LocalDate.of(2026, 7, 1));
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("insertByBo · 该门店已有配置（UNIQUE）→ ServiceException")
    void insert_duplicateStore() {
        GzBeanFreePromoBo bo = newBo("day", 5, 1);
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(true); // 已存在
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("insertByBo · day 周期 happy → 归一化 periodDays=1/anchor=null + insert")
    void insert_day_happy() {
        GzBeanFreePromoBo bo = newBo("day", 5, 1);
        bo.setPeriodDays(99);              // day 周期应被归一化为 1
        bo.setAnchorDate(LocalDate.now()); // day 周期应被归一化为 null
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(baseMapper.insert(any(GzBeanFreePromo.class))).thenAnswer(inv -> {
            ((GzBeanFreePromo) inv.getArgument(0)).setId(77L);
            return 1;
        });
        assertTrue(service.insertByBo(bo));
        assertEquals(77L, bo.getId());
    }

    // ------------------------------ helpers ------------------------------

    private GzBeanFreePromo newPromo(String periodType, int freeCount, int enabled) {
        GzBeanFreePromo p = GzBeanFreePromo.builder()
            .id(1L).storeId(1L).periodType(periodType).periodDays(1)
            .freeCount(freeCount).enabled(enabled).delFlag("0").build();
        // getStatus 用 promo.getTenantId() 显式传给 countBucketIssuedFree（V1 单租户 1001）
        p.setTenantId("1001");
        return p;
    }

    private GzBeanFreePromoBo newBo(String periodType, int freeCount, int enabled) {
        GzBeanFreePromoBo bo = new GzBeanFreePromoBo();
        bo.setStoreId(1L);
        bo.setPeriodType(periodType);
        bo.setFreeCount(freeCount);
        bo.setEnabled(enabled);
        return bo;
    }
}
