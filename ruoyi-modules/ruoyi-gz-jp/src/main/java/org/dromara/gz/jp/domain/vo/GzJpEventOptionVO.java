package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 场<b>轻量选项</b> VO —— 给「按场筛选」下拉、商品表单的所属场选择器、以及商品列表的场信息回填用。
 *
 * <p>由 {@code IGzJpEventService.selectOptions() / selectOptionMap()} 产出：
 * 商品侧只需要「场叫什么 + 现在是不是 open」，不需要整份 {@link GzJpEventAdminVO}。
 * 走事件服务而不是让商品服务直接读场表，是为了让「读时惰性状态判定」只有一处实现
 * （{@code GzJpEventStatus.effective}，FLOW:F-JP-01.step4）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Data
public class GzJpEventOptionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 场主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 场编号 EVT-yyyyMMdd-6位 */
    private String eventNo;

    /** 场名称 */
    private String name;

    /** <b>生效状态</b>（读时惰性判定后的 draft / open / closed），非存库原始值 */
    private String status;
}
