package org.dromara.gz.bean.controller.applet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.gz.bean.domain.vo.GzBeanStoreVO;
import org.dromara.gz.bean.service.IGzBeanStoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-BEAN-001 拼豆门店列表（mp 端）。
 *
 * <p>路径 {@code /app/gz/bean/store}（sensenran C 端 mp 前缀 {@code /app/}，
 * 与 admin {@code /system/} 区分）。</p>
 *
 * <p>登录态：本接口需登录态（拼豆预约前提；浏览态不允许选门店）。
 * sa-token mp-client 鉴权域 — 默认要求 token，未登录 → 401（由全局拦截，无需 @SaIgnore）。</p>
 *
 * <p>语义（doc/10 §3 / doc/11 §3.1）：仅返回 type='pindou' + status='open' 的门店；
 * V1.0 只有 1 行（成都春熙路店）。mp UI 单门店时隐藏选择条直接显示门店名（doc/12 MP-BEAN-SELECT）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/bean/store")
public class GzBeanStoreMpController {

    private final IGzBeanStoreService storeService;

    /**
     * GET /app/gz/bean/store/list — 拉拼豆可预约门店列表。
     *
     * <pre>
     * 200 OK
     * {
     *   "code": 200,
     *   "msg": "操作成功",
     *   "data": [
     *     {
     *       "id": 1,
     *       "storeNo": "CD001",
     *       "name": "成都春熙路店",
     *       "type": "pindou",
     *       "address": "...",
     *       "phone": "028-...",
     *       "businessHours": "10:00-22:00",
     *       "status": "open",
     *       "maxAdvanceDays": 14,
     *       "longitude": null,
     *       "latitude": null
     *     }
     *   ]
     * }
     * </pre>
     */
    @GetMapping("/list")
    public R<List<GzBeanStoreVO>> list() {
        return R.ok(storeService.selectMpList());
    }
}
