package org.dromara.gz.common.controller.applet;

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
 * GZ-SYS-005 业务文件上传（小程序端）
 * <p>
 * 路径前缀 {@code /app/} 对应 sa-token mp-client 鉴权域（无 @SaIgnore — 默认要求 mp 登录态，
 * GZ-USER-002 头像上传等场景将由 sa-token 全局鉴权拦截器校验 mp 用户登录）。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/app/gz/common/file")
public class GzFileMpController {

    private final IGzFileService fileService;

    /**
     * mp 上传文件
     * <p>
     * mp 端用法：{@code wx.uploadFile({ url: '/app/gz/common/file/upload', formData: { usageType: 'user_avatar' }, name: 'file' })}
     * </p>
     */
    @Log(title = "小程序文件上传", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<GzFileObjectVO> upload(
        @RequestPart("file") @NotNull MultipartFile file,
        @RequestParam("usageType") @NotBlank String usageType) {
        return R.ok(fileService.upload(file, usageType));
    }

    /**
     * mp 根据 fileId 拿带 1h 过期签名的临时 URL
     */
    @GetMapping("/url")
    public R<GzFileObjectVO> getUrl(@RequestParam("fileId") @NotNull Long fileId) {
        return R.ok(fileService.getPresignedUrl(fileId));
    }
}
