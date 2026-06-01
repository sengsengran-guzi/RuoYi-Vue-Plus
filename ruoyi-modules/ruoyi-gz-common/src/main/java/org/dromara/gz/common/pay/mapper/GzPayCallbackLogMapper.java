package org.dromara.gz.common.pay.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.vo.GzPayCallbackLogVO;

import java.util.List;

/**
 * gz_pay_callback_log 数据层（GZ-PAY-001）。纯审计表，仅插入 + 按订单号查列表。
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public interface GzPayCallbackLogMapper extends BaseMapperPlus<GzPayCallbackLog, GzPayCallbackLogVO> {

    /**
     * 按 out_trade_no 查回调日志（admin 订单详情内展示，时间倒序）。
     *
     * @param outTradeNo 业务订单号
     * @return 回调日志 VO 列表
     */
    @Select("SELECT id, transaction_id, out_trade_no, callback_type, raw_body, process_status, process_error, create_time " +
        "FROM gz_pay_callback_log WHERE out_trade_no = #{outTradeNo} AND del_flag = '0' " +
        "ORDER BY id DESC")
    List<GzPayCallbackLogVO> selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);
}
