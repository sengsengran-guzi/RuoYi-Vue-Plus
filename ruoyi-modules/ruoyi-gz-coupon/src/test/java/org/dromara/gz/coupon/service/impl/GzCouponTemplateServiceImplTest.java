package org.dromara.gz.coupon.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.bo.GzCouponTemplateBo;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzCouponTemplateServiceImpl} 单测（GZ-COUPON-001 AC 3）。
 *
 * <p>覆盖：枚举校验（V1.2 仅 cash/pindou/manual）+ template_no 生成 + 状态流转边界。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzCouponTemplateServiceImplTest {

    @Mock
    private GzCouponTemplateMapper baseMapper;

    private GzCouponTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzCouponTemplateServiceImpl(baseMapper);
    }

    private GzCouponTemplateBo validBo() {
        GzCouponTemplateBo bo = new GzCouponTemplateBo();
        bo.setName("拼豆代金券 10 元");
        bo.setDiscountType("cash");
        bo.setAmountCent(1000L);
        bo.setApplicableBusiness("pindou");
        bo.setValidDays(30);
        bo.setTotalQuota(100);
        bo.setIssueStrategy("manual");
        return bo;
    }

    @Test
    @DisplayName("新建：template_no = CPN-yyyyMMdd-000001（当日首张）+ status=active + issuedCount=0")
    void insert_generatesTemplateNo() {
        when(baseMapper.selectOne(any())).thenReturn(null); // 当日无历史
        when(baseMapper.insert(any(GzCouponTemplate.class))).thenAnswer(inv -> {
            ((GzCouponTemplate) inv.getArgument(0)).setId(5001L);
            return 1;
        });

        Long id = service.insertByBo(validBo());
        assertEquals(5001L, id);

        ArgumentCaptor<GzCouponTemplate> captor = ArgumentCaptor.forClass(GzCouponTemplate.class);
        verify(baseMapper).insert(captor.capture());
        GzCouponTemplate saved = captor.getValue();
        assertTrue(saved.getTemplateNo().matches("CPN-\\d{8}-000001"));
        assertEquals("active", saved.getStatus());
        assertEquals(0, saved.getIssuedCount());
        assertEquals(0, saved.getVersion());
    }

    @Test
    @DisplayName("新建：discount_type 非 cash → 拒绝（V1.2 仅代金券）")
    void insert_rejectNonCash() {
        GzCouponTemplateBo bo = validBo();
        bo.setDiscountType("percent");
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("新建：issue_strategy=event → 拒绝（V1.2 仅 manual 可新建）")
    void insert_rejectNonManualStrategy() {
        GzCouponTemplateBo bo = validBo();
        bo.setIssueStrategy("event");
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("新建：issueConfigJson 非法 JSON → 拒绝")
    void insert_rejectInvalidConfigJson() {
        GzCouponTemplateBo bo = validBo();
        bo.setIssueConfigJson("{not-json");
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
    }

    @Test
    @DisplayName("暂停：仅 active 可暂停")
    void pause_onlyActive() {
        GzCouponTemplate active = new GzCouponTemplate();
        active.setId(1L);
        active.setStatus("active");
        when(baseMapper.selectById(1L)).thenReturn(active);
        when(baseMapper.updateById(any(GzCouponTemplate.class))).thenReturn(1);
        assertTrue(service.pause(1L));

        ArgumentCaptor<GzCouponTemplate> captor = ArgumentCaptor.forClass(GzCouponTemplate.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("paused", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("暂停：非 active（archived）→ 拒绝")
    void pause_rejectNonActive() {
        GzCouponTemplate archived = new GzCouponTemplate();
        archived.setId(1L);
        archived.setStatus("archived");
        when(baseMapper.selectById(1L)).thenReturn(archived);
        assertThrows(ServiceException.class, () -> service.pause(1L));
    }

    @Test
    @DisplayName("归档：active/paused 可归档；已归档再归档拒绝")
    void archive_transitions() {
        GzCouponTemplate active = new GzCouponTemplate();
        active.setId(1L);
        active.setStatus("active");
        when(baseMapper.selectById(1L)).thenReturn(active);
        lenient().when(baseMapper.updateById(any(GzCouponTemplate.class))).thenReturn(1);
        assertTrue(service.archive(1L));

        GzCouponTemplate archived = new GzCouponTemplate();
        archived.setId(2L);
        archived.setStatus("archived");
        when(baseMapper.selectById(2L)).thenReturn(archived);
        assertThrows(ServiceException.class, () -> service.archive(2L));
    }

    @Test
    @DisplayName("编辑：已归档模板不可编辑")
    void update_rejectArchived() {
        GzCouponTemplate archived = new GzCouponTemplate();
        archived.setId(9L);
        archived.setStatus("archived");
        when(baseMapper.selectById(9L)).thenReturn(archived);
        GzCouponTemplateBo bo = validBo();
        bo.setId(9L);
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
    }
}
