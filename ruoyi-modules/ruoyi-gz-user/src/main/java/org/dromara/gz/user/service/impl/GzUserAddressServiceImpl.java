package org.dromara.gz.user.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.user.domain.bo.GzUserAddressBo;
import org.dromara.gz.user.domain.entity.GzUserAddress;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;
import org.dromara.gz.user.mapper.GzUserAddressMapper;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址簿服务实现（GZ-USER-003）。
 *
 * <p>归属边界：所有写操作先校验 address.userId == 当前 userId（防越权改他人地址）。
 * 默认地址唯一：事务内先 reset 该用户全部 is_default=0，再 set 目标=1（doc/11 §2.2 业务规则）。</p>
 *
 * <p>多租户 / 软删由拦截器自动注入；INSERT 不显式赋 tenant_id（CLAUDE.md §6 #3）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-003)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzUserAddressServiceImpl implements IGzUserAddressService {

    /** 单用户地址数上限（业务层校验，DB 不约束，doc/11 §2.2） */
    private static final long MAX_ADDRESS_PER_USER = 20;

    private final GzUserAddressMapper baseMapper;

    @Override
    public List<GzUserAddressVO> listByUser(Long userId) {
        requireUserId(userId);
        LambdaQueryWrapper<GzUserAddress> lqw = Wrappers.<GzUserAddress>lambdaQuery()
            .eq(GzUserAddress::getUserId, userId)
            .orderByDesc(GzUserAddress::getIsDefault)
            .orderByDesc(GzUserAddress::getCreateTime);
        return baseMapper.selectVoList(lqw);
    }

    @Override
    public GzUserAddressVO getByIdForUser(Long userId, Long id) {
        requireUserId(userId);
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzUserAddress entity = baseMapper.selectById(id);
        if (entity == null || !userId.equals(entity.getUserId())) {
            return null;
        }
        return baseMapper.selectVoById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzUserAddressVO save(Long userId, GzUserAddressBo bo) {
        requireUserId(userId);
        boolean wantDefault = ObjectUtil.equals(bo.getIsDefault(), 1);

        Long id = StrUtil.isNotBlank(bo.getId()) ? parseId(bo.getId()) : null;
        if (id != null) {
            // 编辑路径：校验归属
            GzUserAddress existed = baseMapper.selectById(id);
            if (existed == null || !userId.equals(existed.getUserId())) {
                throw new ServiceException("地址不存在或无权操作");
            }
        }
        else {
            // 新增路径：上限校验
            long count = baseMapper.selectCount(Wrappers.<GzUserAddress>lambdaQuery()
                .eq(GzUserAddress::getUserId, userId));
            if (count >= MAX_ADDRESS_PER_USER) {
                throw new ServiceException("地址数量已达上限（" + MAX_ADDRESS_PER_USER + " 个）");
            }
        }

        // 若设默认 → 先 reset 该用户其他地址为非默认（事务内，doc/11 §2.2）
        if (wantDefault) {
            resetOthersDefault(userId, id);
        }

        GzUserAddress entity = toEntity(bo, userId, id);
        if (id != null) {
            baseMapper.updateById(entity);
            log.info("[gz-user-address] UPDATE id={} userId={} default={}", id, userId, wantDefault);
        }
        else {
            baseMapper.insert(entity);
            id = entity.getId();
            log.info("[gz-user-address] INSERT id={} userId={} default={}", id, userId, wantDefault);
        }
        return baseMapper.selectVoById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long userId, Long id) {
        requireUserId(userId);
        GzUserAddress existed = baseMapper.selectById(id);
        if (existed == null || !userId.equals(existed.getUserId())) {
            throw new ServiceException("地址不存在或无权操作");
        }
        boolean ok = baseMapper.deleteById(id) > 0;
        log.info("[gz-user-address] DELETE id={} userId={} ok={}", id, userId, ok);
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean setDefault(Long userId, Long id) {
        requireUserId(userId);
        GzUserAddress target = baseMapper.selectById(id);
        if (target == null || !userId.equals(target.getUserId())) {
            throw new ServiceException("地址不存在或无权操作");
        }
        // 事务内：先 reset 其他 → 再 set 当前（doc/10 §4.N7）
        resetOthersDefault(userId, id);
        GzUserAddress update = new GzUserAddress();
        update.setId(id);
        update.setIsDefault(1);
        boolean ok = baseMapper.updateById(update) > 0;
        log.info("[gz-user-address] setDefault id={} userId={} ok={}", id, userId, ok);
        return ok;
    }

    /**
     * 把该用户除 exceptId 外的所有地址 is_default 置 0。
     *
     * @param exceptId 排除的地址 id（编辑 / setDefault 时排除目标本身；新增时传 null）
     */
    private void resetOthersDefault(Long userId, Long exceptId) {
        LambdaUpdateWrapper<GzUserAddress> luw = Wrappers.<GzUserAddress>lambdaUpdate()
            .eq(GzUserAddress::getUserId, userId)
            .eq(GzUserAddress::getIsDefault, 1)
            .set(GzUserAddress::getIsDefault, 0);
        if (exceptId != null) {
            luw.ne(GzUserAddress::getId, exceptId);
        }
        baseMapper.update(null, luw);
    }

    private GzUserAddress toEntity(GzUserAddressBo bo, Long userId, Long id) {
        GzUserAddress e = new GzUserAddress();
        e.setId(id);
        e.setUserId(userId);
        e.setRecipientName(bo.getRecipientName());
        e.setMobile(bo.getMobile());
        e.setProvince(bo.getProvince());
        e.setCity(bo.getCity());
        e.setDistrict(bo.getDistrict());
        e.setDetail(bo.getDetail());
        e.setTag(bo.getTag());
        e.setIsDefault(ObjectUtil.defaultIfNull(bo.getIsDefault(), 0));
        return e;
    }

    private static void requireUserId(Long userId) {
        if (ObjectUtil.isNull(userId)) {
            throw new ServiceException("未登录");
        }
    }

    private static Long parseId(String idStr) {
        try {
            return Long.parseLong(idStr);
        }
        catch (NumberFormatException e) {
            throw new ServiceException("非法地址 ID");
        }
    }
}
