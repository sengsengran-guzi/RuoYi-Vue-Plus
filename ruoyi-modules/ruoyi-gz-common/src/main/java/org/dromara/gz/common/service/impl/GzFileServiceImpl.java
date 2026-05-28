package org.dromara.gz.common.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.core.OssClient;
import org.dromara.common.oss.entity.UploadResult;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.gz.common.constant.GzFileConstants;
import org.dromara.gz.common.domain.GzFileObject;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.enums.GzFileUsageType;
import org.dromara.gz.common.mapper.GzFileObjectMapper;
import org.dromara.gz.common.service.IGzFileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * GZ-SYS-005 文件上传 / 对象存储服务实现
 * <p>
 * 不直接调阿里云 OSS SDK，复用 ruoyi {@link OssClient} 抽象 — dev=MinIO / prod=阿里云 OSS。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzFileServiceImpl implements IGzFileService {

    private final GzFileObjectMapper baseMapper;

    @Override
    public GzFileObjectVO upload(MultipartFile file, String usageType) {
        // 1. 入参校验 — 文件非空
        if (ObjectUtil.isNull(file) || file.isEmpty()) {
            throw new ServiceException("file.empty");
        }
        // 2. 入参校验 — usageType 必传 + 在白名单内
        if (StrUtil.isBlank(usageType)) {
            throw new ServiceException("file.usageType.required");
        }
        GzFileUsageType usage;
        try {
            usage = GzFileUsageType.ofCode(usageType);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("file.usageType.invalid: " + usageType);
        }

        // 3. 大小校验（≤ 10MB）
        long size = file.getSize();
        if (size > GzFileConstants.MAX_FILE_SIZE_BYTES) {
            throw new ServiceException(
                "file.size.exceed: " + size + " > " + GzFileConstants.MAX_FILE_SIZE_BYTES);
        }

        // 4. MIME 白名单校验（强约束 #3 — jpg/png/webp/gif）
        String contentType = file.getContentType();
        if (contentType == null || !GzFileConstants.ALLOWED_IMAGE_MIME.contains(contentType.toLowerCase())) {
            throw new ServiceException("file.mime.notAllowed: " + contentType);
        }

        // 5. 后缀白名单（兜底，防止伪造 contentType）
        String originalName = file.getOriginalFilename();
        if (StrUtil.isBlank(originalName)) {
            throw new ServiceException("file.name.empty");
        }
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx < 0) {
            throw new ServiceException("file.suffix.missing");
        }
        String suffix = originalName.substring(dotIdx).toLowerCase();
        if (!GzFileConstants.ALLOWED_IMAGE_SUFFIX.contains(suffix)) {
            throw new ServiceException("file.suffix.notAllowed: " + suffix);
        }

        // 6. 调 ruoyi OssClient 上传
        OssClient storage = OssFactory.instance();
        UploadResult uploadResult;
        try {
            uploadResult = storage.uploadSuffix(file.getBytes(), suffix, contentType);
        } catch (IOException e) {
            log.error("[GZ-SYS-005] OSS upload IO exception", e);
            throw new ServiceException("file.upload.io: " + e.getMessage());
        } catch (Exception e) {
            log.error("[GZ-SYS-005] OSS upload unexpected exception", e);
            throw new ServiceException("file.upload.failed: " + e.getMessage());
        }

        // 7. 落 gz_file_object
        GzFileObject entity = new GzFileObject();
        // bucket / object_key 从 UploadResult.filename 解析（OssClient.uploadSuffix 返回的 filename 是不含 bucket 的 key）
        entity.setBucket(getBucketName(storage));
        entity.setObjectKey(uploadResult.getFilename());
        entity.setFileName(originalName);
        entity.setFileSize(size);
        entity.setMimeType(contentType);
        entity.setUsageType(usage.getCode());
        // tenant_id / create_by / create_time 由 InjectionMetaObjectHandler 自动填充（CLAUDE.md §6 #3）
        baseMapper.insert(entity);

        // 8. 返回 VO（含预签名 URL）
        GzFileObjectVO vo = toVoWithPresignedUrl(entity, storage);
        log.info("[GZ-SYS-005] file uploaded: fileId={}, usageType={}, size={}, key={}",
            vo.getFileId(), vo.getUsageType(), vo.getFileSize(), vo.getObjectKey());
        return vo;
    }

    @Override
    public GzFileObjectVO getPresignedUrl(Long fileId) {
        if (ObjectUtil.isNull(fileId)) {
            throw new ServiceException("file.id.required");
        }
        GzFileObject entity = baseMapper.selectById(fileId);
        if (entity == null) {
            throw new ServiceException("file.notFound: " + fileId);
        }
        OssClient storage = OssFactory.instance();
        return toVoWithPresignedUrl(entity, storage);
    }

    /**
     * Entity → VO + 填充 1h 过期的预签名 URL（强约束 #2）
     * <p>
     * 不用 MapstructUtils（依赖 Spring context，单测时 NPE） — 手写 setter 拷贝，字段少且不易出错。
     * </p>
     */
    private GzFileObjectVO toVoWithPresignedUrl(GzFileObject entity, OssClient storage) {
        GzFileObjectVO vo = new GzFileObjectVO();
        vo.setFileId(entity.getId());
        vo.setObjectKey(entity.getObjectKey());
        vo.setFileName(entity.getFileName());
        vo.setFileSize(entity.getFileSize());
        vo.setMimeType(entity.getMimeType());
        vo.setUsageType(entity.getUsageType());
        vo.setCreateTime(entity.getCreateTime());
        String presigned = storage.createPresignedGetUrl(
            entity.getObjectKey(), GzFileConstants.PRESIGNED_URL_TTL);
        vo.setUrl(presigned);
        return vo;
    }

    /**
     * 从 OssClient.getUrl() 拆出 bucket 名（OssClient 内部封装 bucket，但 entity 持久化需显式）。
     * 退化策略：从 properties 反射拿不到时，存 configKey（不影响后续 oss 访问，因 OssFactory 按 configKey 路由）。
     */
    private String getBucketName(OssClient storage) {
        // OssClient.getUrl() 形如 http://localhost:9000/sensenran-dev，按 / 切最后一段
        String url = storage.getUrl();
        if (StrUtil.isBlank(url)) {
            return storage.getConfigKey();
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == url.length() - 1) {
            return storage.getConfigKey();
        }
        return url.substring(lastSlash + 1);
    }
}
