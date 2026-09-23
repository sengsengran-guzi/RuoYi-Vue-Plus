package org.dromara.gz.recycle.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.gz.recycle.domain.bo.GzRecycleQtyRangeQueryBo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收看板的「点数档」筛选下拉由回收看板权限放行（2026-09-23 prod 事故同类问题）。
 */
@Tag("dev")
class GzRecycleQtyRangeControllerPermissionTest {

    @Test
    @DisplayName("★ 点数档列表必须是「点数档权限 或 回收看板权限」")
    void list_allowsAppointmentListPermission() throws Exception {
        SaCheckPermission a = GzRecycleQtyRangeController.class
            .getMethod("list", GzRecycleQtyRangeQueryBo.class, PageQuery.class).getAnnotation(SaCheckPermission.class);
        List<String> perms = Arrays.asList(a.value());
        assertEquals(SaMode.OR, a.mode());
        assertTrue(perms.contains("gz:recycle:qtyRange:list") && perms.contains("gz:recycle:appointment:list"), "实际=" + perms);
    }
}
