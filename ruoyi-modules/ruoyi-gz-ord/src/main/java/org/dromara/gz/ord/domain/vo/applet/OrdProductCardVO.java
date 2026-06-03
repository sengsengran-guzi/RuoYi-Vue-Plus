package org.dromara.gz.ord.domain.vo.applet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * mp 预购列表卡片展示对象（GZ-ORD-102 AC2）。
 *
 * <p>字段口径权威：doc/11 §6.1（展示子集）+ §6.2（起始价取 enabled SKU MIN price_cent）。</p>
 *
 * <p>跨层契约（CLAUDE.md 跨层契约 #1）：{@code id} 用 {@link ToStringSerializer} 转 string，避免
 * Java Long ↔ JS number 精度丢失。<b>不暴露 main_image_id</b>（前端不存裸 file_id），后端直接解析为
 * {@code mainImageUrl} 可访问签名 URL（NULL → 占位图，R4）。{@code deadlineTime} ISO8601 输出供前端
 * 倒计时校准（配合外层 serverNow，决策 D4）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-102)
 */
@Data
public class OrdProductCardVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品主键（string；前端跳详情用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商品名 */
    private String name;

    /** 主图可访问 URL（由 main_image_id 经 gz_file_object 解析；NULL → 占位图，R4） */
    private String mainImageUrl;

    /** 起始价（分）= 该商品所有 enabled=1 SKU 的 MIN(price_cent)；无可售 SKU → null */
    private Long startPriceCent;

    /** IP/作品标签（筛选 + 卡片 chip） */
    private String ipTag;

    /** 预订截止时间（ISO8601，前端倒计时基准；配合外层 serverNow 校准） */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime deadlineTime;

    /** 到货日文案（delivery_date_exact 优先 yyyy-MM-dd，否则 delivery_date_text，都空则空串） */
    private String deliveryText;

    /** 销量（热度排序展示用） */
    private Long salesCount;
}
