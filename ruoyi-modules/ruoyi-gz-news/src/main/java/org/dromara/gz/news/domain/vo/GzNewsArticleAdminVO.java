package org.dromara.gz.news.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * 资讯文章 <b>admin 端</b> VO（GZ-NEWS-003，列表 + 详情共用）。
 *
 * <p>与 mp 端 {@link GzNewsArticleListVO} / {@link GzNewsArticleDetailVO} 区分：admin 需看
 * <b>全状态</b>（draft/scheduled/published/offline）+ 编辑回填的<b>全字段</b>（含 content_html /
 * schedule_publish_time / sort_no / cover_file_id），mp VO 仅 published 子集。</p>
 *
 * <p>字段权威：doc/11 §5.1。ID 跨层契约 #1：Long 序列化为 string（admin 列表也用 string 一致）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-003)
 */
@Data
public class GzNewsArticleAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 ART-yyyyMMdd-6位序号（系统生成，admin 只读） */
    private String articleNo;

    /** 标题 */
    private String title;

    /** 摘要（空时 mp 截正文前 80 字） */
    private String summary;

    /** 分类 code（admin 走 sys_dict gz_news_category 回显 label） */
    private String categoryCode;

    /** 封面可渲染 URL */
    private String coverUrl;

    /** 封面 file_id（admin 上传链路关联，序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long coverFileId;

    /** 富文本正文 HTML（已白名单清洗存档；列表查询不投影，详情才返回） */
    private String contentHtml;

    /** 视频 URL 逗号分隔（admin 表单原样回填，≤ 5 个） */
    private String videoUrls;

    /** 状态 draft/scheduled/published/offline（附录 A.4） */
    private String status;

    /** 实际发布时间（published 时写） */
    private LocalDateTime publishTime;

    /** 定时发布 due 时间（scheduled 时写，NEWS-004 cron 用） */
    private LocalDateTime schedulePublishTime;

    /** 阅读量（admin 只读） */
    private Long readCount;

    /** 分享量（admin 可见，mp 不可见） */
    private Long shareCount;

    /** 置顶 0否/1是 */
    private Integer isPinned;

    /** 同分类内排序（倒序） */
    private Integer sortNo;

    /** 创建时间（继承自 TenantEntity，类型 java.util.Date — admin 列表显示用） */
    private Date createTime;

    /** 更新时间（同上） */
    private Date updateTime;

    /** 备注 */
    private String remark;
}
