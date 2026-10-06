package org.dromara.gz.jp.snap.gateway;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.job.GzJpSnapHealthJob;
import org.dromara.gz.jp.mapper.GzJpSnapAccountMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual Docker replay/fake + actual dev MySQL. No 8080 or external Mercari required. */
@Tag("dev")
class GzJpGatewayClientIT {
    private GzJpGatewayProperties config() throws Exception {
        GzJpGatewayProperties properties = new GzJpGatewayProperties();
        String secret = System.getenv("GZ_JP_GATEWAY_SECRET");
        if (secret == null || secret.isBlank()) {
            // Local test-only fallback; credentials never appear in assertions/logs or source control.
            Path env = Path.of("../../../mercari-gateway/.env");
            secret = Files.readAllLines(env).stream().filter(line -> line.startsWith("GATEWAY_HMAC_SECRET="))
                .map(line -> line.substring(line.indexOf('=') + 1).strip()).findFirst().orElseThrow();
        }
        properties.setSecret(secret);
        return properties;
    }
    @Test void realGatewayAcceptsQueryAndBodySignatureAndHealthReachesDatabase() throws Exception {
        var properties = config();
        var executor = Executors.newFixedThreadPool(2, runnable -> new Thread(runnable, "snap-purchase-it"));
        try {
            var client = new GzJpGatewayClient(properties, new ObjectMapper(), executor);
            var health = client.health();
            assertEquals("fake", health.path("driver").asText(), "QA must use fake driver");
            assertTrue(health.path("egress_ok").asBoolean());
            assertTrue(client.search(properties.getProbeKeyword(), "created_desc", 1, "").path("items").size() > 0);
            assertEquals(properties.getProbeItemId(), client.item(properties.getProbeItemId()).path("external_id").asText());
            String orderNo = "IT301-" + UUID.randomUUID().toString().substring(0, 18);
            var result = client.purchase(Map.of("order_no", orderNo, "external_id", "m99000000001", "max_price_jpy", 1000,
                "expect_shipping_payer", "seller", "account_code", "acc01")).get();
            assertEquals("success", result.path("result").asText());
            assertTrue(client.reconcile(orderNo, "m99000000001", "acc01").get().path("found").asBoolean());

            var dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", "jdbc:mysql://127.0.0.1:3307/ry-vue?serverTimezone=Asia/Shanghai", "root", "root");
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("dev", new JdbcTransactionFactory(), dataSource));
            configuration.addMapper(GzJpSnapAccountMapper.class);
            try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(false)) {
                // The configured gateway account is acc01; there is no account seed in 101.
                // Insert a transaction-local integration account only if it does not already exist.
                try (var statement = session.getConnection().prepareStatement("INSERT INTO gz_jp_snap_account (tenant_id,account_code,nickname,admin_enabled,create_by,create_time) SELECT '1001','acc01','IT301',0,1,NOW() WHERE NOT EXISTS (SELECT 1 FROM gz_jp_snap_account WHERE tenant_id='1001' AND account_code='acc01')")) {
                    statement.executeUpdate();
                }
                var mapper = session.getMapper(GzJpSnapAccountMapper.class);
                try (var tenant = mockStatic(TenantHelper.class)) {
                    tenant.when(() -> TenantHelper.dynamic(eq("1001"), any(Runnable.class)))
                        .thenAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; });
                    new GzJpSnapHealthJob(client, properties, mapper, mock(GzJpSnapRiskControl.class)).heartbeat();
                }
                var account = mapper.selectHealthAccounts().stream().filter(row -> row.getAccountCode().equals("acc01")).findFirst().orElseThrow();
                assertEquals("healthy", account.getHealthStatus());
                assertNotNull(account.getLastHeartbeatAt());
                System.out.println("REAL_GATEWAY driver=fake search=query-signed purchase=body-signed reconcile=found; REAL_DB tenant=1001 account=acc01 health=healthy heartbeat=non-null (transaction rolled back)");
                session.rollback();
            }
        } finally { executor.shutdownNow(); }
    }
}
