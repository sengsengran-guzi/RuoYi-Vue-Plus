package org.dromara.gz.recycle.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentSubmitBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateAllVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateVO;
import org.dromara.gz.recycle.exception.GzRecycleErrorCode;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.dromara.gz.recycle.service.IGzRecyclePriceRuleService;
import org.dromara.gz.recycle.service.internal.RecycleApptNoGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecycleAppointmentServiceImpl} 单测（GZ-RECYCLE-002 AC 2/3/5）。
 *
 * <p>覆盖：① 多品类累加估价（金额 Σ + total_qty Σ + 时长 Σ 冻结）；② E1 未估价品类 hasUnpriced 标记 +
 * 含未估价拒收；③ submit_image_ids 空照拒收（AC3 后端二次校验）+ image_id 入库（逗号分隔，非裸 url）；
 * ④ receiver_openid 缺失拦截（E5）。estimate 单品类口径已在 GzRecyclePriceRuleEstimateTest 覆盖。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleAppointmentServiceImplTest {

    @Mock
    private GzRecycleAppointmentMapper baseMapper;
    @Mock
    private IGzRecyclePriceRuleService priceRuleService;
    @Mock
    private GzUserMapper gzUserMapper;
    @Mock
    private RecycleApptNoGenerator apptNoGenerator;
    @Mock
    private org.dromara.gz.common.pay.service.IGzPayPayoutService payoutService;
    @Mock
    private org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper payoutMapper;
    @Mock
    private org.dromara.common.core.service.ConfigService configService;

    private GzRecycleAppointmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzRecycleAppointmentServiceImpl(
            baseMapper, priceRuleService, gzUserMapper, apptNoGenerator, new ObjectMapper(),
            payoutService, payoutMapper, configService);
    }

    private GzRecycleAppointmentSubmitBo.ProductLine line(String category, int qty) {
        GzRecycleAppointmentSubmitBo.ProductLine p = new GzRecycleAppointmentSubmitBo.ProductLine();
        p.setCategory(category);
        p.setQty(qty);
        return p;
    }

    private GzRecycleEstimateVO hit(String category, int qty, long unitPriceCent, int duration) {
        GzRecycleEstimateVO vo = new GzRecycleEstimateVO();
        vo.setCategory(category);
        vo.setQty(qty);
        vo.setUnitPriceCent(unitPriceCent);
        vo.setEstimatedAmountCent(unitPriceCent * qty);
        vo.setMatchedDurationMinutes(duration);
        return vo;
    }

    private GzUser userWithOpenid(String openid) {
        GzUser u = new GzUser();
        u.setId(1001L);
        u.setOpenid(openid);
        u.setMobile("13800000000");
        u.setWechatId("wx_zhang");
        u.setTenantId("1001");
        return u;
    }

    private GzRecycleAppointmentSubmitBo submitBo(List<GzRecycleAppointmentSubmitBo.ProductLine> products,
                                                  List<Long> imageIds) {
        GzRecycleAppointmentSubmitBo bo = new GzRecycleAppointmentSubmitBo();
        bo.setStoreId(1L);
        bo.setProducts(products);
        bo.setApptDate(LocalDate.of(2026, 6, 22));
        bo.setSlotStart(LocalTime.of(10, 0));
        bo.setSlotEnd(LocalTime.of(12, 0));
        bo.setSubmitImageIds(imageIds);
        return bo;
    }

    // ===== AC2 多品类累加估价 =====

    @Test
    @DisplayName("AC2 多品类累加：card×3@500 + goods×5@300 → 金额 1500+1500=3000 / total_qty 8 / 时长 15+20=35")
    void estimateAll_multiCategorySum() {
        when(priceRuleService.estimate(eq("card"), eq(3))).thenReturn(hit("card", 3, 500L, 15));
        when(priceRuleService.estimate(eq("goods"), eq(5))).thenReturn(hit("goods", 5, 300L, 20));

        GzRecycleEstimateAllVO vo = service.estimateAll(List.of(line("card", 3), line("goods", 5)));

        assertEquals(8, vo.getTotalQty());
        assertEquals(3000L, vo.getEstimatedAmountCent());
        assertEquals(35, vo.getMatchedDurationMinutes(), "时长冻结口径 = Σ 各命中 duration_minutes");
        assertFalse(vo.getHasUnpriced());
        assertEquals(2, vo.getLines().size());
        assertTrue(vo.getLines().get(0).getPriced());
        assertEquals(1500L, vo.getLines().get(0).getEstimatedAmountCent());
    }

    @Test
    @DisplayName("AC5 E1：goods 未命中区间 → 该 line.priced=false + hasUnpriced=true，不阻断 card 估价")
    void estimateAll_e1UnpricedDoesNotBlockOthers() {
        when(priceRuleService.estimate(eq("card"), eq(3))).thenReturn(hit("card", 3, 500L, 15));
        when(priceRuleService.estimate(eq("goods"), eq(99)))
            .thenThrow(new ServiceException("品类「goods」数量 99 无报价规则"));

        GzRecycleEstimateAllVO vo = service.estimateAll(List.of(line("card", 3), line("goods", 99)));

        assertTrue(vo.getHasUnpriced(), "含未估价品类");
        assertEquals(102, vo.getTotalQty(), "total_qty 仍含未估价品类数量");
        assertEquals(1500L, vo.getEstimatedAmountCent(), "金额仅累加已估价品类");
        assertEquals(15, vo.getMatchedDurationMinutes(), "时长仅累加已估价品类");
        assertTrue(vo.getLines().get(0).getPriced());
        assertFalse(vo.getLines().get(1).getPriced());
    }

    // ===== AC3 submit_image_ids 必填 =====

    @Test
    @DisplayName("AC3 submit_image_ids 空 → 后端拒收 4101，不查用户/不 INSERT")
    void submit_rejectEmptyImages() {
        GzRecycleAppointmentSubmitBo bo = submitBo(List.of(line("card", 3)), new ArrayList<>());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.SUBMIT_IMAGE_REQUIRED, ex.getCode());
        verify(gzUserMapper, never()).selectById(org.mockito.ArgumentMatchers.any());
        verify(baseMapper, never()).insert(org.mockito.ArgumentMatchers.any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("AC3 submit_image_ids 含 null 元素 → 后端拒收 4101")
    void submit_rejectImagesWithNull() {
        GzRecycleAppointmentSubmitBo bo = submitBo(List.of(line("card", 3)), Arrays.asList(9L, null));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.SUBMIT_IMAGE_REQUIRED, ex.getCode());
        verify(baseMapper, never()).insert(org.mockito.ArgumentMatchers.any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("AC3+AC4 正常提交：image_id 逗号入库（非裸 url）+ 估价/时长/total_qty 冻结 + openid/mobile/wechat 快照")
    void submit_happyPath() {
        when(priceRuleService.estimate(eq("card"), eq(3))).thenReturn(hit("card", 3, 500L, 15));
        when(priceRuleService.estimate(eq("goods"), eq(5))).thenReturn(hit("goods", 5, 300L, 20));
        when(gzUserMapper.selectById(1001L)).thenReturn(userWithOpenid("o_wx_abc123"));
        when(apptNoGenerator.generate()).thenReturn("RCY-20260622-000001");
        when(baseMapper.insert(org.mockito.ArgumentMatchers.any(GzRecycleAppointment.class))).thenReturn(1);

        GzRecycleAppointmentSubmitBo bo = submitBo(
            List.of(line("card", 3), line("goods", 5)), List.of(11L, 12L));

        GzRecycleAppointmentVO vo = service.submit(bo, 1001L);

        assertEquals("RCY-20260622-000001", vo.getAppointmentNo());
        assertEquals(8, vo.getTotalQty());
        assertEquals(3000L, vo.getEstimatedAmountCent());
        assertEquals(35, vo.getMatchedDurationMinutes());
        assertEquals("submitted", vo.getStatus());

        // 断言落库实体：image_id 逗号分隔（非裸 url）+ 快照 + 冻结
        org.mockito.ArgumentCaptor<GzRecycleAppointment> captor =
            org.mockito.ArgumentCaptor.forClass(GzRecycleAppointment.class);
        verify(baseMapper).insert(captor.capture());
        GzRecycleAppointment saved = captor.getValue();
        assertEquals("11,12", saved.getSubmitImageIds(), "image_id 逗号分隔入库，不存裸 url");
        assertEquals("o_wx_abc123", saved.getReceiverOpenid());
        assertEquals("13800000000", saved.getMobileSnapshot());
        assertEquals("wx_zhang", saved.getWechatIdSnapshot());
        assertEquals(3000L, saved.getEstimatedAmountCent());
        assertEquals(35, saved.getMatchedDurationMinutes());
        assertEquals(8, saved.getTotalQty());
        assertEquals("submitted", saved.getStatus());
        assertTrue(saved.getProductSnapshotJson().contains("card"), "product 快照写 JSON 列");
    }

    @Test
    @DisplayName("AC5 提交含未估价品类（E1）→ 拒收 4102，不 INSERT")
    void submit_rejectHasUnpriced() {
        when(priceRuleService.estimate(eq("card"), eq(3))).thenReturn(hit("card", 3, 500L, 15));
        when(priceRuleService.estimate(eq("goods"), eq(99)))
            .thenThrow(new ServiceException("无报价规则"));
        when(gzUserMapper.selectById(1001L)).thenReturn(userWithOpenid("o_wx_abc123"));

        GzRecycleAppointmentSubmitBo bo = submitBo(
            List.of(line("card", 3), line("goods", 99)), List.of(11L));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.HAS_UNPRICED_CATEGORY, ex.getCode());
        verify(baseMapper, never()).insert(org.mockito.ArgumentMatchers.any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("AC4 E5：receiver_openid 缺失 → 拦截 4103，不 INSERT")
    void submit_rejectMissingOpenid() {
        // estimate 在 openid 校验之后才调用，用 lenient 避免 UnnecessaryStubbing
        lenient().when(priceRuleService.estimate(eq("card"), anyInt())).thenReturn(hit("card", 3, 500L, 15));
        when(gzUserMapper.selectById(1001L)).thenReturn(userWithOpenid(""));

        GzRecycleAppointmentSubmitBo bo = submitBo(List.of(line("card", 3)), List.of(11L));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.OPENID_REQUIRED, ex.getCode());
        verify(baseMapper, never()).insert(org.mockito.ArgumentMatchers.any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("selectMyDetail 越权：非本人返回 null")
    void selectMyDetail_forbiddenWhenNotOwner() {
        GzRecycleAppointment e = new GzRecycleAppointment();
        e.setId(5L);
        e.setUserId(2002L); // 属于别人
        when(baseMapper.selectById(5L)).thenReturn(e);

        assertEquals(null, service.selectMyDetail(5L, 1001L));
    }
}
