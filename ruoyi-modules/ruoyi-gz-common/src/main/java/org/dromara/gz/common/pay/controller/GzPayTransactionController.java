package org.dromara.gz.common.pay.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.pay.domain.bo.GzPayTransactionQueryBo;
import org.dromara.gz.common.pay.domain.vo.GzPayCallbackLogVO;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-PAY-001 admin 端支付订单列表 + 详情（含回调日志）。
 *
 * <p>路径前缀 {@code /system/gz/pay/transaction}。权限 {@code gz:pay:transaction:list / query}
 * （menu_id 5102 / 5105，仅 owner）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/pay/transaction")
public class GzPayTransactionController extends BaseController {

    private final IGzPayTransactionService transactionService;
    private final GzPayCallbackLogMapper callbackLogMapper;

    /** 分页列表（筛 business_type / status / out_trade_no / transaction_id） */
    @SaCheckPermission("gz:pay:transaction:list")
    @GetMapping("/list")
    public TableDataInfo<GzPayTransactionVO> list(GzPayTransactionQueryBo query, PageQuery pageQuery) {
        return transactionService.selectPageList(query, pageQuery);
    }

    /** 详情 */
    @SaCheckPermission("gz:pay:transaction:query")
    @GetMapping("/{id}")
    public R<GzPayTransactionVO> detail(@PathVariable Long id) {
        GzPayTransactionVO vo = transactionService.getById(id);
        if (vo == null) {
            return R.fail("订单不存在");
        }
        return R.ok(vo);
    }

    /** 订单的回调日志列表（详情页内展示，时间倒序） */
    @SaCheckPermission("gz:pay:transaction:query")
    @GetMapping("/{outTradeNo}/callback-logs")
    public R<List<GzPayCallbackLogVO>> callbackLogs(@PathVariable String outTradeNo) {
        return R.ok(callbackLogMapper.selectByOutTradeNo(outTradeNo));
    }
}
