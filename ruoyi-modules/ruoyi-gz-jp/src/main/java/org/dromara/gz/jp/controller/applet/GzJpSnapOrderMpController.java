package org.dromara.gz.jp.controller.applet;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.jp.domain.bo.GzJpSnapSubmitBo;
import org.dromara.gz.jp.domain.vo.GzJpOrderSubmitVO;
import org.dromara.gz.jp.domain.vo.GzJpSnapQuotePreviewVO;
import org.dromara.gz.jp.service.internal.GzJpSnapQuotePreviewService;
import org.dromara.gz.jp.service.internal.GzJpSnapSubmitService;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/app/gz/jp/snap/order")
public class GzJpSnapOrderMpController {
    private final GzJpSnapSubmitService service;
    private final GzJpSnapQuotePreviewService previewService;
    @GetMapping("/quote/{quoteId}")
    public R<GzJpSnapQuotePreviewVO> preview(@PathVariable String quoteId) {
        LoginHelper.getUserId();
        return R.ok(previewService.preview(quoteId));
    }
    @PostMapping("/submit")
    public R<GzJpOrderSubmitVO> submit(@Valid @RequestBody GzJpSnapSubmitBo bo) {return R.ok(service.submit(bo,LoginHelper.getUserId()));}
}
