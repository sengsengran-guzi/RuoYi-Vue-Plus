package org.dromara.gz.jp.controller;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.controller.applet.GzJpSnapMpController;
import org.dromara.gz.jp.exception.GzJpSnapSearchErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
/** Framework RateLimiter message mapped locally to the stable snap business code. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={GzJpSnapMpController.class,GzJpSnapTermController.class})
public class GzJpSnapReadExceptionAdvice {
    @ExceptionHandler(ServiceException.class) public R<Void> service(ServiceException ex) {
        if ("SNAP_TOO_FAST".equals(ex.getMessage())) return R.fail(GzJpSnapSearchErrorCode.SNAP_TOO_FAST, "搜得太快了，请稍后再试");
        return R.fail(ex.getCode() == null ? 500 : ex.getCode(), ex.getMessage());
    }
}
