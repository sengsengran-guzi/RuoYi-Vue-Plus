package org.dromara.gz.common.constant;

import java.time.Duration;
import java.util.Set;

/**
 * GZ-SYS-005 文件上传 / 对象存储相关常量
 * <p>
 * 强约束（任务卡 §强约束）：
 * <ul>
 *   <li>#3 白名单严格 jpg/png/webp/gif</li>
 *   <li>#4 单文件 ≤ 10MB</li>
 *   <li>#1 业务表只存 file_id，签名 URL 1h 过期</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
public final class GzFileConstants {

    private GzFileConstants() {
    }

    /** 单文件大小上限：10 MB（PRD M6 §文章编辑器） */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** 图片 MIME 白名单（小写匹配） */
    public static final Set<String> ALLOWED_IMAGE_MIME = Set.of(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/gif"
    );

    /** 后缀白名单（小写匹配，含点） */
    public static final Set<String> ALLOWED_IMAGE_SUFFIX = Set.of(
        ".jpg", ".jpeg", ".png", ".webp", ".gif"
    );

    /** 预签名 URL 有效期：1h（任务卡 D3 决策） */
    public static final Duration PRESIGNED_URL_TTL = Duration.ofHours(1);

}
