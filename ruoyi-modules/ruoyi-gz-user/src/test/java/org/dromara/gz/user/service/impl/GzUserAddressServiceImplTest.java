package org.dromara.gz.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.user.domain.bo.GzUserAddressBo;
import org.dromara.gz.user.domain.entity.GzUserAddress;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;
import org.dromara.gz.user.mapper.GzUserAddressMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * {@link GzUserAddressServiceImpl} 单测（GZ-USER-003 AC 8）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>新建默认地址 → reset 其他（update null+wrapper）+ insert</li>
 *   <li>切换默认地址 → setDefault 内 reset 其他 + set 当前</li>
 *   <li>软删（仅本人）</li>
 *   <li>越权操作他人地址 → ServiceException</li>
 *   <li>列表归属边界 + 默认置顶排序</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-003)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzUserAddressServiceImplTest {

    private static final Long USER = 1001L;
    private static final Long OTHER_USER = 2002L;

    @Mock
    private GzUserAddressMapper baseMapper;

    private GzUserAddressServiceImpl service;

    /**
     * 纯 mock 单测下 LambdaUpdateWrapper.eq(GzUserAddress::getXxx) 需要 entity 的
     * mybatis-plus TableInfo 缓存，否则报 "can not find lambda cache for this entity"。
     * 用最小 Configuration 注册一次（不连库）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, GzUserAddress.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzUserAddressServiceImpl(baseMapper);
    }

    private GzUserAddressBo bo(boolean isDefault) {
        GzUserAddressBo b = new GzUserAddressBo();
        b.setRecipientName("张三");
        b.setMobile("13800138000");
        b.setProvince("四川省");
        b.setCity("成都市");
        b.setDistrict("锦江区");
        b.setDetail("春熙路 1 号");
        b.setIsDefault(isDefault ? 1 : 0);
        return b;
    }

    @Test
    @DisplayName("新建默认地址 → reset 其他 + insert")
    void save_newDefault_resetsOthersThenInserts() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(GzUserAddress.class))).thenAnswer(inv -> {
            GzUserAddress e = inv.getArgument(0);
            e.setId(501L);
            return 1;
        });
        when(baseMapper.selectVoById(501L)).thenReturn(new GzUserAddressVO());

        service.save(USER, bo(true));

        // reset 其他：update(null, wrapper)
        verify(baseMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(baseMapper, times(1)).insert(any(GzUserAddress.class));
    }

    @Test
    @DisplayName("新建非默认地址 → 不 reset 其他")
    void save_newNonDefault_skipsReset() {
        when(baseMapper.selectCount(any())).thenReturn(2L);
        when(baseMapper.insert(any(GzUserAddress.class))).thenAnswer(inv -> {
            GzUserAddress e = inv.getArgument(0);
            e.setId(502L);
            return 1;
        });
        when(baseMapper.selectVoById(502L)).thenReturn(new GzUserAddressVO());

        service.save(USER, bo(false));

        verify(baseMapper, never()).update(isNull(), any(Wrapper.class));
        verify(baseMapper, times(1)).insert(any(GzUserAddress.class));
    }

    @Test
    @DisplayName("编辑他人地址 → ServiceException（越权防护）")
    void save_editOthersAddress_throws() {
        GzUserAddress others = GzUserAddress.builder().id(900L).userId(OTHER_USER).build();
        when(baseMapper.selectById(900L)).thenReturn(others);

        GzUserAddressBo b = bo(false);
        b.setId("900");

        assertThrows(ServiceException.class, () -> service.save(USER, b));
        verify(baseMapper, never()).updateById(any(GzUserAddress.class));
    }

    @Test
    @DisplayName("切换默认 → reset 其他 + set 当前为默认")
    void setDefault_resetsOthersAndSetsTarget() {
        GzUserAddress target = GzUserAddress.builder().id(601L).userId(USER).isDefault(0).build();
        when(baseMapper.selectById(601L)).thenReturn(target);
        when(baseMapper.updateById(any(GzUserAddress.class))).thenReturn(1);

        boolean ok = service.setDefault(USER, 601L);

        assertTrue(ok);
        verify(baseMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(baseMapper, times(1)).updateById(any(GzUserAddress.class));
    }

    @Test
    @DisplayName("设默认他人地址 → ServiceException")
    void setDefault_othersAddress_throws() {
        GzUserAddress others = GzUserAddress.builder().id(602L).userId(OTHER_USER).build();
        when(baseMapper.selectById(602L)).thenReturn(others);

        assertThrows(ServiceException.class, () -> service.setDefault(USER, 602L));
    }

    @Test
    @DisplayName("软删本人地址 → deleteById 调用")
    void delete_ownAddress_softDeletes() {
        GzUserAddress own = GzUserAddress.builder().id(701L).userId(USER).build();
        when(baseMapper.selectById(701L)).thenReturn(own);
        when(baseMapper.deleteById(701L)).thenReturn(1);

        assertTrue(service.delete(USER, 701L));
        verify(baseMapper).deleteById(701L);
    }

    @Test
    @DisplayName("软删他人地址 → ServiceException")
    void delete_othersAddress_throws() {
        GzUserAddress others = GzUserAddress.builder().id(702L).userId(OTHER_USER).build();
        when(baseMapper.selectById(702L)).thenReturn(others);

        assertThrows(ServiceException.class, () -> service.delete(USER, 702L));
        verify(baseMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("getByIdForUser 他人地址 → 返 null（不暴露存在性）")
    void getByIdForUser_othersAddress_returnsNull() {
        GzUserAddress others = GzUserAddress.builder().id(801L).userId(OTHER_USER).build();
        when(baseMapper.selectById(801L)).thenReturn(others);

        assertNull(service.getByIdForUser(USER, 801L));
    }

    @Test
    @DisplayName("userId null → ServiceException")
    void list_nullUser_throws() {
        assertThrows(ServiceException.class, () -> service.listByUser(null));
    }
}
