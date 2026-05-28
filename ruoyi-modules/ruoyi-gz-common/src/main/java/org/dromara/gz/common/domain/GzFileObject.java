package org.dromara.gz.common.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * gz_file_object 对象存储文件元数据（全局共用 — doc/11 §5.3）
 * <p>
 * 业务表（gz_news_article / gz_user / gz_gacha_prize 等）通过 file_id 关联本表。
 * URL 不在本表存全串（CDN 域名 / OSS 切换时业务表无需 ALTER），渲染时调
 * {@code IGzFileService#getPresignedUrl(Long)} 拿带 1h 过期签名的临时 URL。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_file_object")
public class GzFileObject extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID（不暴露给前端，业务表 file_id 关联本字段） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对象存储 bucket 名 */
    private String bucket;

    /** 对象存储 key（如 news/2026/06/abc.jpg） */
    private String objectKey;

    /** 原文件名 */
    private String fileName;

    /** 字节数（图片 ≤ 10MB） */
    private Long fileSize;

    /** 如 image/jpeg / image/png */
    private String mimeType;

    /** doc/11 §5.3 枚举：news_cover / news_inline / user_avatar / gacha_prize_image / preorder_product_image / store_image */
    private String usageType;

    /** 关联业务表名（用于反查，可选） */
    private String refererTable;

    /** 关联业务记录 id（可选） */
    private Long refererId;

    /** 删除标志（0=正常 / 2=删除，对齐 ruoyi） */
    @TableLogic
    private String delFlag;

}
