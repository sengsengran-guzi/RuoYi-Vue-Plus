package org.dromara.gz.common.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.common.domain.GzFileObject;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * gz_file_object 视图对象
 * <p>
 * 注意：本 VO 不暴露 oss bucket / object_key 给业务前端（防止 key 枚举攻击），
 * URL 字段为接口调用时按需填充的预签名临时 URL（1h 过期）。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
@Data
@AutoMapper(target = GzFileObject.class)
public class GzFileObjectVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文件 id（业务表关联用） */
    private Long fileId;

    /** 对象存储 key（仅 admin 端列表查看；mp 端置 null） */
    private String objectKey;

    /** 原文件名 */
    private String fileName;

    /** 字节数 */
    private Long fileSize;

    /** MIME */
    private String mimeType;

    /** 使用场景 */
    private String usageType;

    /** 预签名 URL（按需填充；过期时间 1h） */
    private String url;

    /** 上传时间 */
    private Date createTime;
}
