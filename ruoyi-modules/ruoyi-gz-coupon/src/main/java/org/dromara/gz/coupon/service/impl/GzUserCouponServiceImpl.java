package org.dromara.gz.coupon.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.GzUserCouponQueryBo;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;
import org.dromara.gz.coupon.domain.vo.GzUserCouponVO;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.dromara.gz.coupon.service.IGzUserCouponService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户券（发放记录）查询服务实现（GZ-COUPON-001 AC 3，admin 端）。
 *
 * <p>回填 templateName（查 gz_coupon_template）+ userNickname / userMobile（IGzUserService 批量取，防 N+1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzUserCouponServiceImpl implements IGzUserCouponService {

    private final GzUserCouponMapper baseMapper;
    private final GzCouponTemplateMapper templateMapper;
    private final IGzUserService userService;

    @Override
    public TableDataInfo<GzUserCouponVO> selectPage(GzUserCouponQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzUserCoupon> lqw = Wrappers.<GzUserCoupon>lambdaQuery()
            .eq(ObjectUtil.isNotNull(query.getTemplateId()), GzUserCoupon::getTemplateId, query.getTemplateId())
            .eq(ObjectUtil.isNotNull(query.getUserId()), GzUserCoupon::getUserId, query.getUserId())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzUserCoupon::getStatus, query.getStatus())
            .like(StrUtil.isNotBlank(query.getCouponNo()), GzUserCoupon::getCouponNo, query.getCouponNo())
            .orderByDesc(GzUserCoupon::getCreateTime);
        Page<GzUserCoupon> page = baseMapper.selectPage(pageQuery.build(), lqw);

        List<GzUserCoupon> records = page.getRecords();
        // 批量回填 templateName / userNickname / userMobile（防 N+1）
        Map<Long, String> templateNameMap = resolveTemplateNames(records);
        Map<Long, GzUserVO> userMap = resolveUsers(records);

        Page<GzUserCouponVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records.stream()
            .map(e -> toVO(e, templateNameMap, userMap))
            .toList());
        return TableDataInfo.build(voPage);
    }

    private Map<Long, String> resolveTemplateNames(List<GzUserCoupon> records) {
        Set<Long> templateIds = records.stream()
            .map(GzUserCoupon::getTemplateId)
            .filter(ObjectUtil::isNotNull)
            .collect(Collectors.toSet());
        if (templateIds.isEmpty()) {
            return Map.of();
        }
        List<GzCouponTemplate> templates = templateMapper.selectByIds(templateIds);
        return templates.stream()
            .collect(Collectors.toMap(GzCouponTemplate::getId, GzCouponTemplate::getName, (a, b) -> a));
    }

    private Map<Long, GzUserVO> resolveUsers(List<GzUserCoupon> records) {
        Set<Long> userIds = records.stream()
            .map(GzUserCoupon::getUserId)
            .filter(ObjectUtil::isNotNull)
            .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userService.selectVoMapByIds(userIds);
    }

    private GzUserCouponVO toVO(GzUserCoupon e, Map<Long, String> templateNameMap, Map<Long, GzUserVO> userMap) {
        GzUserCouponVO vo = new GzUserCouponVO();
        vo.setId(e.getId());
        vo.setCouponNo(e.getCouponNo());
        vo.setTemplateId(e.getTemplateId());
        vo.setTemplateName(templateNameMap.get(e.getTemplateId()));
        vo.setUserId(e.getUserId());
        GzUserVO user = userMap.get(e.getUserId());
        if (user != null) {
            vo.setUserNickname(user.getNickname());
            vo.setUserMobile(user.getMobile());
        }
        vo.setAmountSnapshotCent(e.getAmountSnapshotCent());
        vo.setStatus(e.getStatus());
        vo.setGainedTime(e.getGainedTime());
        vo.setExpireTime(e.getExpireTime());
        vo.setUsedTime(e.getUsedTime());
        vo.setRelatedPayOutTradeNo(e.getRelatedPayOutTradeNo());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }
}
