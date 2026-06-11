package org.dromara.gz.common.pay.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * mp 支付状态轮询窄 VO（GZ-PAY-001 result.vue 轮询，对齐 mp PayTransactionStatusVO 契约）。
 *
 * <p>仅暴露轮询必需字段：{@code id / outTradeNo / businessType / amountCent / status /
 * transactionId / paidTime}。<b>刻意剔除</b> {@code openid（PII）/ prepayId（支付凭据）/
 * userId / feeCent / channelCode} 等敏感/内部字段 —— 防 IDOR 越权读 PII（纵深防御，
 * 配合 GzPayMpController 的归属校验）。</p>
 *
 * @author kevin-coder (sensenran-guzi · D16 B2 IDOR 收口)
 */
@Data
public class GzPayStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String outTradeNo;

    private String businessType;

    /** 金额（分）；mp / 100 显示元 */
    private Long amountCent;

    /** pending / paid / timeout / closed / refunded ... */
    private String status;

    /** 微信交易号（仅本人单，已付才有）；mp 选用字段 */
    private String transactionId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paidTime;

    /** 从完整交易 VO 投影窄 VO（不拷 openid/prepayId/userId/feeCent）。 */
    public static GzPayStatusVO from(GzPayTransactionVO src) {
        GzPayStatusVO vo = new GzPayStatusVO();
        vo.setId(src.getId());
        vo.setOutTradeNo(src.getOutTradeNo());
        vo.setBusinessType(src.getBusinessType());
        vo.setAmountCent(src.getAmountCent());
        vo.setStatus(src.getStatus());
        vo.setTransactionId(src.getTransactionId());
        vo.setPaidTime(src.getPaidTime());
        return vo;
    }
}
