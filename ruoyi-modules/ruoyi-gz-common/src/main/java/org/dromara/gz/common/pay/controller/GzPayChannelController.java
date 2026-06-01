package org.dromara.gz.common.pay.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.pay.domain.vo.GzPayChannelVO;
import org.dromara.gz.common.pay.service.IGzPayChannelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-PAY-001 admin 端支付通道配置（只读，决策 D3）。
 *
 * <p>路径前缀 {@code /system/gz/pay/channel}。权限 {@code gz:pay:channel:list}（menu_id 5101，仅 owner）。
 * V1.0 仅展示不编辑 —— prod 字段走 env var，admin 编辑无意义且配置错后果严重。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/pay/channel")
public class GzPayChannelController extends BaseController {

    private final IGzPayChannelService channelService;

    /** 通道列表（mch_id 脱敏；V1.0 仅 wechat_pay_v3 一行） */
    @SaCheckPermission("gz:pay:channel:list")
    @GetMapping("/list")
    public R<List<GzPayChannelVO>> list() {
        return R.ok(channelService.listChannels());
    }
}
