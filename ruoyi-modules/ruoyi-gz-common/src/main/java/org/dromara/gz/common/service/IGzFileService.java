package org.dromara.gz.common.service;

import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * GZ-SYS-005 文件上传 / 对象存储服务
 * <p>
 * admin / mp 双端共用：白名单校验 + 大小校验 + 调 ruoyi {@code OssClient} 上传 + 落 {@code gz_file_object}。
 * URL 不存全串，渲染时由 {@link #getPresignedUrl(Long)} 生成带 1h 过期签名的临时 URL。
 * </p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
public interface IGzFileService {

    /**
     * 上传单文件到对象存储 + 落 gz_file_object 元数据。
     *
     * @param file      multipart 文件（≤ 10MB，图片 MIME 白名单）
     * @param usageType doc/11 §5.3 枚举 code（如 news_cover / user_avatar）
     * @return 落库后的 VO（含 fileId + objectKey + 预签名 URL）
     * @throws org.dromara.common.core.exception.ServiceException 文件为空 / 超过 10MB / MIME 不在白名单 / usageType 非法
     */
    GzFileObjectVO upload(MultipartFile file, String usageType);

    /**
     * 根据 fileId 查询元数据 + 生成 1h 过期的预签名 URL。
     *
     * @param fileId gz_file_object.id
     * @return VO（含 URL）
     * @throws org.dromara.common.core.exception.ServiceException fileId 不存在
     */
    GzFileObjectVO getPresignedUrl(Long fileId);

}
