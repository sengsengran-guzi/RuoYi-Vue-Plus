package org.dromara.gz.ord.controller.applet;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.ord.domain.bo.GzAdminOrderQueryBo;
import org.dromara.gz.ord.domain.vo.GzUnifiedOrderVo;
import org.dromara.gz.ord.service.IGzUnifiedOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-ADMIN-201 mp 店员端订单查看 controller 单测（AC1/AC2/AC4/AC7/AC10）。
 *
 * <p>本 controller 是薄壳，复用 GZ-ADMIN-103 {@link IGzUnifiedOrderService}（其聚合 / snapshot 5 字段 /
 * createdAt DESC 等业务逻辑已由 {@code GzUnifiedOrderServiceImplTest} 3/3 覆盖，复用纪律不重测）。
 * 本测试聚焦 controller 自身职责：</p>
 * <ul>
 *   <li>① bizType=all（含空 / 非法）→ queryBo.businessType 留空（不按 business_type 过滤），委托 listForAdmin（AC1/AC2）</li>
 *   <li>② bizType=gacha → queryBo.businessType=gacha 透传（AC10 c：仅扭蛋单查询入参正确）；空串筛选项 blankToNull → null</li>
 *   <li>③ 详情委托 getDetailForAdmin(transactionId)；不存在 → R.fail（AC7）</li>
 *   <li>④ 权限注解断言：list / detail 方法均带 {@code @SaCheckPermission("gz:ord:view")}
 *       —— AC4 三态（授权 200 / 纯顾客 403 / 未授权 403）由 sa-token 切面在该注解上裁决，
 *       单测在此校验注解契约存在且 value 正确（运行时三态走 Tier 1B 集成 / curl）</li>
 * </ul>
 *
 * <p>collaborators 全 mock，纯逻辑验证，不依赖 Spring / 真实 DB。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-201)
 */
@Tag("dev")
@DisplayName("GZ-ADMIN-201 mp 店员端订单查看 — bizType 映射 / 委托 / 权限注解契约")
@ExtendWith(MockitoExtension.class)
class GzStaffOrderMpControllerTest {

    @Mock
    private IGzUnifiedOrderService unifiedOrderService;

    private GzStaffOrderMpController controller;

    private GzStaffOrderMpController controller() {
        if (controller == null) {
            controller = new GzStaffOrderMpController(unifiedOrderService);
        }
        return controller;
    }

    @Test
    @DisplayName("① bizType=all → businessType 留空（不过滤），委托 listForAdmin，原样返回分页")
    void list_bizTypeAll_noBusinessTypeFilter() {
        TableDataInfo<GzUnifiedOrderVo> page = TableDataInfo.build(List.of(new GzUnifiedOrderVo()));
        page.setTotal(1L);
        when(unifiedOrderService.listForAdmin(any(), any())).thenReturn(page);

        TableDataInfo<GzUnifiedOrderVo> ret = controller().list(
            "all", null, null, null, null, new PageQuery(10, 1));

        ArgumentCaptor<GzAdminOrderQueryBo> captor = ArgumentCaptor.forClass(GzAdminOrderQueryBo.class);
        verify(unifiedOrderService).listForAdmin(captor.capture(), any());
        // all → businessType 留空（service 口径：null = 不按 business_type 过滤）
        assertNull(captor.getValue().getBusinessType(), "bizType=all 应不设 businessType 过滤");
        assertEquals(1L, ret.getTotal());
    }

    @Test
    @DisplayName("② bizType=gacha + 空串筛选项 → businessType=gacha 透传，空串经 blankToNull → null")
    void list_bizTypeGacha_passthrough_blankToNull() {
        when(unifiedOrderService.listForAdmin(any(), any()))
            .thenReturn(TableDataInfo.build(List.of()));

        controller().list("gacha", "  ", "", "  ", "", new PageQuery(10, 1));

        ArgumentCaptor<GzAdminOrderQueryBo> captor = ArgumentCaptor.forClass(GzAdminOrderQueryBo.class);
        verify(unifiedOrderService).listForAdmin(captor.capture(), any());
        GzAdminOrderQueryBo q = captor.getValue();
        assertEquals("gacha", q.getBusinessType(), "bizType=gacha 应透传 businessType=gacha（AC10 c）");
        assertNull(q.getBusinessStatus(), "空白 status 应 blankToNull → null");
        assertNull(q.getLogisticsStatus(), "空 logisticsStatus 应 null");
        assertNull(q.getUserKeyword(), "空白 userKeyword 应 null");
        assertNull(q.getOrderNo(), "空 orderNo 应 null");
    }

    @Test
    @DisplayName("③ 详情委托 getDetailForAdmin(transactionId)；命中 → R.ok，不存在 → R.fail")
    void detail_delegatesAndNullGuard() {
        GzUnifiedOrderVo vo = new GzUnifiedOrderVo();
        vo.setBusinessType("gacha");
        when(unifiedOrderService.getDetailForAdmin(9001L)).thenReturn(vo);
        when(unifiedOrderService.getDetailForAdmin(8888L)).thenReturn(null);

        var hit = controller().getInfo(9001L);
        assertEquals(200, hit.getCode());
        assertNotNull(hit.getData());
        assertEquals("gacha", hit.getData().getBusinessType());

        var miss = controller().getInfo(8888L);
        assertEquals(500, miss.getCode(), "详情不存在应 R.fail（code 500）");
    }

    @Test
    @DisplayName("④ list / detail 方法均带 @SaCheckPermission(\"gz:ord:view\")（AC4 权限契约）")
    void permissionAnnotationContract() throws NoSuchMethodException {
        Method listM = GzStaffOrderMpController.class.getMethod(
            "list", String.class, String.class, String.class, String.class, String.class, PageQuery.class);
        Method detailM = GzStaffOrderMpController.class.getMethod("getInfo", Long.class);

        for (Method m : List.of(listM, detailM)) {
            SaCheckPermission perm = m.getAnnotation(SaCheckPermission.class);
            assertNotNull(perm, m.getName() + " 必须带 @SaCheckPermission");
            assertTrue(List.of(perm.value()).contains("gz:ord:view"),
                m.getName() + " @SaCheckPermission value 必须含 gz:ord:view");
        }
    }
}
