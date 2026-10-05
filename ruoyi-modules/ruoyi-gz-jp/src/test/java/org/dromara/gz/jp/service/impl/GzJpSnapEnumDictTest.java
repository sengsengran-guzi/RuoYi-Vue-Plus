package org.dromara.gz.jp.service.impl;

import org.dromara.gz.jp.domain.enums.GzJpItemSource;
import org.dromara.gz.jp.domain.enums.GzJpRefundType;
import org.dromara.gz.jp.domain.enums.GzJpSnapAccountHealth;
import org.dromara.gz.jp.domain.enums.GzJpSnapFailReason;
import org.dromara.gz.jp.domain.enums.GzJpSnapGateResult;
import org.dromara.gz.jp.domain.enums.GzJpSnapMode;
import org.dromara.gz.jp.domain.enums.GzJpSnapPurchaseStatus;
import org.dromara.gz.jp.domain.enums.GzJpSnapTradeStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GZ-SNAP-102：代切枚举取值与 GZ-SNAP-101 字典迁移<b>一一对应</b>（防漂移）。
 *
 * <p>真源是已应用的迁移 {@code V202610050725__GZ-SNAP-101-seed-dict.sql}（append-only，不会再改），
 * 直接解析它的 {@code sys_dict_data} 行，比把取值再抄一遍进测试更不容易两边一起错。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Tag("dev")
@DisplayName("GZ-SNAP-102 代切枚举 ⇔ 字典")
class GzJpSnapEnumDictTest {

    private static final Path SEED = Path.of("..", "..", "ruoyi-admin", "src", "main", "resources", "db",
        "migration", "V202610050725__GZ-SNAP-101-seed-dict.sql");

    /** dict_type → (dict_value → dict_label)，保持迁移里的顺序 */
    private static final Map<String, Map<String, String>> DICT = new LinkedHashMap<>();

    @BeforeAll
    static void parseSeed() throws Exception {
        assertTrue(Files.exists(SEED), "找不到字典迁移：" + SEED.toAbsolutePath());
        String sql = Files.readString(SEED, StandardCharsets.UTF_8);
        // (dict_code, '000000', dict_sort, 'label', 'value', 'type', ...
        Matcher m = Pattern.compile("\\(\\d+,\\s*'000000',\\s*\\d+,\\s*'([^']*)',\\s*'([^']*)',\\s*'(gz_jp_[a-z_]+)'")
            .matcher(sql);
        while (m.find()) {
            DICT.computeIfAbsent(m.group(3), k -> new LinkedHashMap<>()).put(m.group(2), m.group(1));
        }
        assertTrue(DICT.size() >= 12, "字典解析不完整：" + DICT.keySet());
    }

    @Test
    @DisplayName("★ 6 个代切枚举 + 退款类型：取值与字典完全一致（多一个少一个都红）")
    void enumsMatchDict() {
        assertMatches("gz_jp_snap_purchase_status", GzJpSnapPurchaseStatus.values(), GzJpSnapPurchaseStatus::getCode,
            GzJpSnapPurchaseStatus::getLabel);
        assertMatches("gz_jp_snap_trade_status", GzJpSnapTradeStatus.values(), GzJpSnapTradeStatus::getCode,
            GzJpSnapTradeStatus::getLabel);
        assertMatches("gz_jp_snap_gate", GzJpSnapGateResult.values(), GzJpSnapGateResult::getCode,
            GzJpSnapGateResult::getLabel);
        assertMatches("gz_jp_snap_mode", GzJpSnapMode.values(), GzJpSnapMode::getCode, GzJpSnapMode::getLabel);
        assertMatches("gz_jp_snap_fail_reason", GzJpSnapFailReason.values(), GzJpSnapFailReason::getCode,
            GzJpSnapFailReason::getLabel);
        assertMatches("gz_jp_snap_account_health", GzJpSnapAccountHealth.values(), GzJpSnapAccountHealth::getCode,
            GzJpSnapAccountHealth::getLabel);
        assertMatches("gz_jp_refund_type", GzJpRefundType.values(), GzJpRefundType::getCode, GzJpRefundType::getLabel);
    }

    @Test
    @DisplayName("订单行来源：snap 与字典一致，batch 仍在")
    void itemSourceHasSnap() {
        assertEquals("代切", DICT.get("gz_jp_item_source").get(GzJpItemSource.SNAP.getCode()));
        assertEquals(GzJpItemSource.SNAP, GzJpItemSource.of("snap"));
        assertEquals(GzJpItemSource.BATCH, GzJpItemSource.of("batch"));
        assertNull(GzJpItemSource.of("SNAP"), "大小写敏感，不做宽松匹配");
    }

    private static <E> void assertMatches(String dictType, E[] values, Function<E, String> code,
                                          Function<E, String> label) {
        Map<String, String> dict = DICT.get(dictType);
        assertTrue(dict != null && !dict.isEmpty(), "迁移里没有字典 " + dictType);
        Set<String> enumCodes = Arrays.stream(values).map(code).collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(dict.keySet(), enumCodes, dictType + " 取值不一致");
        for (E v : values) {
            assertEquals(dict.get(code.apply(v)), label.apply(v), dictType + "." + code.apply(v) + " 文案不一致");
        }
        System.out.println("[枚举⇔字典] " + dictType + " " + enumCodes);
    }
}
