package org.dromara.gz.jp.snap.gateway;

import lombok.Getter;

@Getter
public class GzJpGatewayException extends RuntimeException {
    public enum Kind { TIMEOUT, HTTP_STATUS, IO, CIRCUIT_OPEN, CONFIGURATION }
    private final Kind kind;
    private final int httpStatus;
    public GzJpGatewayException(Kind kind, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }
}
