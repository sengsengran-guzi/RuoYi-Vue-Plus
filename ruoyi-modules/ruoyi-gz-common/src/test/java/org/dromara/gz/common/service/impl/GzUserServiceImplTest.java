package org.dromara.gz.common.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.bo.GzUserQueryBo;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GzUserServiceImpl} 单测。
 *
 * <p>覆盖任务卡 AC 7（happy + 分页）：</p>
 * <ul>
 *   <li>upsertByOpenid 新建用户：mapper.selectOne 返 null → mapper.insert 调用 + 字段值正确</li>
 *   <li>upsertByOpenid 二次更新：mapper.selectOne 返 existing → mapper.updateById 调用 + lastLoginTime 刷新 + registerTime 不变</li>
 *   <li>selectPageList：query 包含 openid 过滤 → mapper.selectVoPage 调用 + 返回 TableDataInfo</li>
 *   <li>selectVoById null safety：id=null 直接返回 null</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzUserServiceImplTest {

    @Mock
    private GzUserMapper baseMapper;

    @Mock
    private IGzFileService gzFileService;

    private GzUserServiceImpl gzUserService;

    /** 现小程序（谷子宇宙）—— 单值配置形态解析出来的那一个，register-source 继承全局默认 mp_wechat。 */
    private MiniappApp app;

    @BeforeEach
    void setUp() {
        gzUserService = new GzUserServiceImpl(baseMapper, gzFileService);
        app = new WxMiniappProperties().resolveDefaultApp();
    }

    @Test
    @DisplayName("upsertByOpenid 新用户 → mapper.insert 调用 + 字段值正确")
    void upsertByOpenid_newUser_insertsWithCorrectFields() {
        WxJscode2SessionResult session = WxJscode2SessionResult.builder()
            .openid("openid-new-001")
            .unionid("union-new-001")
            .sessionKey("sk-new-001")
            .build();
        when(baseMapper.selectOne(any())).thenReturn(null);
        when(baseMapper.insert(any(GzUser.class))).thenAnswer(invocation -> {
            GzUser u = invocation.getArgument(0);
            u.setId(101L);
            return 1;
        });

        GzUser result = gzUserService.upsertByOpenid(session, "新用户昵称", "https://cdn.wx/avatar.jpg", app);

        assertNotNull(result);
        assertEquals(101L, result.getId());
        assertEquals("openid-new-001", result.getOpenid());
        assertEquals("union-new-001", result.getUnionid());
        assertEquals("新用户昵称", result.getNickname());
        assertEquals("https://cdn.wx/avatar.jpg", result.getAvatarUrl());
        assertEquals(0, result.getGender());
        assertEquals(app.getAppid(), result.getAppId(), "GZ-SYS-023：新用户必须落所属小程序 appid");
        assertEquals("mp_wechat", result.getRegisterSource());
        assertEquals("authorized", result.getStatus());
        assertEquals(0, result.getIsDisabled());
        assertNotNull(result.getRegisterTime());
        assertNotNull(result.getLastLoginTime());
        assertNotNull(result.getUserNo());
        assertTrue(result.getUserNo().startsWith("U"), "userNo should start with U: " + result.getUserNo());

        verify(baseMapper).insert(any(GzUser.class));
        verify(baseMapper, never()).updateById(any(GzUser.class));
    }

    @Test
    @DisplayName("upsertByOpenid 新用户 + 空 nickname → fallback '微信用户'")
    void upsertByOpenid_newUser_blankNickname_usesFallback() {
        WxJscode2SessionResult session = WxJscode2SessionResult.builder()
            .openid("openid-fb-001")
            .sessionKey("sk")
            .build();
        when(baseMapper.selectOne(any())).thenReturn(null);
        when(baseMapper.insert(any(GzUser.class))).thenAnswer(invocation -> {
            GzUser u = invocation.getArgument(0);
            u.setId(102L);
            return 1;
        });

        GzUser result = gzUserService.upsertByOpenid(session, "", "", app);

        assertEquals("微信用户", result.getNickname());
    }

    @Test
    @DisplayName("upsertByOpenid 已存在用户 → updateById + lastLoginTime 刷新 + registerTime 不变")
    void upsertByOpenid_existingUser_updatesAndPreservesRegisterTime() {
        LocalDateTime oldTime = LocalDateTime.now().minusDays(30);
        GzUser existing = GzUser.builder()
            .id(202L)
            .userNo("U20260501000001")
            .openid("openid-existing")
            .unionid(null)
            .nickname("旧昵称")
            .avatarUrl("https://old.wx/avatar.jpg")
            .gender(0)
            .registerSource("mp_wechat")
            .registerTime(oldTime)
            .lastLoginTime(oldTime)
            .status("authorized")
            .isDisabled(0)
            .build();
        existing.setTenantId("1001");

        WxJscode2SessionResult session = WxJscode2SessionResult.builder()
            .openid("openid-existing")
            .unionid("union-fresh-bind")
            .sessionKey("sk-existing")
            .build();
        when(baseMapper.selectOne(any())).thenReturn(existing);
        when(baseMapper.updateById(any(GzUser.class))).thenReturn(1);

        GzUser result = gzUserService.upsertByOpenid(session, "新昵称", "https://new.wx/avatar.jpg", app);

        assertEquals(202L, result.getId());
        assertEquals("新昵称", result.getNickname());
        assertEquals("https://new.wx/avatar.jpg", result.getAvatarUrl());
        assertEquals("union-fresh-bind", result.getUnionid(), "首次返回 unionid 应被补齐");
        assertNotEquals(oldTime, result.getLastLoginTime(), "lastLoginTime 应被刷新");
        assertEquals(oldTime, result.getRegisterTime(), "registerTime 应保持不变");

        verify(baseMapper).updateById(any(GzUser.class));
        verify(baseMapper, never()).insert(any(GzUser.class));
    }

    @Test
    @DisplayName("upsertByOpenid 已存在用户 + unionid 已有值 → 不覆盖")
    void upsertByOpenid_existingUser_unionidNotOverwritten() {
        GzUser existing = GzUser.builder()
            .id(203L)
            .openid("openid-stable-union")
            .unionid("union-original")
            .nickname("nm")
            .status("authorized")
            .isDisabled(0)
            .build();
        WxJscode2SessionResult session = WxJscode2SessionResult.builder()
            .openid("openid-stable-union")
            .unionid("union-different")
            .sessionKey("sk")
            .build();
        when(baseMapper.selectOne(any())).thenReturn(existing);
        when(baseMapper.updateById(any(GzUser.class))).thenReturn(1);

        GzUser result = gzUserService.upsertByOpenid(session, "x", "y", app);

        assertEquals("union-original", result.getUnionid(), "已有 unionid 不应被新值覆盖");
    }

    @Test
    @DisplayName("upsertByOpenid 空 openid → IllegalArgumentException")
    void upsertByOpenid_blankOpenid_throws() {
        WxJscode2SessionResult session = WxJscode2SessionResult.builder()
            .openid("")
            .sessionKey("sk")
            .build();
        assertThrows(IllegalArgumentException.class,
            () -> gzUserService.upsertByOpenid(session, "n", "a", app));
    }

    @Test
    @DisplayName("selectPageList: query 含 openid → mapper.selectVoPage 调用 + 返 TableDataInfo")
    @SuppressWarnings("unchecked")
    void selectPageList_withOpenidFilter_returnsTableDataInfo() {
        GzUserQueryBo q = new GzUserQueryBo();
        q.setOpenid("openid-prefix");
        PageQuery pageQuery = new PageQuery(10, 1);

        GzUserVO vo = new GzUserVO();
        vo.setId(1L);
        vo.setOpenid("openid-prefix-xxx");
        Page<GzUserVO> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(vo));

        when(baseMapper.selectVoPage(any(), any(Wrapper.class))).thenReturn((IPage) page);

        TableDataInfo<GzUserVO> result = gzUserService.selectPageList(q, pageQuery);

        assertNotNull(result);
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRows().size());
        assertEquals("openid-prefix-xxx", result.getRows().get(0).getOpenid());

        // wrapper 由 service 内部构造，验证 mapper 收到的是非 null wrapper
        ArgumentCaptor<Wrapper<GzUser>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectVoPage(any(), wrapperCaptor.capture());
        assertNotNull(wrapperCaptor.getValue());
    }

    @Test
    @DisplayName("selectVoById null → 直接返 null（null safety）")
    void selectVoById_nullId_returnsNull() {
        assertNull(gzUserService.selectVoById(null));
        verify(baseMapper, never()).selectVoById(any(java.io.Serializable.class));
    }

    @Test
    @DisplayName("selectVoById 正常（无头像 image_id）→ 透传 mapper 结果，不调文件服务")
    void selectVoById_validId_returnsMapperResult() {
        GzUserVO vo = new GzUserVO();
        vo.setId(501L);
        // avatarImageId == null → 不触发签名 URL 重生成
        when(baseMapper.selectVoById(501L)).thenReturn(vo);

        GzUserVO result = gzUserService.selectVoById(501L);

        assertNotNull(result);
        assertEquals(501L, result.getId());
        verify(gzFileService, never()).getPresignedUrl(any());
    }

    @Test
    @DisplayName("GZ-USER-006：selectVoById 有 avatar_image_id → 按 image_id 重生成签名 URL 回填 avatarUrl")
    void selectVoById_withAvatarImageId_resolvesPresignedUrl() {
        GzUserVO vo = new GzUserVO();
        vo.setId(502L);
        vo.setAvatarImageId(99L);
        vo.setAvatarUrl("https://stale-cache/old.png"); // 旧缓存，应被重生成的签名 URL 覆盖
        when(baseMapper.selectVoById(502L)).thenReturn(vo);

        GzFileObjectVO file = new GzFileObjectVO();
        file.setFileId(99L);
        file.setUrl("https://cos-signed/avatar.png?sign=fresh");
        when(gzFileService.getPresignedUrl(99L)).thenReturn(file);

        GzUserVO result = gzUserService.selectVoById(502L);

        assertNotNull(result);
        assertEquals("https://cos-signed/avatar.png?sign=fresh", result.getAvatarUrl());
        verify(gzFileService, times(1)).getPresignedUrl(99L);
    }

    @Test
    @DisplayName("GZ-USER-006：头像签名失败（文件被删 / COS 异常）→ 不抛错，保留旧缓存 URL")
    void selectVoById_avatarResolveFails_doesNotThrow() {
        GzUserVO vo = new GzUserVO();
        vo.setId(503L);
        vo.setAvatarImageId(88L);
        vo.setAvatarUrl("https://fallback/cache.png");
        when(baseMapper.selectVoById(503L)).thenReturn(vo);
        when(gzFileService.getPresignedUrl(88L)).thenThrow(new RuntimeException("file deleted"));

        GzUserVO result = assertDoesNotThrow(() -> gzUserService.selectVoById(503L));

        // 资料读取不因头像失败而 500：保留库里旧 avatar_url 缓存
        assertEquals("https://fallback/cache.png", result.getAvatarUrl());
    }
}
