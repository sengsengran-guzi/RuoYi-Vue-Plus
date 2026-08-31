package org.dromara.gz.recycle.service.impl;

import org.apache.ibatis.annotations.Select;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 活跃状态集「多份物理拷贝」一致性守卫（GZ-RECYCLE-012 / ADR-0021 坑位 2）。
 *
 * <p><b>为什么需要机械守卫</b>：MyBatis 的 {@code @Select} 注解无法引用 Java 常量，所以
 * {@code ACTIVE_HOLD_STATUSES} 在代码里存在 <b>3 份</b>物理拷贝：</p>
 * <ol>
 *   <li>{@code GzRecycleAppointmentServiceImpl.ACTIVE_HOLD_STATUSES}（可用性 / 周看板读路径用）</li>
 *   <li>{@code countActiveCoveringHourForUpdate} 的 SQL 内联字面量（防超卖写路径）</li>
 *   <li>{@code countActiveCoveringHourExcludingForUpdate} 的 SQL 内联字面量（改期写路径）</li>
 * </ol>
 *
 * <p>漏改任一处的后果不对称：<b>读路径少一个态 = mp 显示可约但提交被拒</b>（可容忍）；
 * <b>写路径少一个态 = 漏拦 = 真超卖</b>（不可容忍，且线上很难发现 —— 只在特定状态的单存在时才复现）。
 * 人工 code review 挡不住这类遗漏，本测是唯一的机械闸门。</p>
 *
 * <p>{@code USER_ACTIVE_STATUSES}（一人一单守卫）同理有 2 份拷贝，一并守。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-012)
 */
@Tag("dev")
class GzRecycleActiveStatusConsistencyTest {

    /** 从 SQL 里抠出 {@code status IN ('a','b',...)} 的状态字面量。 */
    private static final Pattern STATUS_IN = Pattern.compile("status\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    @Test
    @DisplayName("★ ACTIVE_HOLD_STATUSES 的 3 份拷贝完全一致（漏改写路径 = 漏拦 = 真超卖）")
    void activeHoldStatuses_allThreeCopiesMatch() throws Exception {
        List<String> javaConstant = readStringListConstant(
            GzRecycleAppointmentServiceImpl.class, "ACTIVE_HOLD_STATUSES");
        assertTrue(javaConstant.size() >= 5, "活跃态至少 5 个，读到的是：" + javaConstant);

        assertSqlStatusesMatch("countActiveCoveringHourForUpdate", javaConstant);
        assertSqlStatusesMatch("countActiveCoveringHourExcludingForUpdate", javaConstant);
    }

    @Test
    @DisplayName("USER_ACTIVE_STATUSES 的 2 份拷贝一致（改口径不同步 → /active 预检与 submit 拦截分叉）")
    void userActiveStatuses_bothCopiesMatch() throws Exception {
        List<String> javaConstant = readStringListConstant(
            GzRecycleAppointmentServiceImpl.class, "USER_ACTIVE_STATUSES");
        assertSqlStatusesMatch("countActiveByUserForUpdate", javaConstant);
    }

    @Test
    @DisplayName("两个状态集刻意不同：一人一单守卫不含 paid（拿到钱即结清可再约）")
    void twoStatusSets_areIntentionallyDifferent() throws Exception {
        List<String> hold = readStringListConstant(GzRecycleAppointmentServiceImpl.class, "ACTIVE_HOLD_STATUSES");
        List<String> user = readStringListConstant(GzRecycleAppointmentServiceImpl.class, "USER_ACTIVE_STATUSES");
        assertTrue(hold.contains("paid"), "占格集必须含 paid —— 已打款但当天仍占着小时格");
        assertTrue(!user.contains("paid"), "一人一单集刻意不含 paid —— 拿到钱即结清，可再约");
        assertTrue(hold.contains("manual_hold"), "占格集必须含 manual_hold —— 店员手动占用也挡下单");
        assertTrue(!user.contains("manual_hold"), "一人一单集不含 manual_hold —— 手动占用行 user_id 恒 NULL");
    }

    @Test
    @DisplayName("★ 一人一单集不含 confirmed_onsite —— 含了 = 卖过一次的老顾客永久约不了第二单（GZ-RECYCLE-018）")
    void userActiveStatuses_mustNotContainConfirmedOnsite() throws Exception {
        List<String> user = readStringListConstant(GzRecycleAppointmentServiceImpl.class, "USER_ACTIVE_STATUSES");
        assertTrue(!user.contains("confirmed_onsite"),
            "客户 7.15 改店内现金结算后 confirmed_onsite 即终态（核对完顾客当场拿钱，不再进 paying/paid）。"
                + "把它算作「进行中」会让每个成功卖过一次东西的老顾客永久撞 4127 —— 线上已实际发生过。"
                + "当前集合=" + user);
        // 资金真在途的两态必须保留，否则这个修就从「解封老顾客」变成「拆掉资金守卫」
        assertTrue(user.contains("paying"), "paying 是钱在途，必须仍拦");
        assertTrue(user.contains("payout_failed"), "payout_failed 是打款失败待处理，必须仍拦");
        assertTrue(user.contains("submitted"), "submitted 是真·进行中，必须仍拦");
    }

    /* ---------------- helpers ---------------- */

    @SuppressWarnings("unchecked")
    private List<String> readStringListConstant(Class<?> clazz, String fieldName) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (List<String>) f.get(null);
    }

    private void assertSqlStatusesMatch(String mapperMethod, List<String> expected) {
        String sql = readSelectSql(mapperMethod);
        Matcher m = STATUS_IN.matcher(sql);
        if (!m.find()) {
            fail("在 " + mapperMethod + " 的 SQL 里找不到 status IN (...)：" + sql);
        }
        Matcher q = QUOTED.matcher(m.group(1));
        List<String> fromSql = new java.util.ArrayList<>();
        while (q.find()) {
            fromSql.add(q.group(1));
        }
        assertEquals(expected.size(), fromSql.size(),
            mapperMethod + " 的状态个数与 Java 常量不符（改口径必须同步所有拷贝）。"
                + "Java=" + expected + " SQL=" + fromSql);
        for (String status : expected) {
            assertTrue(fromSql.contains(status),
                mapperMethod + " 的 SQL 缺少状态 '" + status + "'（漏拦 = 超卖）。SQL=" + fromSql);
        }
    }

    private String readSelectSql(String methodName) {
        for (Method m : GzRecycleAppointmentMapper.class.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            Select select = m.getAnnotation(Select.class);
            if (select != null) {
                return String.join(" ", select.value());
            }
        }
        return fail("找不到带 @Select 的 mapper 方法：" + methodName);
    }
}
