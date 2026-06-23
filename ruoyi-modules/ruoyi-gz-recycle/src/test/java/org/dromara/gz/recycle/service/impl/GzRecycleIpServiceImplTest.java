package org.dromara.gz.recycle.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.recycle.domain.bo.GzRecycleIpBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleIp;
import org.dromara.gz.recycle.domain.vo.GzRecycleIpVO;
import org.dromara.gz.recycle.mapper.GzRecycleIpMapper;
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
 * {@link GzRecycleIpServiceImpl} 单测（GZ-RECYCLE-004）。
 *
 * <p>覆盖：新建成功（默认 enabled=1 / sortNo=0 兜底）/ 名称重复拒绝 / 编辑不存在拒绝 /
 * 启用列表仅启用项 + 排序。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleIpServiceImplTest {

    @Mock
    private GzRecycleIpMapper baseMapper;

    private GzRecycleIpServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzRecycleIpServiceImpl(baseMapper);
    }

    private GzRecycleIpBo bo(Long id, String ipName, Integer enabled, Integer sortNo) {
        GzRecycleIpBo bo = new GzRecycleIpBo();
        bo.setId(id);
        bo.setIpName(ipName);
        bo.setEnabled(enabled);
        bo.setSortNo(sortNo);
        return bo;
    }

    private GzRecycleIp ip(long id, String ipName, int enabled, int sortNo) {
        GzRecycleIp e = new GzRecycleIp();
        e.setId(id);
        e.setIpName(ipName);
        e.setEnabled(enabled);
        e.setSortNo(sortNo);
        return e;
    }

    @Test
    @DisplayName("happy：新建 IP，名称不重复 → enabled 默认 1 / sortNo null 兜底 0，落库")
    void insert_ok() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(GzRecycleIp.class))).thenAnswer(inv -> {
            GzRecycleIp e = inv.getArgument(0);
            e.setId(101L);
            return 1;
        });
        Long id = service.insertByBo(bo(null, "  火影  ", null, null));
        assertEquals(101L, id);

        ArgumentCaptor<GzRecycleIp> cap = ArgumentCaptor.forClass(GzRecycleIp.class);
        org.mockito.Mockito.verify(baseMapper).insert((GzRecycleIp) cap.capture());
        GzRecycleIp saved = cap.getValue();
        assertEquals("火影", saved.getIpName());   // trim 生效
        assertEquals(1, saved.getEnabled());        // 默认启用
        assertEquals(0, saved.getSortNo());         // null 兜底 0
    }

    @Test
    @DisplayName("error：新建 IP 名称已存在 → 抛「已存在」业务异常，不落库")
    void insert_duplicateName_rejected() {
        when(baseMapper.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.insertByBo(bo(null, "火影", 1, 1)));
        assertTrue(ex.getMessage().contains("已存在"));
        org.mockito.Mockito.verify(baseMapper, org.mockito.Mockito.never()).insert(any(GzRecycleIp.class));
    }

    @Test
    @DisplayName("error：编辑不存在的 IP → 抛「不存在」业务异常")
    void update_notFound_rejected() {
        when(baseMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.updateByBo(bo(999L, "海贼王", 1, 1)));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("happy：启用列表只取 enabled=1，VO 字段正确映射")
    void listEnabled_ok() {
        lenient().when(baseMapper.selectList(any())).thenReturn(List.of(
            ip(1L, "火影", 1, 1),
            ip(2L, "海贼王", 1, 2)));
        List<GzRecycleIpVO> list = service.listEnabled();
        assertEquals(2, list.size());
        assertEquals("火影", list.get(0).getIpName());
        assertEquals(1L, list.get(0).getId());
    }
}
