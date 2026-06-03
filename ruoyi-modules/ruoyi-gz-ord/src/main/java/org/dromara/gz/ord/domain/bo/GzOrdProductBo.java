package org.dromara.gz.ord.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 预购商品增改业务对象（GZ-ORD-101 admin 端，含嵌套 SKU 列表）。
 *
 * <p>字段口径权威：doc/11 §6.1；商品 + SKU 同事务（决策 D1）。validate 分组：{@link AddGroup} 新增 /
 * {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：name / mainImageId / galleryImageIds / descriptionHtml（service 层
 * 落库前白名单清洗）/ ipTag / deadlineTime / deliveryDateText|deliveryDateExact（二选一，业务层校验 F6.1）/
 * sortNo / skuList（≥1）。<b>系统管理字段</b>：productNo（系统生成）/ status（走 changeStatus 流转，
 * 不允许直接改；新增固定 off_shelf）/ salesCount / version / 公共字段。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Data
public class GzOrdProductBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "商品 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 商品名 */
    @NotBlank(message = "商品名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "商品名长度不能超过 128", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 主图 file_id（→gz_file_object.id，禁裸 url；可空） */
    private Long mainImageId;

    /** 图集 逗号分隔 file_id（可空） */
    @Size(max = 512, message = "图集长度不能超过 512", groups = {AddGroup.class, EditGroup.class})
    private String galleryImageIds;

    /** 商品详情富文本 HTML（落库前 service 过 sanitize；可空） */
    private String descriptionHtml;

    /** IP/作品标签（可空） */
    @Size(max = 64, message = "IP 标签长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String ipTag;

    /** 预订截止时间（必填，过此点不可下单） */
    @NotNull(message = "截止时间不能为空", groups = {AddGroup.class, EditGroup.class})
    private LocalDateTime deadlineTime;

    /** 模糊到货日（如「8 月下旬」）；与 deliveryDateExact 二选一（F6.1 业务层校验） */
    @Size(max = 64, message = "到货日文案长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String deliveryDateText;

    /** 精确到货日；与 text 二选一 */
    private LocalDate deliveryDateExact;

    /** 同 IP 内排序（不填默认 0） */
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;

    /** SKU 列表（至少一个，决策 D1 单规格也建一条；编辑时按 id diff 增删改） */
    @Valid
    @NotEmpty(message = "至少需要一个规格（SKU）", groups = {AddGroup.class, EditGroup.class})
    private List<GzOrdSkuBo> skuList;
}
