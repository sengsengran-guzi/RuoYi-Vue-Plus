package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GzRecycleVerifyBo} 的 Bean Validation 口径守卫（客户 2026-09-21「后台无法核销」）。
 *
 * <p>admin 核销接口 {@code POST /system/gz/recycle/appointment/{id}/verify} 的 id 在路径上、body 里没有，
 * controller 在方法体里才回填 {@code appointmentId}；而 {@code @Valid} 在进方法体之前就跑完了。
 * BO 上一旦给 {@code appointmentId} 加 {@code @NotNull}，admin 的每一次核销都会被拦成「预约单 id 不能为空」——
 * 线上真实发生过，且 controller 单测 / service mock 单测都照不出来（它们不走 Spring 的参数校验）。
 * 本测直接跑真实 Validator，用 admin 前端的<b>原样请求体</b>校验。</p>
 */
@Tag("dev")
class GzRecycleVerifyBoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** admin 前端 verifyAppointment() 的原样 body：{ verifyImageIds: [], finalAmountCent, remark } —— 不带 appointmentId */
    private GzRecycleVerifyBo adminFrontendBody() {
        GzRecycleVerifyBo bo = new GzRecycleVerifyBo();
        bo.setVerifyImageIds(java.util.List.of());
        bo.setFinalAmountCent(1200L);
        return bo;
    }

    @Test
    @DisplayName("★ admin 前端原样请求体（无 appointmentId、无照片）必须通过校验 —— 否则后台核销永远报「预约单 id 不能为空」")
    void adminFrontendBody_passesValidation() {
        Set<ConstraintViolation<GzRecycleVerifyBo>> v = validator.validate(adminFrontendBody());
        assertTrue(v.isEmpty(), "admin 核销请求体不应有任何校验错误，实际=" + v);
    }

    @Test
    @DisplayName("金额仍是必填：不传 finalAmountCent → 只报这一条")
    void missingAmount_stillRejected() {
        GzRecycleVerifyBo bo = adminFrontendBody();
        bo.setFinalAmountCent(null);
        Set<ConstraintViolation<GzRecycleVerifyBo>> v = validator.validate(bo);
        assertEquals(1, v.size(), "实际=" + v);
        assertEquals("finalAmountCent", v.iterator().next().getPropertyPath().toString());
    }
}
