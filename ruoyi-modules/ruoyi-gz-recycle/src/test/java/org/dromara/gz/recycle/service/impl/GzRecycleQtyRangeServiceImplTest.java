package org.dromara.gz.recycle.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.recycle.domain.bo.GzRecycleQtyRangeBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleQtyRange;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.mapper.GzRecycleQtyRangeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecycleQtyRangeServiceImpl} 单测（GZ-RECYCLE-004）。
 *
 * <p>覆盖：新建成功（默认 enabled=1 / sortNo 兜底 / 落 durationMinutes）/ code 重复拒绝 /
 * 编辑不存在拒绝 / 启用桶列表带 durationMinutes。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleQtyRangeServiceImplTest {

    @Mock
    private GzRecycleQtyRangeMapper baseMapper;

    private GzRecycleQtyRangeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzRecycleQtyRangeServiceImpl(baseMapper);
    }

    private GzRecycleQtyRangeBo bo(Long id, String code, String label, Integer duration, Integer enabled, Integer sortNo) {
        GzRecycleQtyRangeBo bo = new GzRecycleQtyRangeBo();
        bo.setId(id);
        bo.setCode(code);
        bo.setLabel(label);
        bo.setDurationMinutes(duration);
        bo.setEnabled(enabled);
        bo.setSortNo(sortNo);
        return bo;
    }

    private GzRecycleQtyRange range(long id, String code, String label, int duration, int enabled, int sortNo) {
        GzRecycleQtyRange e = new GzRecycleQtyRange();
        e.setId(id);
        e.setCode(code);
        e.setLabel(label);
        e.setDurationMinutes(duration);
        e.setEnabled(enabled);
        e.setSortNo(sortNo);
        return e;
    }

    @Test
    @DisplayName("happy：新建桶，code 不重复 → enabled 默认 1 / sortNo 兜底 0 / 落 durationMinutes")
    void insert_ok() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(GzRecycleQtyRange.class))).thenAnswer(inv -> {
            GzRecycleQtyRange e = inv.getArgument(0);
            e.setId(201L);
            return 1;
        });
        Long id = service.insertByBo(bo(null, " 1-25 ", "1-25 件", 30, null, null));
        assertEquals(201L, id);

        ArgumentCaptor<GzRecycleQtyRange> cap = ArgumentCaptor.forClass(GzRecycleQtyRange.class);
        org.mockito.Mockito.verify(baseMapper).insert((GzRecycleQtyRange) cap.capture());
        GzRecycleQtyRange saved = cap.getValue();
        assertEquals("1-25", saved.getCode());   // trim 生效
        assertEquals(30, saved.getDurationMinutes());
        assertEquals(1, saved.getEnabled());      // 默认启用
        assertEquals(0, saved.getSortNo());       // null 兜底 0
    }

    @Test
    @DisplayName("error：新建桶 code 已存在 → 抛「已存在」业务异常，不落库")
    void insert_duplicateCode_rejected() {
        when(baseMapper.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.insertByBo(bo(null, "1-25", "1-25 件", 30, 1, 1)));
        assertTrue(ex.getMessage().contains("已存在"));
        org.mockito.Mockito.verify(baseMapper, org.mockito.Mockito.never()).insert(any(GzRecycleQtyRange.class));
    }

    @Test
    @DisplayName("error：编辑不存在的桶 → 抛「不存在」业务异常")
    void update_notFound_rejected() {
        when(baseMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.updateByBo(bo(999L, "25-50", "25-50 件", 60, 1, 2)));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("happy：启用桶列表带 durationMinutes，按 sort 顺序映射")
    void listEnabled_ok() {
        lenient().when(baseMapper.selectList(any())).thenReturn(List.of(
            range(1L, "1-25", "1-25 件", 30, 1, 1),
            range(2L, "25-50", "25-50 件", 60, 1, 2)));
        List<GzRecycleQtyRangeVO> list = service.listEnabled();
        assertEquals(2, list.size());
        assertEquals("1-25", list.get(0).getCode());
        assertEquals(30, list.get(0).getDurationMinutes());
        assertEquals(60, list.get(1).getDurationMinutes());
    }
}
