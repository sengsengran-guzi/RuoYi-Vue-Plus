package org.dromara.gz.news.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资讯列表项 VO（mp {@code GET /app/gz/news/article/list} + 首页 AC8 资讯卡）。
 *
 * <p>字段权威：doc/11 §5.1。列表项<b>不含</b> content_html（正文仅详情拉，省流量）、
 * 不含 share_count（NEWS-002 强约束 #4：埋点字段不暴露 C 端）。</p>
 *
 * <p>ID 跨层契约 #1：Long id 用 {@link ToStringSerializer} 序列化为 string，避免 JS 精度丢失。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
@Data
public class GzNewsArticleListVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 ART-yyyyMMdd-6位序号 */
    private String articleNo;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 分类 code（mp 端用此映射 i18n key，不读 dict_label） */
    private String categoryCode;

    /** 封面可渲染 URL */
    private String coverUrl;

    /** 是否有视频（video_urls 非空 → true，列表卡显示视频角标） */
    private Boolean hasVideo;

    /** 阅读量 */
    private Long readCount;

    /** 置顶 0否/1是 */
    private Integer isPinned;

    /** 发布时间（按此 desc 排） */
    private LocalDateTime publishTime;
}
