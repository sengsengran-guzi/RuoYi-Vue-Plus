package org.dromara.gz.bean.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanFreePromo;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * gz_bean_free_promo 视图对象（admin 配置回显）。
 *
 * <p>字段权威：doc/11 §3.11。admin 端配置页回显完整字段；mp 端促销提示走
 * {@link GzBeanFreePromoStatusVO}（不暴露内部配置）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
@Data
@AutoMapper(target = GzBeanFreePromo.class)
public class GzBeanFreePromoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** 门店名（admin 列表 enrich 用；AutoMapper 无对应 entity 字段不参与映射） */
    private String storeName;

    /** 周期类型 day / week / days */
    private String periodType;

    /** days 滚动周期天数 N */
    private Integer periodDays;

    /** days 滚动周期锚点起算日 */
    private LocalDate anchorDate;

    /** 每周期免费名额 N */
    private Integer freeCount;

    /** 促销窗口起 */
    private LocalDate startDate;

    /** 促销窗口止 */
    private LocalDate endDate;

    /** 总开关 0=关 / 1=开 */
    private Integer enabled;

    /** 创建时间（公共字段） */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;
}
