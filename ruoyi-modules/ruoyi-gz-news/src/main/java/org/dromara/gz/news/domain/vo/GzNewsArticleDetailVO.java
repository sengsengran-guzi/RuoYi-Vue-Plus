package org.dromara.gz.news.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资讯详情 VO（mp {@code GET /app/gz/news/article/{id}}）。
 *
 * <p>字段权威：doc/11 §5.1。含 content_html（已白名单清洗）+ videoUrls 拆为 List。
 * <b>不含</b> share_count（NEWS-002 强约束 #4：埋点字段不暴露 C 端）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
@Data
public class GzNewsArticleDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 */
    private String articleNo;

    /** 标题 */
    private String title;

    /** 摘要（分享卡片用） */
    private String summary;

    /** 分类 code（mp 端用此映射 i18n key） */
    private String categoryCode;

    /** 封面可渲染 URL（分享卡片 imageUrl） */
    private String coverUrl;

    /** 富文本正文 HTML（已服务端白名单清洗，mp rich-text 渲染） */
    private String contentHtml;

    /** 视频 URL 列表（逗号分隔串拆分；mp 端原生 video 组件渲染） */
    private List<String> videoUrls;

    /** 阅读量 */
    private Long readCount;

    /** 发布时间 */
    private LocalDateTime publishTime;
}
