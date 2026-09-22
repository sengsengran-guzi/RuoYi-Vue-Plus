package org.dromara.gz.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 迁移守卫：单租户合并之后，新增 {@code sys_config} 行必须显式写 {@code tenant_id}。
 *
 * <p><b>为什么要机械守卫</b>：项目在 V202607272010 合并成单租户 1001，{@code sys_config} 按租户过滤；
 * 裸 {@code INSERT} 不写 {@code tenant_id} 会落到列默认值 {@code '000000'}，结果是：
 * 小程序登录用户（带 tenant=1001）查不到这条配置、admin「参数设置」里也搜不到。
 * 这个坑已经踩了两次（客服二维码 V202608271147、回收到店注意事项 V202609211414），
 * 两次都是「照抄更早的旧迁移写法」+ 本地用匿名请求自测所以没发现（匿名请求不做租户过滤）。
 * 注释提醒挡不住，只能靠测试。</p>
 *
 * <p>迁移目录在 ruoyi-admin 模块（项目约定），surefire 的工作目录是本模块根，故按相对路径读取。</p>
 */
@Tag("dev")
class SysConfigMigrationTenantGuardTest {

    private static final Path MIGRATION_DIR = Paths.get("../../ruoyi-admin/src/main/resources/db/migration");

    /** 单租户合并迁移的版本号；此后的迁移才受本规则约束（之前的历史数据已由该迁移整体搬到 1001） */
    private static final long MERGE_VERSION = 202607272010L;

    /**
     * 已应用、无法再改（Flyway append-only）的历史违规文件 → 各自的订正迁移。
     * 新增违规一律不许加进来：应该直接在 INSERT 里写 tenant_id='1001'。
     */
    private static final Map<String, String> FIXED_ELSEWHERE = Map.of(
        "V202608271147__GZ-RECYCLE-016-service-qrcode-config.sql", "V202608271157__GZ-RECYCLE-016-fix-service-qrcode-config-tenant.sql",
        "V202609211414__GZ-RECYCLE-019-recycle-notice-config.sql", "V202609221049__GZ-RECYCLE-019-fix-notice-config-tenant.sql"
    );

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");
    /** INSERT [IGNORE] INTO sys_config ( 列清单 ) */
    private static final Pattern INSERT_COLUMNS = Pattern.compile(
        "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+`?sys_config`?\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("★ 单租户合并后的迁移：INSERT INTO sys_config 必须显式带 tenant_id（否则登录用户与 admin 都查不到）")
    void sysConfigInserts_mustSetTenantId() throws IOException {
        assertTrue(Files.isDirectory(MIGRATION_DIR), "找不到迁移目录：" + MIGRATION_DIR.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                String name = f.getFileName().toString();
                Matcher v = VERSION.matcher(name);
                if (!v.find() || Long.parseLong(v.group(1)) <= MERGE_VERSION || FIXED_ELSEWHERE.containsKey(name)) {
                    continue;
                }
                Matcher m = INSERT_COLUMNS.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    if (!m.group(1).toLowerCase().contains("tenant_id")) {
                        violations.add(name);
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("以下迁移 INSERT INTO sys_config 没写 tenant_id，会落到 '000000'，登录用户和 admin 都查不到："
                + violations + "。在列清单里加 tenant_id 并写 '1001'。");
        }
    }

    @Test
    @DisplayName("白名单里的每个历史违规都真的有对应订正迁移（防止白名单变成逃生口）")
    void allowlistedFiles_haveTheirFixMigrations() {
        for (Map.Entry<String, String> e : FIXED_ELSEWHERE.entrySet()) {
            assertTrue(Files.exists(MIGRATION_DIR.resolve(e.getValue())),
                e.getKey() + " 声称已由 " + e.getValue() + " 订正，但该文件不存在");
        }
    }
}
