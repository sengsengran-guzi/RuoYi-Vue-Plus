package org.dromara.gz.coupon.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.domain.bo.GzUserQueryBo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.GzUserCouponQueryBo;
import org.dromara.gz.coupon.domain.vo.GzUserCouponVO;
import org.dromara.gz.coupon.service.IGzUserCouponService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-COUPON-001 用户券（发放记录）查询 + 发放页用户检索（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/coupon/userCoupon}。</p>
 *
 * <p>权限（DDL menu_id 12002 + 按钮 12020~12022）：</p>
 * <ul>
 *   <li>{@code gz:coupon:userCoupon:list} — 发放记录列表</li>
 *   <li>{@code gz:coupon:user:search} — 发放页选用户检索（复用 gz_user 列表）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/coupon/userCoupon")
public class GzUserCouponController extends BaseController {

    private final IGzUserCouponService userCouponService;
    private final IGzUserService userService;

    /** 分页查询发放记录（回填 templateName / userNickname / userMobile）。 */
    @SaCheckPermission("gz:coupon:userCoupon:list")
    @GetMapping("/list")
    public TableDataInfo<GzUserCouponVO> list(GzUserCouponQueryBo query, PageQuery pageQuery) {
        return userCouponService.selectPage(query, pageQuery);
    }

    /**
     * 发放页选用户检索（复用 gz_user admin 列表分页，按昵称 / 手机号 / openid 筛）。
     *
     * <p>独立 perm {@code gz:coupon:user:search}（仅 owner），不复用 gz:user:list（避免给优惠券 owner
     * 自动放开整个用户管理域权限）。</p>
     */
    @SaCheckPermission("gz:coupon:user:search")
    @GetMapping("/userOptions")
    public TableDataInfo<GzUserVO> userOptions(GzUserQueryBo query, PageQuery pageQuery) {
        return userService.selectPageList(query, pageQuery);
    }
}
