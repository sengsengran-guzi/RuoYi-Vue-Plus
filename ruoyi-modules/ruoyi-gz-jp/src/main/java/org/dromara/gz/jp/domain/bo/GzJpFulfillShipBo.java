package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量发货入参（GZ-JP-106，FLOW:F-JP-03.step3）。
 *
 * <pre>
 * POST /system/gz/jp/fulfill/ship
 * { "itemIds": ["12","13"], "carrierCode": "sf", "trackingNo": "SF1234567890" }
 * </pre>
 *
 * <p><b>★ carrierCode 与 trackingNo 必填，缺一即拒</b>（4109）——
 * 「发货完毕」是终态且客人要靠单号自查物流，没有单号的 delivered 是坏数据。</p>
 *
 * <p><b>★ 不建包裹表</b>：{@code trackingNo} 本身就是包裹标识，同单号的行天然属同一包裹。
 * 因此一次发货的行<b>必须属于同一个客人</b>（一个包裹只有一个收件人），否则 4110。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Data
public class GzJpFulfillShipBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 一起发出的订单商品行主键（可跨订单，但必须同一客人） */
    @NotEmpty(message = "请至少选择一行商品")
    @Size(max = 200, message = "单次最多发货 200 行，请分批处理")
    private List<Long> itemIds;

    /**
     * 国内快递公司编码（字典 gz_express_carrier：sf / yto / zto / yunda / jd / ems / debang / jitu / other）。
     *
     * <p><b>刻意不加 {@code @NotBlank}</b>：必填由 service 统一抛 <b>4109</b>。
     * 交给 bean validation 的话前端拿到的是框架的 500 校验错，与文档承诺的 4109 对不上，
     * 108 会照着错的码写分支。校验仍在读库之前，没有「先发货再补单号」的窗口。</p>
     */
    private String carrierCode;

    /** 国内运单号（员工手工填，不接快递 API；客人自行复制到快递公司查询）。必填同上，由 service 抛 4109 */
    private String trackingNo;
}
