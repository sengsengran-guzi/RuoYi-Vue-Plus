package org.dromara.gz.news.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 资讯文章增改业务对象（GZ-NEWS-003 admin 端）。
 *
 * <p>字段口径权威：doc/11 §5.1；validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：title / summary / categoryCode / coverUrl / coverFileId /
 * contentHtml / videoUrls / isPinned / sortNo / remark。
 * <b>系统管理字段</b>（不接收前端，service 内部赋值）：articleNo（系统生成）/ status（走 publish/
 * schedule/offline 流转方法，不允许直接改）/ publishTime / schedulePublishTime（走 schedule 方法）/
 * readCount / shareCount / tenantId / 公共字段。</p>
 *
 * <p><b>content_html 强约束 #1</b>：service 层落库前必过 {@code HtmlSanitizer.sanitize}，本 BO 仅承载原始入参。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-003)
 */
@Data
public class GzNewsArticleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "文章 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 标题 */
    @NotBlank(message = "标题不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "标题长度不能超过 128", groups = {AddGroup.class, EditGroup.class})
    private String title;

    /** 摘要（空时 mp 截正文前 80 字；admin 可不填） */
    @Size(max = 255, message = "摘要长度不能超过 255", groups = {AddGroup.class, EditGroup.class})
    private String summary;

    /** 分类 code（new_product/activity/guide/announcement，枚举校验在 service） */
    @NotBlank(message = "分类不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 32, message = "分类 code 长度不能超过 32", groups = {AddGroup.class, EditGroup.class})
    private String categoryCode;

    /** 封面可渲染 URL（mp 渲染真源 + 分享卡 imageUrl） */
    @Size(max = 512, message = "封面 URL 长度不能超过 512", groups = {AddGroup.class, EditGroup.class})
    private String coverUrl;

    /** 封面 file_id（admin 上传后回填，可空） */
    private Long coverFileId;

    /** 富文本正文 HTML（落库前 service 过 sanitize；编辑器输出） */
    @NotBlank(message = "正文不能为空", groups = {AddGroup.class, EditGroup.class})
    private String contentHtml;

    /** 视频 URL 逗号分隔（V1.0 仅录 URL 不上传文件，≤ 5 个；service 校验数量） */
    @Size(max = 1024, message = "视频 URL 总长度不能超过 1024", groups = {AddGroup.class, EditGroup.class})
    private String videoUrls;

    /** 置顶 0否/1是（不填默认 0） */
    @Pattern(regexp = "^[01]$", message = "置顶仅支持 0 / 1", groups = {AddGroup.class, EditGroup.class})
    private String isPinned;

    /** 同分类内排序（倒序，不填默认 0） */
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
