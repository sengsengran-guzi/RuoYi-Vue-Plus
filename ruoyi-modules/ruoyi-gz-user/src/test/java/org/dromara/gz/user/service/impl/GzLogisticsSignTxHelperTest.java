package org.dromara.gz.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.gz.user.domain.entity.GzLogisticsAudit;
import org.dromara.gz.user.domain.entity.writable.GzOrdLogisticsRow;
import org.dromara.gz.user.mapper.GzLogisticsAuditMapper;
import org.dromara.gz.user.mapper.writable.GzOrdLogisticsMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * {@link GzLogisticsSignTxHelper} 单测（GZ-USER-104 AC4/AC7）—— 单订单条件 UPDATE + 写审计原子单元。
 *
 * <p>覆盖：
 * <ul>
 *   <li>命中（update affected=1）→ 写 1 条审计 + 返 true；审计字段对齐 doc/11 §8.3</li>
 *   <li>未命中（update affected=0，已签 / 并发抢先）→ 不写审计 + 返 false（幂等，AC4）</li>
 * </ul></p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzLogisticsSignTxHelperTest {

    @Mock
    private GzOrdLogisticsMapper ordLogisticsMapper;
    @Mock
    private GzLogisticsAuditMapper auditMapper;

    private GzLogisticsSignTxHelper helper;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, GzOrdLogisticsRow.class);
    }

    @BeforeEach
    void setUp() {
        helper = new GzLogisticsSignTxHelper(auditMapper);
    }

    @Test
    @DisplayName("(1) 条件命中（affected=1）→ 写 1 条审计 + 返 true；审计字段对齐 §8.3")
    void hit_writesOneAudit_returnsTrue() {
        when(ordLogisticsMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        boolean ok = helper.markDeliveredAndAudit(ordLogisticsMapper, GzOrdLogisticsRow.class, 9001L, 0,
            "PREORD-20260601-000020", GzLogisticsAudit.BIZ_PREORDER,
            GzLogisticsAudit.ACTION_AUTO_DELIVERED, GzLogisticsAudit.OPERATOR_TYPE_SYSTEM,
            GzLogisticsAudit.OPERATOR_SYSTEM);

        assertTrue(ok);
        ArgumentCaptor<GzLogisticsAudit> cap = ArgumentCaptor.forClass(GzLogisticsAudit.class);
        verify(auditMapper, times(1)).insert(cap.capture());
        GzLogisticsAudit a = cap.getValue();
        assertEquals("preorder", a.getBusinessType());
        assertEquals("PREORD-20260601-000020", a.getBusinessOrderNo());
        assertEquals(GzLogisticsAudit.ACTION_AUTO_DELIVERED, a.getActionType());
        assertEquals(GzLogisticsAudit.OPERATOR_TYPE_SYSTEM, a.getOperatorType());
        assertEquals(GzLogisticsAudit.OPERATOR_SYSTEM, a.getOperatorId());
        assertEquals("in_china_dispatching", a.getFromStatus());
        assertEquals("delivered", a.getToStatus());
        assertNotNull(a.getOperatedTime());
    }

    @Test
    @DisplayName("(2) 条件未命中（affected=0，已签 / 并发）→ 不写审计 + 返 false（幂等，AC4）")
    void miss_noAudit_returnsFalse() {
        when(ordLogisticsMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        boolean ok = helper.markDeliveredAndAudit(ordLogisticsMapper, GzOrdLogisticsRow.class, 9002L, 3,
            "PREORD-20260601-000021", GzLogisticsAudit.BIZ_PREORDER,
            GzLogisticsAudit.ACTION_USER_CONFIRMED, GzLogisticsAudit.OPERATOR_TYPE_USER, "U20260601000002");

        assertFalse(ok);
        verify(auditMapper, never()).insert(any(GzLogisticsAudit.class));
    }

    @Test
    @DisplayName("(3) 用户签收审计：user_confirmed + operator_type=user + operator_id=user_no")
    void userConfirmed_auditFields() {
        when(ordLogisticsMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        helper.markDeliveredAndAudit(ordLogisticsMapper, GzOrdLogisticsRow.class, 9003L, 0,
            "PREORD-20260601-000022", GzLogisticsAudit.BIZ_PREORDER,
            GzLogisticsAudit.ACTION_USER_CONFIRMED, GzLogisticsAudit.OPERATOR_TYPE_USER, "U20260601000003");

        ArgumentCaptor<GzLogisticsAudit> cap = ArgumentCaptor.forClass(GzLogisticsAudit.class);
        verify(auditMapper).insert(cap.capture());
        GzLogisticsAudit a = cap.getValue();
        assertEquals(GzLogisticsAudit.ACTION_USER_CONFIRMED, a.getActionType());
        assertEquals(GzLogisticsAudit.OPERATOR_TYPE_USER, a.getOperatorType());
        assertEquals("U20260601000003", a.getOperatorId());
    }
}
