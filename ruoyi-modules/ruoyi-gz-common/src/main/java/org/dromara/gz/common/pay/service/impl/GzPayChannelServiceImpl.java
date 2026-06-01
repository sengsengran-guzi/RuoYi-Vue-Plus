package org.dromara.gz.common.pay.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.domain.vo.GzPayChannelVO;
import org.dromara.gz.common.pay.mapper.GzPayChannelMapper;
import org.dromara.gz.common.pay.service.IGzPayChannelService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 支付通道配置服务实现（GZ-PAY-001，admin 只读）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Service
@RequiredArgsConstructor
public class GzPayChannelServiceImpl implements IGzPayChannelService {

    private final GzPayChannelMapper channelMapper;

    @Override
    public List<GzPayChannelVO> listChannels() {
        List<GzPayChannelVO> list = channelMapper.selectVoList(Wrappers.emptyWrapper());
        // mch_id 脱敏（强约束 #4，admin 详情不暴露完整商户号）
        for (GzPayChannelVO vo : list) {
            vo.setMchId(maskMchId(vo.getMchId()));
        }
        return list;
    }

    private String maskMchId(String mchId) {
        if (mchId == null || mchId.length() < 6) {
            return "******";
        }
        return mchId.substring(0, 2) + "****" + mchId.substring(mchId.length() - 2);
    }
}
