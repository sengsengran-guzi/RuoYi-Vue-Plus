package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 商品 <b>admin 端</b> VO（GZ-JP-102，列表 + 详情共用，UI:admin.product）。
 *
 * <p>ID 跨层契约：所有 Long 主键 / 外键序列化为 string（JS number 精度丢失）。
 * <b>金额例外</b>：{@link #priceCent} 是「分」的整数，量级远低于 JS 安全整数，保持 number
 * 便于前端直接做元/分换算 —— 与 gz-ord / gz-recycle 既有 admin VO 一致。</p>
 *
 * <p><b>★ 一期没有库存 / SKU 字段</b>（field-ssot 明确，accept 断言无 stock 列）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Data
public class GzJpProductAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商品编号 JPP-yyyyMMdd-6位（系统生成，admin 只读） */
    private String productNo;

    /** 所属场主键 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long eventId;

    /** 所属场编号（列表回填；场已被删则为 null） */
    private String eventNo;

    /** 所属场名称（列表回填；场已被删则为 null） */
    private String eventName;

    /**
     * 所属场<b>生效状态</b>（draft / open / closed，读时惰性判定）。
     *
     * <p>配合 {@link #visibleToCustomer} 解释「我明明上架了客人却看不到」——
     * FLOW:F-JP-01.step2 明确「商品 status=on_shelf；<b>未开场时仍不可见</b>」。</p>
     */
    private String eventStatus;

    /** 商品名称 */
    private String name;

    /** 主图 file id（admin 走 file id + 缩略图组件回显，不在列表逐条预签名） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mainImageId;

    /** 图集 file id 列表（落库是逗号分隔串，出参展开成数组） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> galleryImageIds;

    /** 售价（<b>分</b>）★ 全包邮，此价即客人最终支付价 */
    private Long priceCent;

    /** 预计到货时间（文本） */
    private String deliveryDateText;

    /** 额外注意事项（REQ-PROD-005） */
    private String noticeText;

    /** 商品状态 on_shelf / off_shelf（字典 gz_jp_product_status） */
    private String status;

    /**
     * 客人此刻是否真的看得到 = 商品 on_shelf <b>且</b> 所属场生效状态 open。
     *
     * <p>纯派生字段，不落库；admin 列表用它给「已上架但场没开」打提示，避免店员误判。</p>
     */
    private Boolean visibleToCustomer;

    /** 场内排序号，越小越前 */
    private Integer sortNo;

    /** 乐观锁版本号 */
    private Integer version;

    /** 创建时间（继承自 TenantEntity，类型 java.util.Date） */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注（内部用，不下发 mp） */
    private String remark;
}
