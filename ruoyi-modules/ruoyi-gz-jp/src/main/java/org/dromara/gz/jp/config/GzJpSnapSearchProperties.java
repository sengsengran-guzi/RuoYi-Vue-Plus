package org.dromara.gz.jp.config;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Data @Component @ConfigurationProperties(prefix="gz.jp.snap")
public class GzJpSnapSearchProperties {
    private String imageBaseUrl = "http://127.0.0.1:8700";
    private Translate translate = new Translate();
    @Data public static class Translate {
        private String provider = "mock";
        private String endpoint = "mt.cn-hangzhou.aliyuncs.com";
        @ToString.Exclude private String accessKeyId = "";
        @ToString.Exclude private String accessKeySecret = "";
    }
}
