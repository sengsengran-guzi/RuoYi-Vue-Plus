package org.dromara.gz.common.recycle.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.UserService;
import org.dromara.gz.common.recycle.domain.vo.RecycleRawRow;
import org.dromara.gz.common.recycle.mapper.GzRecycleBinMapper;
import org.dromara.gz.common.recycle.service.impl.GzRecycleBinServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-ADMIN-108 AC9 — 回收站 service 单测（Mockito 模拟泛型表 mapper）。
 *
 * <p>验：restore happy（del_flag 2→0）/ restore 影响 0 行抛业务异常（不静默成功，D6）/
 * cleanupExpired 遍历 6 张注册表物理清理求和。del_flag 严格 {'0','2'}（doc/11 §0.3，归档走独立 archived_flag）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleBinServiceImplTest {

    @Mock
    private GzRecycleBinMapper recycleBinMapper;
    @Mock
    private UserService userService;

    @InjectMocks
    private GzRecycleBinServiceImpl service;

    @Test
    @DisplayName("restore happy：news del_flag 2→0，返回展示名称")
    void restore_happy() {
        RecycleRawRow row = new RecycleRawRow();
        row.setId(42L);
        row.setEntityName("误删的资讯标题");
        row.setDeleteTime(LocalDateTime.now());
        // resolveName 查名（任意筛选参数）
        lenient().when(recycleBinMapper.selectDeleted(eq("gz_news_article"), eq("title"), any(), any(), any()))
            .thenReturn(List.of(row));
        when(recycleBinMapper.restore("gz_news_article", 42L)).thenReturn(1);

        String name = service.restore("news", 42L);
        assertEquals("误删的资讯标题", name);
        verify(recycleBinMapper).restore("gz_news_article", 42L);
    }

    @Test
    @DisplayName("restore 影响 0 行（记录不存在/非已删）→ 抛业务异常，不静默成功")
    void restore_zeroRowsThrows() {
        lenient().when(recycleBinMapper.selectDeleted(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(recycleBinMapper.restore("gz_ord_product", 99L)).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.restore("ord_product", 99L));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("恢复失败"));
    }

    @Test
    @DisplayName("非法 entityType → 抛异常（注册表白名单拦截）")
    void restore_unknownEntityType() {
        assertThrows(IllegalArgumentException.class, () -> service.restore("not_registered", 1L));
        verify(recycleBinMapper, never()).restore(any(), any());
    }

    @Test
    @DisplayName("archive happy：archived_flag=1（del_flag 保持 '2'，归档与逻辑删正交）")
    void archive_happy() {
        RecycleRawRow row = new RecycleRawRow();
        row.setId(7L);
        row.setEntityName("某扭蛋机");
        lenient().when(recycleBinMapper.selectDeleted(eq("gz_gacha_machine"), eq("name"), any(), any(), any()))
            .thenReturn(List.of(row));
        when(recycleBinMapper.archive("gz_gacha_machine", 7L)).thenReturn(1);

        assertEquals("某扭蛋机", service.archive("gacha_machine", 7L));
        verify(recycleBinMapper).archive("gz_gacha_machine", 7L);
    }

    @Test
    @DisplayName("cleanupExpired：遍历 6 张注册表物理清理，求和；archived/未超期由 SQL WHERE 过滤")
    void cleanupExpired_sumAcrossTables() {
        // 6 张注册表各返回删除行数（news 5 / ord_product 3 / 其余 0）
        when(recycleBinMapper.cleanupExpired(eq("gz_news_article"), any())).thenReturn(5);
        when(recycleBinMapper.cleanupExpired(eq("gz_ord_product"), any())).thenReturn(3);
        when(recycleBinMapper.cleanupExpired(eq("gz_ord_sku"), any())).thenReturn(0);
        when(recycleBinMapper.cleanupExpired(eq("gz_gacha_prize"), any())).thenReturn(0);
        when(recycleBinMapper.cleanupExpired(eq("gz_gacha_machine"), any())).thenReturn(0);
        when(recycleBinMapper.cleanupExpired(eq("gz_bean_store"), any())).thenReturn(0);

        int total = service.cleanupExpired();
        assertEquals(8, total, "5 + 3");
        verify(recycleBinMapper, times(6)).cleanupExpired(any(), any());
    }
}
