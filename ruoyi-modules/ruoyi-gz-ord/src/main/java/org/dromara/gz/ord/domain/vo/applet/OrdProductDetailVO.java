package org.dromara.gz.ord.domain.vo.applet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * mp 商品详情展示对象（GZ-ORD-103 AC1）。
 *
 * <p>字段口径权威：doc/11 §6.1 gz_ord_product + §6.2 gz_ord_sku。图片 FK（main_image_id /
 * gallery_image_ids）后端解析为可访问签名 URL（NULL → 占位图，R4）—— 前端<b>不接触裸 file_id</b>。
 * {@code descriptionHtml} 为 admin 富文本编辑器白名单清洗后的存档原文，前端用 {@code rich-text} 渲染
 * （决策 D5，强约束 #7）。{@code deadlineTime} ISO8601 输出配合外层 {@code serverNow} 倒计时校准
 * （决策 D2，复用 ORD-102 useCountdown）。</p>
 *
 * <p>ID 跨层契约（CLAUDE.md 跨层契约 #1）：{@code id} 用 {@link ToStringSerializer} 转 string。
 * <b>不返回 main_image_id / gallery_image_ids 裸列、不新增 view_count（§6.1 无该字段，强约束 #1 / R7）</b>。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-103)
 */
@Data
public class OrdProductDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商品名 */
    private String name;

    /** 主图可访问 URL（由 main_image_id 经 gz_file_object 解析；NULL → 占位图，R4） */
    private String mainImageUrl;

    /** 图集 URL 数组（gallery_image_ids 逗号分隔逐个解析；无图 → 空数组，轮播只用 mainImageUrl） */
    private List<String> galleryImageUrls;

    /** 商品详情富文本 HTML（已白名单清洗，前端 rich-text 渲染；可空） */
    private String descriptionHtml;

    /** IP/作品标签 */
    private String ipTag;

    /** 预订截止时间（ISO8601，前端倒计时基准；配合外层 serverNow 校准） */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime deadlineTime;

    /** 到货日文案（delivery_date_exact 优先 yyyy-MM-dd，否则 delivery_date_text，都空则空串） */
    private String deliveryText;

    /** 状态 on_shelf / off_shelf / auto_off */
    private String status;

    /** 销量（展示用） */
    private Long salesCount;

    /** SKU 列表（sort_no 升序；含停用 SKU 前端置灰；起始价 = enabled MIN priceCent 由前端算） */
    private List<OrdSkuMpVO> skus;

    /** 服务器当前时间（ISO8601；前端倒计时校准基准，与 ORD-102 列表口径一致，决策 D2） */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime serverNow;
}
