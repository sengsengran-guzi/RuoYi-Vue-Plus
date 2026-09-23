package org.dromara.gz.common.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务文件接口的放行规则（2026-09-23 prod 事故：回收店员看板「当前操作没有权限」）。
 *
 * <p>回收看板要看客人实物照、核销时要拍照存证，原先只认「业务文件管理」菜单下的按钮权限，
 * 角色管理里取消「运营配置」那棵树就会被连带取消。现在回收业务权限也放行，但只限回收相关图片。</p>
 */
@Tag("dev")
class GzFileControllerPermissionTest {

    @Test
    @DisplayName("上传：有文件权限 → 任意用途；只有回收核销权限 → 只能传回收核对照")
    void canUpload() {
        assertTrue(GzFileController.canUpload(true, "news_cover"));
        assertTrue(GzFileController.canUpload(false, "recycle_verify_image"));
        assertFalse(GzFileController.canUpload(false, "news_cover"), "回收店员不能借核销权限上传资讯封面");
        assertFalse(GzFileController.canUpload(false, "store_image"));
    }

    @Test
    @DisplayName("取图：有文件权限 → 任意文件；只有回收看板权限 → 只能看回收实物照 / 核对照")
    void canView() {
        assertTrue(GzFileController.canView(true, "user_avatar"));
        assertTrue(GzFileController.canView(false, "recycle_submit_image"));
        assertTrue(GzFileController.canView(false, "recycle_verify_image"));
        assertFalse(GzFileController.canView(false, "user_avatar"), "回收店员不能借看板权限看用户头像等其它文件");
        assertFalse(GzFileController.canView(false, null));
    }

    @Test
    @DisplayName("★ 注解守卫：上传 / 取图必须是「文件权限 或 回收业务权限」，改回单一权限 = 回收看板又会被连带弄坏")
    void annotations_keepBusinessPermission() throws Exception {
        assertOr(GzFileController.class.getMethod("upload", org.springframework.web.multipart.MultipartFile.class, String.class),
            "gz:file:upload", "gz:recycle:appointment:verify");
        assertOr(GzFileController.class.getMethod("getUrl", Long.class), "gz:file:list", "gz:recycle:appointment:list");
    }

    static void assertOr(java.lang.reflect.Method m, String... expected) {
        SaCheckPermission a = m.getAnnotation(SaCheckPermission.class);
        List<String> perms = Arrays.asList(a.value());
        assertEquals(SaMode.OR, a.mode(), m.getName() + " 必须是「或」权限");
        for (String p : expected) {
            assertTrue(perms.contains(p), m.getName() + " 缺少放行权限 " + p + "，实际=" + perms);
        }
    }
}
