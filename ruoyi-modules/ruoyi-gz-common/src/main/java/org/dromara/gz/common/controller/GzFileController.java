package org.dromara.gz.common.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.service.IGzFileService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * GZ-SYS-005 业务文件上传（admin 端）
 * <p>
 * 权限：{@code gz:file:upload} / {@code gz:file:list}（DDL 已插入 sys_menu menu_id 5031~5033）
 * </p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/file")
public class GzFileController {

    private final IGzFileService fileService;

    /**
     * 上传文件（multipart/form-data）
     *
     * @param file      文件（≤ 10MB，图片白名单 jpg/png/webp/gif）
     * @param usageType doc/11 §5.3 枚举：news_cover / news_inline / user_avatar / gacha_prize_image / preorder_product_image / store_image
     * @return {fileId, objectKey, url, fileName, fileSize, mimeType, usageType}
     */
    @SaCheckPermission("gz:file:upload")
    @Log(title = "业务文件上传", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<GzFileObjectVO> upload(
        @RequestPart("file") @NotNull MultipartFile file,
        @RequestParam("usageType") @NotBlank String usageType) {
        return R.ok(fileService.upload(file, usageType));
    }

    /**
     * 根据 fileId 拿带 1h 过期签名的临时 URL
     */
    @SaCheckPermission("gz:file:list")
    @GetMapping("/url")
    public R<GzFileObjectVO> getUrl(@RequestParam("fileId") @NotNull Long fileId) {
        return R.ok(fileService.getPresignedUrl(fileId));
    }
}
