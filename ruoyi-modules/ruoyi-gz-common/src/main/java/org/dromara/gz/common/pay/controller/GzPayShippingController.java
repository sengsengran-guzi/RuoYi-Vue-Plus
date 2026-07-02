package org.dromara.gz.common.pay.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.pay.domain.vo.GzPayShippingOrderVO;
import org.dromara.gz.common.pay.service.IGzPayShippingService;
import org.dromara.gz.common.pay.service.IGzPayShippingService.UploadStats;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信发货信息上报管理（admin 端）—— 排查「订单未接入 / 待发货」+ owner 手动补报。
 *
 * <p>路径前缀 {@code /system/gz/pay/shipping}。权限 {@code gz:pay:shipping:list}（列表）/
 * {@code gz:pay:shipping:retry}（补报）（menu_id 5114-5116，仅 owner）。</p>
 *
 * <p><b>存在意义</b>：生产未部署 SnailJob → 发货兜底 cron 从不触发 → 支付回调即时上报一旦失败即永卡
 * {@code failed}（历史 74 单成因）。本页给 owner 一个可视化 + 手动补报的救手：重报已在小程序订单中心
 * 发过货的单会命中微信幂等码收敛为 {@code success}。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/pay/shipping")
public class GzPayShippingController extends BaseController {

    private final IGzPayShippingService shippingService;

    /** 分页列表（筛 upload_status / business_type / out_trade_no） */
    @SaCheckPermission("gz:pay:shipping:list")
    @GetMapping("/list")
    public TableDataInfo<GzPayShippingOrderVO> list(
        @RequestParam(required = false) String uploadStatus,
        @RequestParam(required = false) String businessType,
        @RequestParam(required = false) String outTradeNo,
        PageQuery pageQuery) {
        return shippingService.selectPageList(uploadStatus, businessType, outTradeNo, pageQuery);
    }

    /**
     * 手动补报全部待发货（pending/failed，单次最多 50 条，同步返回统计）。
     *
     * <pre>POST /system/gz/pay/shipping/retry-all</pre>
     */
    @SaCheckPermission("gz:pay:shipping:retry")
    @Log(title = "发货信息手动补报（全部）", businessType = BusinessType.UPDATE)
    @PostMapping("/retry-all")
    public R<UploadStats> retryAll() {
        return R.ok("补报完成", shippingService.backfillPending());
    }

    /**
     * 单条补报。
     *
     * <pre>POST /system/gz/pay/shipping/{id}/retry</pre>
     */
    @SaCheckPermission("gz:pay:shipping:retry")
    @Log(title = "发货信息手动补报（单条）", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/retry")
    public R<Void> retryOne(@PathVariable Long id) {
        boolean ok = shippingService.retryOne(id);
        return ok ? R.ok("上报成功") : R.fail("上报未成功，请稍后重试或查看失败原因");
    }
}
