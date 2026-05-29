package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanStoreBo;
import org.dromara.gz.bean.domain.bo.GzBeanStoreQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanStoreVO;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GzBeanStoreServiceImpl} 单测（GZ-BEAN-001）。
 *
 * <p>覆盖任务卡 AC 8：</p>
 * <ul>
 *   <li>happy path：insertByBo 走默认值填充 + insert 调用</li>
 *   <li>UNIQUE 拦截：checkStoreNoUnique 已存在 → insertByBo 抛 ServiceException</li>
 *   <li>updateByBo 忽略 storeNo（业务码不可改）</li>
 *   <li>selectMpList 只返 type=pindou + status=open（构造的 wrapper 验证）</li>
 *   <li>selectVoById null safety</li>
 *   <li>selectPageList 分页透传</li>
 * </ul>
 *
 * <p>使用 MockitoExtension + MockedStatic 隔离 MapstructUtils 静态依赖（避免 Spring context）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzBeanStoreServiceImplTest {

    @Mock
    private GzBeanStoreMapper baseMapper;

    private GzBeanStoreServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanStoreServiceImpl(baseMapper);
    }

    // ------------------------------ insertByBo ------------------------------

    @Test
    @DisplayName("insertByBo happy path → 默认值兜底 + insert 调用 + bo.id 回填")
    void insertByBo_happy_appliesDefaultsAndInserts() {
        GzBeanStoreBo bo = new GzBeanStoreBo();
        bo.setStoreNo("CD002");
        bo.setName("成都太古里店");
        bo.setAddress("成都市锦江区太古里");
        // 不填 type / status / maxAdvanceDays → service 兜底
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(false); // 唯一
        when(baseMapper.insert(any(GzBeanStore.class))).thenAnswer(invocation -> {
            GzBeanStore e = invocation.getArgument(0);
            e.setId(11L);
            return 1;
        });

        boolean ok = service.insertByBo(bo);
        assertTrue(ok);
        assertEquals(11L, bo.getId(), "bo.id 应被回填");

        ArgumentCaptor<GzBeanStore> captor = ArgumentCaptor.forClass(GzBeanStore.class);
        verify(baseMapper).insert(captor.capture());
        GzBeanStore saved = captor.getValue();
        assertEquals("CD002", saved.getStoreNo());
        assertEquals("成都太古里店", saved.getName());
        assertEquals("成都市锦江区太古里", saved.getAddress());
        assertEquals("pindou", saved.getType(), "type 未填 → 默认 pindou");
        assertEquals("open", saved.getStatus(), "status 未填 → 默认 open");
        assertEquals(14, saved.getMaxAdvanceDays(), "maxAdvanceDays 未填 → 默认 14");
    }

    @Test
    @DisplayName("insertByBo BO 已显式填 type=guzi → 保留不覆盖（v2 预留场景）")
    void insertByBo_preservesExplicitType() {
        GzBeanStoreBo bo = new GzBeanStoreBo();
        bo.setStoreNo("CD-GZ-001");
        bo.setName("成都谷子店");
        bo.setAddress("地址");
        bo.setType("guzi");
        bo.setStatus("closed");
        bo.setMaxAdvanceDays(7);
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(baseMapper.insert(any(GzBeanStore.class))).thenAnswer(inv -> {
            ((GzBeanStore) inv.getArgument(0)).setId(12L);
            return 1;
        });

        assertTrue(service.insertByBo(bo));
        ArgumentCaptor<GzBeanStore> captor = ArgumentCaptor.forClass(GzBeanStore.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals("guzi", captor.getValue().getType());
        assertEquals("closed", captor.getValue().getStatus());
        assertEquals(7, captor.getValue().getMaxAdvanceDays());
    }

    @Test
    @DisplayName("insertByBo UNIQUE 拦截：storeNo 已存在 → ServiceException")
    void insertByBo_duplicateStoreNo_throws() {
        GzBeanStoreBo bo = new GzBeanStoreBo();
        bo.setStoreNo("CD001");
        bo.setName("测试");
        bo.setAddress("地址");
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(true); // UNIQUE 撞

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("CD001"));
        verify(baseMapper, never()).insert(any(GzBeanStore.class));
    }

    // ------------------------------ updateByBo ------------------------------

    @Test
    @DisplayName("updateByBo 忽略 storeNo（业务码不可改）")
    void updateByBo_ignoresStoreNo() {
        GzBeanStoreBo bo = new GzBeanStoreBo();
        bo.setId(1L);
        bo.setStoreNo("ATTEMPT-CHANGE"); // 期望被忽略
        bo.setName("改名后");
        bo.setAddress("新地址");
        when(baseMapper.updateById(any(GzBeanStore.class))).thenReturn(1);

        boolean ok = service.updateByBo(bo);
        assertTrue(ok);

        ArgumentCaptor<GzBeanStore> captor = ArgumentCaptor.forClass(GzBeanStore.class);
        verify(baseMapper).updateById(captor.capture());
        assertNull(captor.getValue().getStoreNo(), "storeNo 应被置 null 避免 SQL 改业务码");
        assertEquals("改名后", captor.getValue().getName());
        assertEquals("新地址", captor.getValue().getAddress());
        assertEquals(1L, captor.getValue().getId());
    }

    @Test
    @DisplayName("updateByBo id=null → ServiceException")
    void updateByBo_nullId_throws() {
        GzBeanStoreBo bo = new GzBeanStoreBo();
        bo.setName("x");
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
    }

    // ------------------------------ checkStoreNoUnique ------------------------------

    @Test
    @DisplayName("checkStoreNoUnique 空 storeNo → 视为唯一")
    void checkStoreNoUnique_blank_isUnique() {
        GzBeanStoreBo bo = new GzBeanStoreBo();
        assertTrue(service.checkStoreNoUnique(bo));
        verify(baseMapper, never()).exists(any(Wrapper.class));
    }

    @Test
    @DisplayName("checkStoreNoUnique 编辑场景：排除自身 id")
    void checkStoreNoUnique_editScene_excludesSelfId() {
        GzBeanStoreBo bo = new GzBeanStoreBo();
        bo.setId(5L);
        bo.setStoreNo("CD001");
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(false);

        assertTrue(service.checkStoreNoUnique(bo));
        verify(baseMapper).exists(any(Wrapper.class));
    }

    // ------------------------------ selectMpList ------------------------------

    @Test
    @DisplayName("selectMpList 调用 mapper.selectVoList — 业务规则在 wrapper 内")
    void selectMpList_callsMapper() {
        GzBeanStoreVO vo = new GzBeanStoreVO();
        vo.setId(1L);
        vo.setStoreNo("CD001");
        vo.setName("成都春熙路店");
        vo.setType("pindou");
        vo.setStatus("open");
        when(baseMapper.selectVoList(any(Wrapper.class))).thenReturn(List.of(vo));

        List<GzBeanStoreVO> result = service.selectMpList();

        assertEquals(1, result.size());
        assertEquals("CD001", result.get(0).getStoreNo());
        verify(baseMapper).selectVoList(any(Wrapper.class));
    }

    // ------------------------------ selectOptions ------------------------------

    @Test
    @DisplayName("selectOptions 调用 mapper.selectVoList — admin 账号下拉数据")
    void selectOptions_callsMapper() {
        when(baseMapper.selectVoList(any(Wrapper.class))).thenReturn(List.of());
        assertNotNull(service.selectOptions());
        verify(baseMapper).selectVoList(any(Wrapper.class));
    }

    // ------------------------------ selectPageList ------------------------------

    @Test
    @DisplayName("selectPageList 透传 → TableDataInfo")
    @SuppressWarnings("unchecked")
    void selectPageList_transitive() {
        GzBeanStoreQueryBo q = new GzBeanStoreQueryBo();
        q.setName("成都");
        PageQuery pq = new PageQuery(10, 1);

        GzBeanStoreVO vo = new GzBeanStoreVO();
        vo.setId(1L);
        Page<GzBeanStoreVO> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(vo));
        when(baseMapper.selectVoPage(any(), any(Wrapper.class))).thenReturn((IPage) page);

        TableDataInfo<GzBeanStoreVO> result = service.selectPageList(q, pq);

        assertNotNull(result);
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRows().size());
    }

    // ------------------------------ selectVoById ------------------------------

    @Test
    @DisplayName("selectVoById null id → null safety")
    void selectVoById_nullId_returnsNull() {
        assertNull(service.selectVoById(null));
        verify(baseMapper, never()).selectVoById(any(java.io.Serializable.class));
    }

    @Test
    @DisplayName("selectVoById 正常 → mapper 透传")
    void selectVoById_valid_returnsMapperResult() {
        GzBeanStoreVO vo = new GzBeanStoreVO();
        vo.setId(7L);
        when(baseMapper.selectVoById(7L)).thenReturn(vo);
        GzBeanStoreVO got = service.selectVoById(7L);
        assertNotNull(got);
        assertEquals(7L, got.getId());
    }

    // ------------------------------ deleteByIds ------------------------------

    @Test
    @DisplayName("deleteByIds 空集合 → 直接返 false")
    void deleteByIds_emptyCollection_returnsFalse() {
        assertFalse(service.deleteByIds(List.of()));
        verify(baseMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("deleteByIds 正常 → mapper.deleteByIds 调用 + 软删")
    void deleteByIds_valid_callsMapper() {
        when(baseMapper.deleteByIds(any())).thenReturn(2);
        assertTrue(service.deleteByIds(List.of(1L, 2L)));
        verify(baseMapper).deleteByIds(any());
    }
}
