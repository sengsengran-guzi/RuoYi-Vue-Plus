package org.dromara.gz.jp.snap.gateway;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gz.jp.snap.gateway")
public class GzJpGatewayProperties {
    private String baseUrl = "http://127.0.0.1:8700";
    @ToString.Exclude
    private String secret = "";
    private int readFailureThreshold = 3;
    private long circuitOpenMs = 60000;
    private String probeKeyword = "ブルーロック 缶バッジ";
    private String probeItemId = "m99000000001";
}
