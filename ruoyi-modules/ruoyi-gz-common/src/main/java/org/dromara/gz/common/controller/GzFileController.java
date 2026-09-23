package org.dromara.gz.common.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.enums.GzFileUsageType;
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

    /**
     * 上传 / 取图原先只认「业务文件管理」菜单下的按钮权限。回收店员在回收看板要看客人实物照、核销时拍照存证，
     * 这两条权限一旦在角色管理里被连带取消（取消「运营配置」那棵树即可），看板就会报「当前操作没有权限」。
     * 2026-09-23 起：持有回收看板 / 回收核销权限也放行，但只限回收相关的图片用途（见各方法）。
     */
    private static final String FILE_UPLOAD_PERM = "gz:file:upload";
    private static final String FILE_LIST_PERM = "gz:file:list";

    private final IGzFileService fileService;

    /**
     * 上传文件（multipart/form-data）
     *
     * @param file      文件（≤ 10MB，图片白名单 jpg/png/webp/gif）
     * @param usageType doc/11 §5.3 枚举：news_cover / news_inline / user_avatar / gacha_prize_image / preorder_product_image / store_image
     * @return {fileId, objectKey, url, fileName, fileSize, mimeType, usageType}
     */
    @SaCheckPermission(value = {FILE_UPLOAD_PERM, "gz:recycle:appointment:verify"}, mode = SaMode.OR)
    @Log(title = "业务文件上传", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<GzFileObjectVO> upload(
        @RequestPart("file") @NotNull MultipartFile file,
        @RequestParam("usageType") @NotBlank String usageType) {
        // 只有回收核销权限的人（店员在回收看板核销时拍照存证）只能上传回收核对照，不能借此上传资讯封面 / 门店图等其它用途
        if (!canUpload(StpUtil.hasPermission(FILE_UPLOAD_PERM), usageType)) {
            throw new NotPermissionException(FILE_UPLOAD_PERM);
        }
        return R.ok(fileService.upload(file, usageType));
    }

    /**
     * 根据 fileId 拿带 1h 过期签名的临时 URL
     */
    @SaCheckPermission(value = {FILE_LIST_PERM, "gz:recycle:appointment:list"}, mode = SaMode.OR)
    @GetMapping("/url")
    public R<GzFileObjectVO> getUrl(@RequestParam("fileId") @NotNull Long fileId) {
        GzFileObjectVO vo = fileService.getPresignedUrl(fileId);
        // 只有回收看板权限的人只能看回收相关图片（客人实物照 / 店员核对照），其它用途的文件照样要求文件权限
        if (!canView(StpUtil.hasPermission(FILE_LIST_PERM), vo.getUsageType())) {
            throw new NotPermissionException(FILE_LIST_PERM);
        }
        return R.ok(vo);
    }

    /** 上传放行判定：有文件上传权限 → 任意用途；只凭回收核销权限进来 → 只能传回收核对照 */
    static boolean canUpload(boolean hasFileUploadPerm, String usageType) {
        return hasFileUploadPerm || GzFileUsageType.RECYCLE_VERIFY_IMAGE.getCode().equals(usageType);
    }

    /** 取图放行判定：有文件查看权限 → 任意文件；只凭回收看板权限进来 → 只能看回收相关图片 */
    static boolean canView(boolean hasFileListPerm, String usageType) {
        return hasFileListPerm
            || GzFileUsageType.RECYCLE_SUBMIT_IMAGE.getCode().equals(usageType)
            || GzFileUsageType.RECYCLE_VERIFY_IMAGE.getCode().equals(usageType);
    }
}
