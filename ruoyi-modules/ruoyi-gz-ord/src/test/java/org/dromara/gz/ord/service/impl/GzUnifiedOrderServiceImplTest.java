package org.dromara.gz.ord.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.gacha.domain.entity.GzGachaOrder;
import org.dromara.gz.gacha.mapper.GzGachaOrderMapper;
import org.dromara.gz.ord.domain.bo.GzAdminOrderQueryBo;
import org.dromara.gz.ord.domain.entity.GzOrdOrder;
import org.dromara.gz.ord.domain.vo.GzUnifiedOrderVo;
import org.dromara.gz.ord.mapper.GzOrdOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * GZ-ADMIN-103 三类聚合订单 service 单测（AC11，≥ 3 测试）：
 * <ul>
 *   <li>① list 混合 businessType（全部）：以 gz_pay_transaction 为主表分页、含 test 单、preorder/gacha
 *       回查 snapshot 正确映射、按 paid_time DESC（主表层 orderBy）</li>
 *   <li>② list 按 businessType=gacha 仅返扭蛋单：prize/machine snapshot + rarity + 盲盒语义字段映射</li>
 *   <li>③ chip / business_type label 映射 + test 单仅投影支付流水字段（无业务/物流/地址块）</li>
 * </ul>
 *
 * <p>collaborators 全 mock，纯逻辑验证，不依赖 Spring / 真实 DB。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-103)
 */
@Tag("dev")
@DisplayName("GZ-ADMIN-103 三类聚合订单 — 主表分页 / 回查映射 / test 单")
@ExtendWith(MockitoExtension.class)
class GzUnifiedOrderServiceImplTest {

    @Mock
    private GzPayTransactionMapper payTransactionMapper;
    @Mock
    private GzOrdOrderMapper ordOrderMapper;
    @Mock
    private GzGachaOrderMapper gachaOrderMapper;
    @Mock
    private IGzUserService userService;
    @Mock
    private IGzFileService fileService;

    private GzUnifiedOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        service = new GzUnifiedOrderServiceImpl(
            payTransactionMapper, ordOrderMapper, gachaOrderMapper, userService, fileService, om);
        // 图片解析非本测试关注点，统一返回占位（lenient 避免未触发 stub 报错）
        lenient().when(fileService.getPresignedUrl(any())).thenThrow(new RuntimeException("no-oss-in-test"));
    }

    // ============================================================
    //  ① list 混合 businessType（全部）
    // ============================================================

    @Test
    @DisplayName("① list 全部：以 gz_pay_transaction 主表分页 + 含 test 单 + preorder/gacha 回查映射")
    void listForAdmin_allTypes_aggregatesFromPayTransaction() {
        GzPayTransaction pre = txn(101L, "preorder", "PREORD-20260618-000001", 19900L, "paid");
        GzPayTransaction gac = txn(102L, "gacha", "GACHA-20260618-000001", 3000L, "paid");
        GzPayTransaction test = txn(103L, "test", null, 1L, "paid");
        test.setUserId(null);

        Page<GzPayTransaction> page = new Page<>(1, 10, 3);
        page.setRecords(List.of(pre, gac, test));
        when(payTransactionMapper.selectPage(any(IPage.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        when(ordOrderMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(preorder("PREORD-20260618-000001", "paid", "in_japan")));
        when(gachaOrderMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(gacha("GACHA-20260618-000001", "pending_ship", "in_japan")));
        when(userService.selectVoMapByIds(anyCollection())).thenReturn(Map.of(2001L, user(2001L, "阿杉", "o_AAA")));

        GzAdminOrderQueryBo q = new GzAdminOrderQueryBo();
        TableDataInfo<GzUnifiedOrderVo> resp = service.listForAdmin(q, new PageQuery(1, 10));

        assertEquals(3, resp.getRows().size(), "全部 3 单（含 test）");
        GzUnifiedOrderVo preVo = byTxnId(resp.getRows(), 101L);
        assertEquals("preorder", preVo.getBusinessType());
        assertEquals("预定货品", preVo.getBusinessTypeLabel());
        assertEquals("CHIIKAWA 限定", preVo.getProductName());
        assertEquals("to_ship", preVo.getChipStatus());
        assertEquals("待发货", preVo.getChipLabel());
        assertEquals(19900L, preVo.getAmountCent());
        assertEquals("阿杉", preVo.getUserNickname());

        GzUnifiedOrderVo gacVo = byTxnId(resp.getRows(), 102L);
        assertEquals("扭蛋机", gacVo.getBusinessTypeLabel());
        assertEquals("SSR 海洋之心", gacVo.getPrizeName());
        assertEquals("SSR", gacVo.getRarity());

        // test 单：仅支付流水字段，无业务/物流/地址块
        GzUnifiedOrderVo testVo = byTxnId(resp.getRows(), 103L);
        assertEquals("测试", testVo.getBusinessTypeLabel());
        assertNull(testVo.getBusinessStatus(), "test 单无业务态");
        assertNull(testVo.getLogisticsStatus(), "test 单无物流态");
        assertNull(testVo.getProductName(), "test 单无商品块");
        assertNull(testVo.getUserId(), "test 单无用户");
    }

    // ============================================================
    //  ② list 按 businessType=gacha 仅返扭蛋单
    // ============================================================

    @Test
    @DisplayName("② list businessType=gacha：仅返扭蛋单 + prize/machine snapshot + 稀有度")
    void listForAdmin_gachaOnly_returnsGachaWithSnapshot() {
        GzPayTransaction gac = txn(200L, "gacha", "GACHA-20260618-000009", 5000L, "paid");
        Page<GzPayTransaction> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(gac));
        when(payTransactionMapper.selectPage(any(IPage.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(gachaOrderMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(gacha("GACHA-20260618-000009", "in_logistics", "in_china_dispatching")));
        when(userService.selectVoMapByIds(anyCollection())).thenReturn(Map.of(2001L, user(2001L, "阿杉", "o_AAA")));

        GzAdminOrderQueryBo q = new GzAdminOrderQueryBo();
        q.setBusinessType("gacha");
        TableDataInfo<GzUnifiedOrderVo> resp = service.listForAdmin(q, new PageQuery(1, 10));

        assertEquals(1, resp.getRows().size());
        GzUnifiedOrderVo vo = resp.getRows().get(0);
        assertEquals("gacha", vo.getBusinessType());
        assertEquals("SSR 海洋之心", vo.getPrizeName());
        assertEquals("SSR", vo.getRarity());
        assertEquals("一番赏·海洋", vo.getMachineName());
        assertEquals("shipping", vo.getChipStatus(), "gacha in_logistics → shipping chip");
        assertEquals("国内派送中", vo.getLogisticsStatusLabel());
    }

    // ============================================================
    //  ③ chip 反向过滤 + 详情按 transactionId
    // ============================================================

    @Test
    @DisplayName("③ businessStatus chip 本页过滤：done 只留 delivered，detail 按 txnId 回查")
    void listForAdmin_chipFilter_andDetail() {
        GzPayTransaction a = txn(301L, "preorder", "PREORD-A", 100L, "paid");
        GzPayTransaction b = txn(302L, "preorder", "PREORD-B", 200L, "paid");
        Page<GzPayTransaction> page = new Page<>(1, 10, 2);
        page.setRecords(List.of(a, b));
        when(payTransactionMapper.selectPage(any(IPage.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(ordOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            preorder("PREORD-A", "paid", "in_japan"),       // chip to_ship
            preorder("PREORD-B", "delivered", "delivered")  // chip done
        ));
        when(userService.selectVoMapByIds(anyCollection())).thenReturn(Map.of(2001L, user(2001L, "阿杉", "o_AAA")));

        GzAdminOrderQueryBo q = new GzAdminOrderQueryBo();
        q.setBusinessStatus("done");
        TableDataInfo<GzUnifiedOrderVo> resp = service.listForAdmin(q, new PageQuery(1, 10));
        assertEquals(1, resp.getRows().size(), "done chip 仅留 delivered 单");
        assertEquals(302L, resp.getRows().get(0).getTransactionId());

        // detail 按 transactionId 回查
        when(payTransactionMapper.selectById(302L)).thenReturn(b);
        when(ordOrderMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(preorder("PREORD-B", "delivered", "delivered")));
        GzUnifiedOrderVo detail = service.getDetailForAdmin(302L);
        assertEquals("done", detail.getChipStatus());
        assertEquals("已完成", detail.getChipLabel());

        assertNull(service.getDetailForAdmin(null), "null txnId → null");
    }

    // ============================================================
    //  fixtures
    // ============================================================

    private static GzUnifiedOrderVo byTxnId(List<GzUnifiedOrderVo> rows, long id) {
        return rows.stream().filter(r -> r.getTransactionId() == id).findFirst().orElseThrow();
    }

    private static GzPayTransaction txn(Long id, String bizType, String bizOrderNo, long amountCent, String status) {
        GzPayTransaction t = new GzPayTransaction();
        t.setId(id);
        t.setOutTradeNo((bizOrderNo == null ? "TEST-20260618-000001" : bizOrderNo));
        t.setBusinessType(bizType);
        t.setBusinessOrderNo(bizOrderNo);
        t.setAmountCent(amountCent);
        t.setStatus(status);
        t.setUserId(2001L);
        t.setCreateTime(new Date());
        return t;
    }

    private static GzOrdOrder preorder(String orderNo, String bizStatus, String logisticsStatus) {
        GzOrdOrder o = new GzOrdOrder();
        o.setOrderNo(orderNo);
        o.setBusinessStatus(bizStatus);
        o.setLogisticsStatus(logisticsStatus);
        o.setQty(1);
        o.setTotalAmountCent(19900L);
        o.setProductSnapshotJson("{\"name\":\"CHIIKAWA 限定\",\"ipTag\":\"CHIIKAWA\",\"deliveryDateText\":\"2026年8月\"}");
        o.setSkuSnapshotJson("{\"specName\":\"整盒12个\",\"priceCent\":19900}");
        o.setAddressSnapshotJson("{\"recipient\":\"张三\",\"mobile\":\"13800000000\",\"province\":\"四川省\",\"city\":\"成都市\",\"district\":\"武侯区\",\"detail\":\"xx路1号\"}");
        o.setCreateTime(new Date());
        return o;
    }

    private static GzGachaOrder gacha(String orderNo, String bizStatus, String logisticsStatus) {
        GzGachaOrder o = new GzGachaOrder();
        o.setOrderNo(orderNo);
        o.setBusinessStatus(bizStatus);
        o.setLogisticsStatus(logisticsStatus);
        o.setTotalAmountCent(3000L);
        o.setPrizeSnapshotJson("{\"name\":\"SSR 海洋之心\",\"rarity\":\"SSR\",\"imageId\":\"5001\"}");
        o.setMachineSnapshotJson("{\"name\":\"一番赏·海洋\",\"coverImageId\":\"6001\"}");
        o.setCreateTime(new Date());
        return o;
    }

    private static GzUserVO user(Long id, String nickname, String openid) {
        GzUserVO u = new GzUserVO();
        u.setId(id);
        u.setNickname(nickname);
        u.setOpenid(openid);
        return u;
    }
}
