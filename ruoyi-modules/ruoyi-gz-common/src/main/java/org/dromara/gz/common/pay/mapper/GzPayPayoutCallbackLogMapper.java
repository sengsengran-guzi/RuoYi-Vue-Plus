package org.dromara.gz.common.pay.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutCallbackLog;

/**
 * gz_pay_payout_callback_log 数据层（GZ-PAY-105，doc/11 §4.9）。
 *
 * <p>纯审计表，BaseMapperPlus CRUD 足够（仅 insert + 查询）。多租户 / 软删由拦截器处理。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
public interface GzPayPayoutCallbackLogMapper extends BaseMapperPlus<GzPayPayoutCallbackLog, GzPayPayoutCallbackLog> {
}
