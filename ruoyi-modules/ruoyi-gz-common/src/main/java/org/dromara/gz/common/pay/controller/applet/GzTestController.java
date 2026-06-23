package org.dromara.gz.common.pay.controller.applet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.config.GzTestProperties;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.pay.domain.bo.GzPayTestCreateBo;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * mp 真机自测入口（GZ 测试页后端）—— 供 Kevin 真机验证微信原生能力（真实付款 / 给用户打款）。
 *
 * <p><b>非业务页</b>，四道安全闸：</p>
 * <ol>
 *   <li><b>总开关</b> {@link GzTestProperties#isEnabled()}（env {@code GZ_TEST_ENABLED}，默认 false）—— 关则拒绝</li>
 *   <li>{@link SaCheckLogin} 必须登录</li>
 *   <li>金额锁死 {@link #TEST_AMOUNT_CENT}=1 分（付款 / 打款都只允许 1 分）</li>
 *   <li>openid 取当前登录用户自己（付款付自己、打款打给自己，不可代他人）</li>
 * </ol>
 *
 * <p>小程序侧「测试」tab 仅体验版显示（{@code VITE_SHOW_TEST_TAB}），正式版不出现。
 * 以后新增自测能力（打款 / 客服 / 扫码等）在本控制器加端点 + 测试页加区块。</p>
 *
 * @author kevin-coder (sensenran-guzi · 真机自测页)
 */
@Slf4j
@SaCheckLogin
@RequiredArgsConstructor
@RestController
@RequestMapping("/app/gz/test")
public class GzTestController {

    /** 自测金额硬上限：1 分（付款 / 打款统一） */
    private static final long TEST_AMOUNT_CENT = 1L;

    private final GzTestProperties testProperties;
    private final IGzPayTransactionService transactionService;
    private final GzUserMapper gzUserMapper;

    /**
     * 发起 1 分真机测试支付单（business_type=test），返回 mp {@code uni.requestPayment} 5 参。
     *
     * <p>真机付款成功 → 微信回调 /api/pay/v3/notify → 订单转 paid（test 单无业务 handler，dispatcher 跳过）。
     * 测试页轮询 {@code /app/gz/pay/transaction/{outTradeNo}/status} 看 paid。</p>
     */
    @PostMapping("/pay/create-order")
    public R<MpPayParamsVO> createPayOrder() {
        assertEnabled();
        Long userId = LoginHelper.getUserId();
        String openid = currentOpenid(userId);
        GzPayTestCreateBo bo = new GzPayTestCreateBo();
        bo.setAmountCent(TEST_AMOUNT_CENT);
        bo.setOpenid(openid);
        log.info("[gz-test] 真机自测付款 1分 userId={} openid={}", userId, mask(openid));
        return R.ok(transactionService.createTestOrder(bo, userId));
    }

    private void assertEnabled() {
        if (!testProperties.isEnabled()) {
            throw new ServiceException("自测入口未开启（gz.test.enabled=false）");
        }
    }

    private String currentOpenid(Long userId) {
        GzUser user = gzUserMapper.selectById(userId);
        if (user == null || user.getOpenid() == null || user.getOpenid().isBlank()) {
            throw new ServiceException("当前登录用户无 openid，无法发起自测（请先微信登录）");
        }
        return user.getOpenid();
    }

    private static String mask(String s) {
        if (s == null || s.length() < 6) {
            return "******";
        }
        return s.substring(0, 3) + "****" + s.substring(s.length() - 2);
    }
}
