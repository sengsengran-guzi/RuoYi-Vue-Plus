package org.dromara.gz.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 业务文件使用场景枚举（doc/11 §5.3 / F5.5 — 代码 const 固定，新增需改本类）
 * <p>
 * 业务方上传文件时必传 {@code usageType}，落 {@code gz_file_object.usage_type}，用于后续按场景反查 / 容量统计。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
@Getter
@AllArgsConstructor
public enum GzFileUsageType {

    /** 资讯封面 */
    NEWS_COVER("news_cover", "资讯封面"),
    /** 资讯富文本内嵌图片 */
    NEWS_INLINE("news_inline", "资讯内嵌"),
    /** 用户头像 */
    USER_AVATAR("user_avatar", "用户头像"),
    /** 扭蛋奖品图片（V1.1） */
    GACHA_PRIZE_IMAGE("gacha_prize_image", "扭蛋奖品"),
    /** 预购商品图片（V1.1） */
    PREORDER_PRODUCT_IMAGE("preorder_product_image", "预购商品"),
    /** 门店图片 */
    STORE_IMAGE("store_image", "门店图片");

    /** 落库枚举值 */
    private final String code;

    /** 显示名 */
    private final String label;

    /**
     * 按 code 校验并返回枚举；不匹配抛 IllegalArgumentException（由 service 包装为 ServiceException）
     */
    public static GzFileUsageType ofCode(String code) {
        return Arrays.stream(values())
            .filter(e -> e.code.equals(code))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("file.usageType.invalid: " + code));
    }
}
