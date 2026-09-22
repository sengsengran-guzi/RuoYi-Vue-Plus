package org.dromara.gz.bean.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.gz.bean.domain.vo.GzBeanStoreVO;
import org.dromara.gz.bean.service.IGzBeanStoreService;
import org.dromara.gz.bean.service.impl.GzBeanStoreServiceImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-BEAN-001 拼豆门店列表（mp 端）。
 *
 * <p>路径 {@code /app/gz/bean/store}（sensenran C 端 mp 前缀 {@code /app/}，
 * 与 admin {@code /system/} 区分）。</p>
 *
 * <p><b>匿名可读</b>（{@link SaIgnore}）：拼豆是落地首页 tab，游客需先浏览门店/日期/座位类型再决定是否
 * 登录预约（browse-first，与 news/gacha/商品 一致）；登录仅在「提交预约」(booking/paid-submit) 处由
 * mp 端 ensureLoggedIn 弹协议 sheet 触发。只读目录无敏感数据。</p>
 *
 * <p>语义（doc/10 §3 / doc/11 §3.1）：仅返回 type='pindou' + status='open' 的门店。
 * mp UI 单门店时隐藏选择条直接显示门店名（doc/12 MP-BEAN-SELECT）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@SaIgnore
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
     *
     * <p><b>{@code ?scope=} 按业务线取门店</b>（GZ-BEAN-053，客户 2026-09-21「回收和拼豆不是一个门店」）：
     * {@code pindou}=拼豆可预约门店 / {@code recycle}=回收可预约门店。两条线是不同的物理门店（地址不同），
     * 各端必须带上自己的 scope，否则会把对方的门店（和地址）显示给用户 —— 甲方反馈的
     * 「回收的地址是错的」就是这么来的。</p>
     *
     * <p><b>缺省为 {@code pindou}</b>：本端点历史上就是拼豆专用（回收当年是借用它才踩的坑），
     * 保持缺省语义不变，老版本小程序在灰度期照常工作；回收端显式传 {@code ?scope=recycle}。</p>
     *
     * <p><b>非法值归一成 {@code pindou}</b>：service 对非白名单 scope 是「不筛」（admin 下拉需要这个语义），
     * 但 mp 端点若照搬，乱传一个参数就会把两条线的门店混着返回 —— 正是本次要消灭的现象。</p>
     *
     * @param scope 业务线，缺省 / 非法 → {@code pindou}
     */
    @GetMapping("/list")
    public R<List<GzBeanStoreVO>> list(
        @RequestParam(required = false, defaultValue = "pindou") String scope) {
        String normalized = GzBeanStoreServiceImpl.SCOPE_RECYCLE.equals(scope)
            ? GzBeanStoreServiceImpl.SCOPE_RECYCLE
            : GzBeanStoreServiceImpl.SCOPE_PINDOU;
        return R.ok(storeService.selectMpList(normalized));
    }
}
