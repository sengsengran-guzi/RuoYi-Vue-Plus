package org.dromara.gz.common.pay.service;

import org.dromara.gz.common.pay.domain.vo.GzPayChannelVO;

import java.util.List;

/**
 * 支付通道配置服务（GZ-PAY-001，admin 只读，决策 D3）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public interface IGzPayChannelService {

    /**
     * 通道列表（mch_id 脱敏；V1.0 仅 wechat_pay_v3 一行）。
     */
    List<GzPayChannelVO> listChannels();
}
