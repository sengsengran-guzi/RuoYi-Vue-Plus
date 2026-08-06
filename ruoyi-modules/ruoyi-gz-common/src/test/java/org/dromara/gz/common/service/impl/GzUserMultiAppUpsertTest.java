package org.dromara.gz.common.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GZ-SYS-023 accept[STATE]：<b>同 openid 不同 app 可共存（两条独立记录）</b>。
 *
 * <p>验的是 ADR-0019 §4 那条真正要命的性质：openid 是 <b>appid 维度</b>的标识，微信只保证它在单个
 * appid 内唯一。若 {@code upsertByOpenid} 的查询条件漏了 {@code app_id}，两个小程序的 openid 一旦撞上，
 * 第二个小程序的用户会<b>直接登进第一个小程序某人的账号</b>（拿到别人的订单 / 券 / 会员权益）——
 * 数据串户级事故。</p>
 *
 * <p><b>为什么不是普通的 mock 断言</b>：{@code when(selectOne(any())).thenReturn(null)} 这种写法
 * 无论 wrapper 里带没带 app_id 都会「通过」，照不出漏条件。所以这里用一张<b>按 (app_id, openid) 索引的
 * 内存假表</b>顶替 mapper：{@code selectOne} 真的按 service 传进来的 wrapper 参数去查它，
 * {@code insert} 真的写进它。漏掉 app_id 条件的实现会在「B 小程序同 openid 登录」时命中 A 的行 →
 * 走 UPDATE 分支 → 断言「两条独立记录」立即失败。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-023)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GzUserMultiAppUpsertTest {

    /** 谷子宇宙（现小程序）—— prod 真 appid。 */
    private static final String APPID_GUZI = "wx2f8b93e09703f07d";
    /** 日本拼团（新小程序）—— 真 appid 到手后的形态。 */
    private static final String APPID_JP = "wxJP0000000000jp";
    /** 两个小程序对同一自然人下发的 openid 恰好相同（微信不保证跨 appid 不撞）。 */
    private static final String SAME_OPENID = "o-collision-same-person";

    @Mock
    private GzUserMapper baseMapper;

    @Mock
    private IGzFileService gzFileService;

    private GzUserServiceImpl gzUserService;

    /** 内存假表：key = "appId|openid"（= 迁移后的唯一键去掉 tenant_id 那维，tenant 由拦截器管）。 */
    private final Map<String, GzUser> table = new LinkedHashMap<>();
    /** 每次 selectOne 收到的 wrapper 归档，用于断言查询条件真的含 app_id。 */
    private final List<LambdaQueryWrapper<GzUser>> capturedWrappers = new ArrayList<>();

    @BeforeAll
    static void initLambdaCache() {
        // wrapper.getSqlSegment() 要把 GzUser::getAppId 解析成列名 app_id，需要 MP 的 TableInfo 缓存；
        // 纯 Mockito 单测不装 MP，这里手动 init（幂等、无副作用）。
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), GzUser.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gzUserService = new GzUserServiceImpl(baseMapper, gzFileService);
        table.clear();
        capturedWrappers.clear();
        AtomicLong seq = new AtomicLong(1000L);

        org.mockito.Mockito.when(baseMapper.selectOne(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                LambdaQueryWrapper<GzUser> w = (LambdaQueryWrapper<GzUser>) inv.getArgument(0);
                // ★ MP 的 wrapper 参数是**懒物化**的：eq() 只存了个 ISqlSegment lambda，
                //   paramNameValuePairs 要等 getSqlSegment() 跑一遍才有值。不先调它就永远读到空 map。
                String sql = w.getSqlSegment();
                capturedWrappers.add(w);
                // generateUserNo 也走 selectOne（likeRight user_no）—— 它不按 openid 查，返 null 即可
                if (!sql.contains("openid")) {
                    return null;
                }
                return lookup(sql, w.getParamNameValuePairs().values().toArray());
            });
        org.mockito.Mockito.when(baseMapper.insert(org.mockito.ArgumentMatchers.any(GzUser.class)))
            .thenAnswer(inv -> {
                GzUser u = inv.getArgument(0);
                u.setId(seq.incrementAndGet());
                assertNotNull(u.getAppId(), "INSERT 必须带 app_id（DB 该列 NOT NULL）");
                String key = key(u.getAppId(), u.getOpenid());
                assertFalse(table.containsKey(key), "唯一键 (app_id, openid) 冲突：" + key);
                table.put(key, u);
                return 1;
            });
        org.mockito.Mockito.when(baseMapper.updateById(org.mockito.ArgumentMatchers.any(GzUser.class)))
            .thenReturn(1);
    }

    /**
     * 按 wrapper 的 (app_id, openid) 约束查假表。
     *
     * <p>关键：{@code sql} 里没有 {@code app_id} 时（= 实现漏了 app 维度），退化成「只按 openid 查」，
     * 于是会命中<b>另一个小程序</b>的行 —— 让串户在断言里暴露出来，而不是被 mock 掩盖成通过。</p>
     */
    private GzUser lookup(String sql, Object[] paramValues) {
        boolean appConstrained = sql.contains("app_id");
        for (Map.Entry<String, GzUser> e : table.entrySet()) {
            GzUser u = e.getValue();
            if (!contains(paramValues, u.getOpenid())) {
                continue;
            }
            if (!appConstrained || contains(paramValues, u.getAppId())) {
                return u;
            }
        }
        return null;
    }

    private static boolean contains(Object[] values, String target) {
        for (Object v : values) {
            if (target != null && target.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private static String key(String appId, String openid) {
        return appId + "|" + openid;
    }

    private static MiniappApp app(String clientId, String appid, String registerSource) {
        MiniappApp a = new MiniappApp();
        a.setClientId(clientId);
        a.setAppid(appid);
        a.setRegisterSource(registerSource);
        return a;
    }

    private static WxJscode2SessionResult session(String openid) {
        return WxJscode2SessionResult.builder().openid(openid).sessionKey("sk").build();
    }

    // ============================================================
    //  accept[STATE] 主断言
    // ============================================================

    @Test
    @DisplayName("★ accept：同一 openid 在两个小程序登录 → 两条独立 gz_user（不串户）")
    void sameOpenid_twoApps_createsTwoIndependentRows() {
        MiniappApp guzi = app("mp-applet-sensenran-guzi", APPID_GUZI, "mp_wechat");
        MiniappApp jp = app("mp-applet-gz-jp", APPID_JP, "mp_wechat_jp");

        GzUser inGuzi = gzUserService.upsertByOpenid(session(SAME_OPENID), "谷子宇宙用户", "a1.png", guzi);
        GzUser inJp = gzUserService.upsertByOpenid(session(SAME_OPENID), "拼团用户", "a2.png", jp);

        assertEquals(2, table.size(), "同 openid 不同 app 必须是两条独立记录（1 条 = 串户）");
        assertNotEquals(inGuzi.getId(), inJp.getId(), "两个小程序的用户不能共用同一个 user id");

        assertEquals(APPID_GUZI, inGuzi.getAppId());
        assertEquals(APPID_JP, inJp.getAppId());
        assertEquals(SAME_OPENID, inGuzi.getOpenid());
        assertEquals(SAME_OPENID, inJp.getOpenid());

        // 各自的资料互不覆盖（串户时第二次登录会把第一条的昵称改掉）
        assertEquals("谷子宇宙用户", table.get(key(APPID_GUZI, SAME_OPENID)).getNickname());
        assertEquals("拼团用户", table.get(key(APPID_JP, SAME_OPENID)).getNickname());
    }

    @Test
    @DisplayName("同一小程序内同 openid 二次登录 → 仍是同一条（UPDATE，不重复建号）")
    void sameOpenid_sameApp_updatesSingleRow() {
        MiniappApp guzi = app("mp-applet-sensenran-guzi", APPID_GUZI, "mp_wechat");

        GzUser first = gzUserService.upsertByOpenid(session(SAME_OPENID), "初次", "a.png", guzi);
        GzUser second = gzUserService.upsertByOpenid(session(SAME_OPENID), "二次", "b.png", guzi);

        assertEquals(1, table.size(), "同 app 同 openid 只能有一条");
        assertEquals(first.getId(), second.getId(), "二次登录必须复用同一 user id");
        assertEquals("二次", second.getNickname());
        assertEquals(first.getRegisterTime(), second.getRegisterTime(), "registerTime 不因再次登录而变");
    }

    @Test
    @DisplayName("查询条件真的带 app_id —— SQL 片段含 app_id 与 openid 两个约束")
    void upsertQuery_constrainsBothAppIdAndOpenid() {
        MiniappApp jp = app("mp-applet-gz-jp", APPID_JP, "mp_wechat_jp");

        gzUserService.upsertByOpenid(session(SAME_OPENID), "n", "a", jp);

        LambdaQueryWrapper<GzUser> upsertWrapper = capturedWrappers.stream()
            .filter(w -> w.getParamNameValuePairs().containsValue(SAME_OPENID))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有一次 selectOne 是按 openid 查的"));

        String sql = upsertWrapper.getSqlSegment();
        assertTrue(sql.contains("app_id"), "UPSERT 查询条件必须含 app_id，实际：" + sql);
        assertTrue(sql.contains("openid"), "UPSERT 查询条件必须含 openid，实际：" + sql);
        assertTrue(upsertWrapper.getParamNameValuePairs().containsValue(APPID_JP),
            "app_id 的取值必须是本次登录小程序的 appid");
    }

    @Test
    @DisplayName("register_source 按小程序区分（dev 两个 app 同为 wxMOCK 时的唯一来源标识）")
    void registerSource_isPerApp() {
        // dev 现状：两个小程序 appid 都是 wxMOCK，app_id 列此时分不出来源，只能靠 register_source
        MiniappApp devGuzi = app("mp-applet-sensenran-guzi", WxMiniappProperties.MOCK_APPID, "mp_wechat");
        MiniappApp devJp = app("mp-applet-gz-jp", WxMiniappProperties.MOCK_APPID, "mp_wechat_jp");

        GzUser u1 = gzUserService.upsertByOpenid(session("o-dev-tester"), "n", "a", devGuzi);
        GzUser u2 = gzUserService.upsertByOpenid(session("o-jp-tester"), "n", "a", devJp);

        assertEquals("mp_wechat", u1.getRegisterSource(), "现小程序保持历史取值（存量与线上零变化）");
        assertEquals("mp_wechat_jp", u2.getRegisterSource());
    }

    @Test
    @DisplayName("单值配置形态（prod 现状）注册来源仍是 mp_wechat —— 现有小程序行为零变化")
    void legacySingleValueConfig_keepsHistoricalRegisterSource() {
        MiniappApp legacy = new WxMiniappProperties().resolveDefaultApp();

        assertEquals("mp_wechat", legacy.getRegisterSource());
        assertEquals("mp-applet-sensenran-guzi", legacy.getClientId());
    }

    @Test
    @DisplayName("app 缺失 / appid 为空 → IllegalArgumentException（NOT NULL 列不许写空，也不许静默回落）")
    void blankApp_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> gzUserService.upsertByOpenid(session(SAME_OPENID), "n", "a", null));
        assertThrows(IllegalArgumentException.class,
            () -> gzUserService.upsertByOpenid(session(SAME_OPENID), "n", "a",
                app("mp-applet-gz-jp", "  ", "mp_wechat_jp")));
        assertTrue(table.isEmpty(), "校验不通过时不得落库");
    }
}
