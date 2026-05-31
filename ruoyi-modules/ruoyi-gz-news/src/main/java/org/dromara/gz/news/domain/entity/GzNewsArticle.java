package org.dromara.gz.news.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_news_article — 资讯文章 entity（GZ-NEWS-001）。
 *
 * <p>字段口径权威：doc/11 §5.1 gz_news_article + §1 全局公共字段。</p>
 *
 * <p><b>与 ticket 卡假设差异（以 doc/11 §5.1 为准）</b>：
 * category_code（非 category_id，走 sys_dict gz_news_category，不建 category 表 §5.2）/
 * content_html（非 content）/ video_urls 逗号分隔（非 video_url）/ publish_time（非 publish_at）/
 * read_count（非 view_count）/ status 4 态（draft/scheduled/published/offline）。</p>
 *
 * <p><b>cover_url 决策</b>：doc/11 用 cover_file_id（FK→file_object，admin 上传链路 NEWS-003）。
 * V1.0 mp 仅读 + 分享卡需直接可渲染 URL → 落 cover_url 直存可渲染串作为 mp 真源；cover_file_id 预留
 * admin 关联。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_news_article")
public class GzNewsArticle extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 ART-yyyyMMdd-6位序号 — UNIQUE(tenant_id, article_no) */
    private String articleNo;

    /** 文章标题 */
    private String title;

    /** 摘要（空时 mp 截取正文前 80 字） */
    private String summary;

    /** 分类 new_product/activity/guide/announcement（走 sys_dict gz_news_category） */
    private String categoryCode;

    /** 封面图可渲染 URL（mp 渲染真源 + 分享卡 imageUrl） */
    private String coverUrl;

    /** 封面 FK→gz_file_object.id（admin 上传链路 NEWS-003 关联，V1.0 可空） */
    private Long coverFileId;

    /** 富文本正文 HTML（白名单清洗后存档） */
    private String contentHtml;

    /** 视频 URL 逗号分隔（V1.0 不转码，≤ 5 个） */
    private String videoUrls;

    /** 状态 draft/scheduled/published/offline（doc/10 §5 状态机 / 附录 A.4） */
    private String status;

    /** 实际发布时间（published 时写） */
    private LocalDateTime publishTime;

    /** 定时发布 due 时间（NEWS-004 cron 用） */
    private LocalDateTime schedulePublishTime;

    /** 阅读量（mp 进详情 +1，V1.0 不防刷） */
    private Long readCount;

    /** 分享量（NEWS-002 埋点 +1，不给 mp 用户看） */
    private Long shareCount;

    /** 置顶 0否/1是（置顶排序优先） */
    private Integer isPinned;

    /** 同分类内排序（倒序） */
    private Integer sortNo;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi） */
    @TableLogic
    private String delFlag;
}
