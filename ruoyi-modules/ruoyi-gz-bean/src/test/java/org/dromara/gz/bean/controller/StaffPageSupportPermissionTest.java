package org.dromara.gz.bean.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 店员页面打开时必需的辅助读接口：由页面自身的业务权限放行（2026-09-23 prod 事故）。
 *
 * <p>回收店员 huishou 的角色里「门店列表」权限被连带取消（它挂在门店管理菜单下），回收看板随即报
 * 「当前操作没有权限」、门店下拉为空、看板空白。本测守住：门店下拉按业务线放行、桌型下拉认预约查看权限。</p>
 */
@Tag("dev")
class StaffPageSupportPermissionTest {

    private static boolean check(String scope, Set<String> userPerms) {
        return GzBeanStoreController.canReadStoreOptions(scope, userPerms::contains, userPerms);
    }

    @Test
    @DisplayName("★ 回收店员（只有回收权限、没有门店权限）能读回收门店下拉 —— huishou 事故的直接修复")
    void recycleStaff_canReadRecycleStores() {
        Set<String> recycleStaff = Set.of("gz:recycle:appointment:list", "gz:recycle:appointment:verify");
        assertTrue(check("recycle", recycleStaff));
        assertFalse(check("pindou", recycleStaff), "回收店员不应因此读到拼豆门店");
        assertFalse(check(null, recycleStaff), "不带 scope 的全集（账号绑定用）仍只给门店管理权限");
    }

    @Test
    @DisplayName("拼豆店员（只有拼豆权限）能读拼豆门店下拉，读不到回收的")
    void pindouStaff_canReadPindouStores() {
        Set<String> pindouStaff = Set.of("gz:bean:booking:list", "gz:bean:booking:verify");
        assertTrue(check("pindou", pindouStaff));
        assertFalse(check("recycle", pindouStaff));
    }

    @Test
    @DisplayName("有门店管理权限 → 任何 scope 都能读（负责人 / 超管不受影响）")
    void storeManager_canReadAll() {
        Set<String> owner = Set.of("gz:bean:store:list");
        assertTrue(check(null, owner));
        assertTrue(check("recycle", owner));
        assertTrue(check("pindou", owner));
    }

    @Test
    @DisplayName("完全不相关的角色 → 拒绝")
    void unrelatedRole_denied() {
        Set<String> newsEditor = Set.of("gz:news:article:list");
        assertFalse(check("recycle", newsEditor));
        assertFalse(check("pindou", newsEditor));
        assertFalse(check(null, newsEditor));
    }

    @Test
    @DisplayName("★ 注解守卫：门店下拉不能再挂固定的门店权限注解；桌型下拉必须认预约查看权限")
    void annotations() throws Exception {
        assertNull(GzBeanStoreController.class.getMethod("options", String.class).getAnnotation(SaCheckPermission.class),
            "门店下拉改为按业务线判定，加回 @SaCheckPermission(\"gz:bean:store:list\") 会让回收 / 拼豆店员的看板重新失效");
        SaCheckPermission a = GzBeanSeatTypeConfigController.class.getMethod("listByStore", Long.class).getAnnotation(SaCheckPermission.class);
        List<String> perms = Arrays.asList(a.value());
        assertEquals(SaMode.OR, a.mode());
        assertTrue(perms.contains("gz:bean:booking:list"), "桌型下拉必须认预约查看权限，实际=" + perms);
    }
}
