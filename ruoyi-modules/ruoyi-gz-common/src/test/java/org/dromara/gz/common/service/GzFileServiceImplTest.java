package org.dromara.gz.common.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.core.OssClient;
import org.dromara.common.oss.entity.UploadResult;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.gz.common.domain.GzFileObject;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.mapper.GzFileObjectMapper;
import org.dromara.gz.common.service.impl.GzFileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-SYS-005 AC 7 — Service 层单测
 * <p>
 * 覆盖：
 * <ul>
 *   <li>happy path: 上传 png + mock OssClient 返回 UploadResult + 落库</li>
 *   <li>白名单拦截 #1: 上传 .exe（伪造 contentType=application/octet-stream）→ 400</li>
 *   <li>白名单拦截 #2: 上传伪装 png 但后缀 .exe → 400（后缀兜底）</li>
 *   <li>大小拦截: 上传 11MB → 400</li>
 *   <li>usageType 非法 → 400</li>
 *   <li>getPresignedUrl fileId 不存在 → ServiceException</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-005)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GZ-SYS-005 GzFileServiceImpl 单测")
class GzFileServiceImplTest {

    @Mock
    private GzFileObjectMapper baseMapper;

    @InjectMocks
    private GzFileServiceImpl service;

    private MockedStatic<OssFactory> ossFactoryStatic;
    private OssClient ossClient;

    @BeforeEach
    void setUp() {
        ossClient = Mockito.mock(OssClient.class);
        when(ossClient.getConfigKey()).thenReturn("minio");
        when(ossClient.getUrl()).thenReturn("http://localhost:9000/sensenran-dev");
        when(ossClient.createPresignedGetUrl(anyString(), any(Duration.class)))
            .thenReturn("http://localhost:9000/sensenran-dev/news/2026/05/abc.png?X-Amz-Signed=fake");

        ossFactoryStatic = Mockito.mockStatic(OssFactory.class);
        ossFactoryStatic.when(OssFactory::instance).thenReturn(ossClient);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        ossFactoryStatic.close();
    }

    @Test
    @DisplayName("happy: 上传 png + 落库 + 返回预签名 URL")
    void upload_png_success() {
        // arrange
        MultipartFile file = new MockMultipartFile(
            "file",
            "test.png",
            "image/png",
            "fake-png-bytes-non-empty".getBytes());

        UploadResult mockUpload = UploadResult.builder()
            .url("http://localhost:9000/sensenran-dev/gz/2026/05/uuid.png")
            .filename("gz/2026/05/uuid.png")
            .eTag("fake-etag")
            .build();
        when(ossClient.uploadSuffix(any(byte[].class), eq(".png"), eq("image/png")))
            .thenReturn(mockUpload);

        // 模拟 insert 后回填 id
        Mockito.doAnswer(inv -> {
            GzFileObject entity = inv.getArgument(0);
            entity.setId(1001L);
            return 1;
        }).when(baseMapper).insert(any(GzFileObject.class));

        // act
        GzFileObjectVO vo = service.upload(file, "news_cover");

        // assert
        assertThat(vo).isNotNull();
        assertThat(vo.getFileId()).isEqualTo(1001L);
        assertThat(vo.getObjectKey()).isEqualTo("gz/2026/05/uuid.png");
        assertThat(vo.getFileName()).isEqualTo("test.png");
        assertThat(vo.getMimeType()).isEqualTo("image/png");
        assertThat(vo.getUsageType()).isEqualTo("news_cover");
        assertThat(vo.getUrl()).contains("X-Amz-Signed=fake");
        verify(baseMapper).insert(any(GzFileObject.class));
        verify(ossClient).uploadSuffix(any(byte[].class), eq(".png"), eq("image/png"));
    }

    @Test
    @DisplayName("白名单拦截: 上传 .exe contentType=application/octet-stream → 400")
    void upload_exe_rejected_by_mime() {
        MultipartFile file = new MockMultipartFile(
            "file",
            "evil.exe",
            "application/octet-stream",
            "fake-exe-bytes".getBytes());

        assertThatThrownBy(() -> service.upload(file, "news_cover"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("file.mime.notAllowed");
    }

    @Test
    @DisplayName("后缀兜底拦截: contentType 伪造为 image/png 但文件名 .exe → 400")
    void upload_fake_png_real_exe_rejected_by_suffix() {
        MultipartFile file = new MockMultipartFile(
            "file",
            "evil.exe",
            "image/png",  // 伪造的 contentType
            "fake-bytes".getBytes());

        assertThatThrownBy(() -> service.upload(file, "news_cover"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("file.suffix.notAllowed");
    }

    @Test
    @DisplayName("大小拦截: 11MB → 400")
    void upload_oversize_rejected() {
        byte[] big = new byte[11 * 1024 * 1024]; // 11MB
        MultipartFile file = new MockMultipartFile(
            "file",
            "big.png",
            "image/png",
            big);

        assertThatThrownBy(() -> service.upload(file, "news_cover"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("file.size.exceed");
    }

    @Test
    @DisplayName("usageType 非法 → 400")
    void upload_invalid_usage_type() {
        MultipartFile file = new MockMultipartFile(
            "file",
            "test.png",
            "image/png",
            "bytes".getBytes());

        assertThatThrownBy(() -> service.upload(file, "some_random_scene"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("file.usageType.invalid");
    }

    @Test
    @DisplayName("usageType 空 → 400")
    void upload_blank_usage_type() {
        MultipartFile file = new MockMultipartFile(
            "file",
            "test.png",
            "image/png",
            "bytes".getBytes());

        assertThatThrownBy(() -> service.upload(file, ""))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("file.usageType.required");
    }

    @Test
    @DisplayName("空文件 → 400")
    void upload_empty_file() {
        MultipartFile file = new MockMultipartFile(
            "file",
            "empty.png",
            "image/png",
            new byte[0]);

        assertThatThrownBy(() -> service.upload(file, "news_cover"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("file.empty");
    }

    @Test
    @DisplayName("getPresignedUrl: fileId 不存在 → ServiceException")
    void getUrl_not_found() {
        when(baseMapper.selectById(9999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getPresignedUrl(9999L))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("file.notFound");
    }

    @Test
    @DisplayName("getPresignedUrl: happy → 含 1h 签名")
    void getUrl_success() {
        GzFileObject entity = new GzFileObject();
        entity.setId(1001L);
        entity.setBucket("sensenran-dev");
        entity.setObjectKey("gz/2026/05/uuid.png");
        entity.setFileName("test.png");
        entity.setFileSize(100L);
        entity.setMimeType("image/png");
        entity.setUsageType("news_cover");
        when(baseMapper.selectById(1001L)).thenReturn(entity);

        GzFileObjectVO vo = service.getPresignedUrl(1001L);

        assertThat(vo.getFileId()).isEqualTo(1001L);
        assertThat(vo.getUrl()).contains("X-Amz-Signed=fake");
        verify(ossClient).createPresignedGetUrl(eq("gz/2026/05/uuid.png"), eq(Duration.ofHours(1)));
    }
}
