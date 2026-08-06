package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 商品增改业务对象（GZ-JP-102 admin 端，FLOW:F-JP-01.step2）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_product} 段。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：eventId / name / mainImageId / galleryImageIds / priceCent /
 * deliveryDateText / noticeText / sortNo / remark。<br>
 * <b>系统管理字段</b>（不接前端）：productNo（系统生成）/ status（只能走 {@code /status} 批量上下架端点，
 * 不允许直接 PUT 改）/ version / 公共字段。</p>
 *
 * <p><b>价格单位是「分」</b>：前端负责元→分换算（{@code Math.round(yuan * 100)}），后端只认整数分，
 * 杜绝浮点误差进库。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Data
public class GzJpProductBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "商品 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 所属场 FK→gz_jp_event.id（service 校验该场存在） */
    @NotNull(message = "请选择所属场", groups = {AddGroup.class, EditGroup.class})
    private Long eventId;

    /** 商品名称 */
    @NotBlank(message = "商品名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "商品名称长度不能超过 128", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 主图 file id（gz_file_object.id；DDL NOT NULL，必传） */
    @NotNull(message = "请上传商品主图", groups = {AddGroup.class, EditGroup.class})
    private Long mainImageId;

    /**
     * 图集 file id 列表（一期用图集承载商品详情，不做富文本）。
     *
     * <p>入参用 List，落库前 service 逗号 join 成串（gallery_image_ids VARCHAR(512)），
     * 与 gz_recycle_appointment.verify_image_ids 既有做法一致。</p>
     */
    private List<Long> galleryImageIds;

    /** 售价（<b>分</b>）★ 全包邮，此价即客人最终支付价，不叠加运费（REQ-ORDER-004） */
    @NotNull(message = "售价不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 1, message = "售价必须大于 0", groups = {AddGroup.class, EditGroup.class})
    private Long priceCent;

    /** 预计到货时间（文本，如「8月下旬」） */
    @Size(max = 64, message = "预计到货时间长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String deliveryDateText;

    /** 额外注意事项（REQ-PROD-005；下单前须显著展示，不混进图集） */
    @Size(max = 1024, message = "注意事项长度不能超过 1024", groups = {AddGroup.class, EditGroup.class})
    private String noticeText;

    /** 场内排序号，越小越前（不填按 0） */
    private Integer sortNo;

    /** 备注（内部用，不下发 mp） */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
