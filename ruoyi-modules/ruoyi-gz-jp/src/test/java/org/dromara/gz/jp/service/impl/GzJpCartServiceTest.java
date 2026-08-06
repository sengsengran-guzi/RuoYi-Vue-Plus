package org.dromara.gz.jp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.jp.domain.entity.GzJpCartItem;
import org.dromara.gz.jp.domain.entity.GzJpProduct;
import org.dromara.gz.jp.domain.vo.GzJpCartGroupVO;
import org.dromara.gz.jp.domain.vo.GzJpCartItemVO;
import org.dromara.gz.jp.domain.vo.GzJpCartVO;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.mapper.GzJpCartItemMapper;
import org.dromara.gz.jp.mapper.GzJpProductMapper;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单测（GZ-JP-104，FLOW:F-JP-02.step2）。
 *
 * <p><b>accept 第 2 条直接跑本类</b>（「同商品重复加购合并数量而非新增行」）。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>★ 重复加购累加不新增行</b>（AC 1）+ 并发撞唯一键后转累加，仍不产生第二行</li>
 *   <li><b>★ 失效项标 invalid 且不计入合计</b>（AC 2）—— 场已结束 / 商品下架 / 商品已删三类</li>
 *   <li><b>★ 跨场商品共存于同一购物车</b>（AC 3）—— 按场分组，可下单的场在前、孤儿组沉底</li>
 *   <li>加购是写路径：走严格闸 {@code isBookable}，已结束场的在架商品加不进车</li>
 *   <li>用户隔离：所有查询 / 删除的 SQL 条件必含 user_id</li>
 *   <li>数量上限 / 款数上限 / qty=0 走 DELETE 而非 PUT</li>
 *   <li>图片：同图只换一次签名、解析失败与 null 都回落占位图（绝不给 null）</li>
 *   <li>ID 跨层契约：VO 的 id / productId / eventId 序列化为 string；VO 无运费字段（全包邮）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GzJpCartServiceTest {

    private static final Long USER_ID = 11L;
    private static final Long OTHER_USER_ID = 22L;
    private static final Long PRODUCT_ID = 5001L;
    private static final Long EVENT_ID = 3001L;
    private static final Long MAIN_IMAGE_ID = 901L;
    private static final String MAIN_IMAGE_URL = "https://oss.example.com/jp/main.png?sign=aaa";
    private static final String PLACEHOLDER = "/static/images/mock-product.png";

    @Mock
    private GzJpCartItemMapper baseMapper;

    @Mock
    private GzJpProductMapper productMapper;

    @Mock
    private IGzJpEventService eventService;

    @Mock
    private IGzFileService fileService;

    private GzJpCartServiceImpl service;

    /**
     * LambdaQueryWrapper 的 {@code getSqlSegment()} 会急切解析列名，需要 MyBatis-Plus 的表信息缓存；
     * 纯 Mockito 单测里没有 Spring 容器装它，不 init 会报「can not find lambda cache」。
     * 幂等、无副作用（项目既有踩坑记录）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzJpCartItem.class);
        TableInfoHelper.initTableInfo(assistant, GzJpProduct.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzJpCartServiceImpl(baseMapper, productMapper, eventService, fileService);
    }

    // ============================================================
    //  ★ AC 1：同商品重复加购 = 数量累加，不产生第二行
    // ============================================================

    @Test
    @DisplayName("★ 同商品重复加购 → 数量累加，绝不 insert 第二行")
    void repeatedAddMergesQtyInsteadOfInsertingSecondRow() {
        givenBookableOnShelfProduct();
        givenExistingRowMergingTo(2, 3, 5);

        int merged = service.addItem(USER_ID, PRODUCT_ID, 3);

        assertEquals(5, merged, "2 + 3 = 5");
        verify(baseMapper, never()).insert(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("★★ 累加走原子自增 SQL（不是读改写 —— 并发两次 +3 读改写会丢更新；也不是 FOR UPDATE 加锁读，那会与失败 INSERT 的 S 锁撞死锁）")
    void mergeUsesAtomicIncrementNotReadModifyWrite() {
        givenBookableOnShelfProduct();
        givenExistingRowMergingTo(2, 1, 3);

        service.addItem(USER_ID, PRODUCT_ID, 1);

        verify(baseMapper, times(1)).increaseQty(USER_ID, PRODUCT_ID, 1, 99);
        // 读改写会体现为 updateById(实体)；原子自增路径下一次都不该有
        verify(baseMapper, never()).updateById(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("★★ addItem 必须在事务之外跑（NOT_SUPPORTED）—— 包事务会同时引入死锁 + RR 快照读不到并发新增行")
    void addItemMustRunOutsideTransaction() throws NoSuchMethodException {
        Transactional ann = GzJpCartServiceImpl.class
            .getMethod("addItem", Long.class, Long.class, Integer.class)
            .getAnnotation(Transactional.class);
        assertNotNull(ann, "必须显式标注传播行为，否则被带事务的上层调用时会静默加入外层事务");
        assertEquals(Propagation.NOT_SUPPORTED, ann.propagation(),
            "改成 REQUIRED/REQUIRES_NEW 会让「INSERT 撞唯一键 → 转累加」重新死锁（真库 10 并发压出过 7 个 500）");
    }

    @Test
    @DisplayName("首次加购 → insert 一行，数量 = 入参")
    void firstAddInsertsOneRow() {
        givenBookableOnShelfProduct();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(baseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(baseMapper.insert(any(GzJpCartItem.class))).thenReturn(1);

        assertEquals(2, service.addItem(USER_ID, PRODUCT_ID, 2));

        ArgumentCaptor<GzJpCartItem> captor = ArgumentCaptor.forClass(GzJpCartItem.class);
        verify(baseMapper).insert(captor.capture());
        GzJpCartItem inserted = captor.getValue();
        assertEquals(USER_ID, inserted.getUserId());
        assertEquals(PRODUCT_ID, inserted.getProductId());
        assertEquals(2, inserted.getQty());
        assertNull(inserted.getTenantId(), "tenant_id 不显式赋值，走 InjectionMetaObjectHandler 自动注入");
        verify(baseMapper, never()).updateById(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("qty 缺省（null）→ 视为 1")
    void nullQtyDefaultsToOne() {
        givenBookableOnShelfProduct();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(baseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(baseMapper.insert(any(GzJpCartItem.class))).thenReturn(1);

        assertEquals(1, service.addItem(USER_ID, PRODUCT_ID, null));
    }

    @Test
    @DisplayName("★★ 并发双击撞唯一键 → 加锁读重查转累加，仍不产生第二行")
    void concurrentAddHittingUniqueKeyFallsBackToMerge() {
        givenBookableOnShelfProduct();
        // 首查为空（决定走 insert）→ insert 撞唯一键 → 原子自增打在对手插的行上 → 回查得 3
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(null, cartItem(77L, USER_ID, PRODUCT_ID, 3));
        when(baseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(baseMapper.insert(any(GzJpCartItem.class))).thenThrow(new DuplicateKeyException("uk_user_product"));
        when(baseMapper.increaseQty(USER_ID, PRODUCT_ID, 2, 99)).thenReturn(1);

        assertEquals(3, service.addItem(USER_ID, PRODUCT_ID, 2), "对手的 1 + 本次 2");

        verify(baseMapper, times(1)).insert(any(GzJpCartItem.class));
        verify(baseMapper, times(1)).increaseQty(USER_ID, PRODUCT_ID, 2, 99);
    }

    @Test
    @DisplayName("撞唯一键后自增也打不中（同一瞬间被删）→ 明确报错让客人重试，不产生第二行")
    void duplicateKeyWithoutSurvivingRowFailsClearly() {
        givenBookableOnShelfProduct();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(baseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(baseMapper.insert(any(GzJpCartItem.class))).thenThrow(new DuplicateKeyException("uk_user_product"));
        when(baseMapper.increaseQty(USER_ID, PRODUCT_ID, 1, 99)).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.addItem(USER_ID, PRODUCT_ID, 1));
        assertTrue(ex.getMessage().contains("重试"));
        verify(baseMapper, times(1)).insert(any(GzJpCartItem.class));
    }

    // ============================================================
    //  加购的写路径闸门（严格 isBookable + 商品在架）
    // ============================================================

    @Test
    @DisplayName("商品不存在 / 已软删 → 拒绝加购")
    void addRejectsMissingProduct() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.addItem(USER_ID, PRODUCT_ID, 1));
        assertEquals("该商品已下架", ex.getMessage());
        verify(baseMapper, never()).insert(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("商品已下架（off_shelf）→ 拒绝加购")
    void addRejectsOffShelfProduct() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product(PRODUCT_ID, EVENT_ID, "off_shelf", 12800L));
        assertThrows(ServiceException.class, () -> service.addItem(USER_ID, PRODUCT_ID, 1));
        verify(eventService, never()).isBookable(anyLong());
        verify(baseMapper, never()).insert(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("★ 已结束场里的在架商品「看得到但加不进车」—— 加购走严格闸 isBookable")
    void addRejectsProductOfClosedEvent() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L));
        when(eventService.isBookable(EVENT_ID)).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.addItem(USER_ID, PRODUCT_ID, 1));
        assertEquals("本场已结束，无法加入购物车", ex.getMessage());
        verify(baseMapper, never()).insert(any(GzJpCartItem.class));
        // 只读的 isVisible 是浏览闸，写路径不许用
        verify(eventService, never()).isVisible(anyLong());
    }

    @Test
    @DisplayName("未登录（userId 为 null）→ service 层最后一道也拦")
    void addRejectsAnonymous() {
        assertThrows(ServiceException.class, () -> service.addItem(null, PRODUCT_ID, 1));
        verifyNoInteractions(productMapper);
    }

    // ============================================================
    //  数量 / 款数上限
    // ============================================================

    @Test
    @DisplayName("累加后超 99 件 → 拒绝且不落库（不静默截断）")
    void mergeBeyondQtyMaxRejected() {
        givenBookableOnShelfProduct();
        GzJpCartItem exist = cartItem(77L, USER_ID, PRODUCT_ID, 98);
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(exist);
        // 上限守卫在 SQL WHERE 里 → 影响 0 行；回查仍是 98 说明行还在，即「超上限」而非「行没了」
        when(baseMapper.increaseQty(USER_ID, PRODUCT_ID, 5, 99)).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.addItem(USER_ID, PRODUCT_ID, 5));
        assertTrue(ex.getMessage().contains("99"));
        assertEquals(98, exist.getQty(), "★ 不静默截断成 99");
        verify(baseMapper, never()).updateById(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("单次加购超 99 件 → 拒绝（连商品都不查）")
    void singleAddBeyondQtyMaxRejected() {
        assertThrows(ServiceException.class, () -> service.addItem(USER_ID, PRODUCT_ID, 100));
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("车内已 50 款再加新款 → 拒绝")
    void addBeyondCartRowsMaxRejected() {
        givenBookableOnShelfProduct();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(baseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(50L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.addItem(USER_ID, PRODUCT_ID, 1));
        assertTrue(ex.getMessage().contains("50"));
        verify(baseMapper, never()).insert(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("款数已满时，对车里已有商品累加仍放行（款数上限只管新增行）")
    void mergeNotBlockedByCartRowsMax() {
        givenBookableOnShelfProduct();
        givenExistingRowMergingTo(1, 1, 2);

        assertEquals(2, service.addItem(USER_ID, PRODUCT_ID, 1));
        verify(baseMapper, never()).selectCount(any(LambdaQueryWrapper.class));
    }

    // ============================================================
    //  改数量
    // ============================================================

    @Test
    @DisplayName("改数量 = 绝对赋值")
    void updateQtySetsAbsoluteValue() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cartItem(77L, USER_ID, PRODUCT_ID, 2));
        when(baseMapper.updateById(any(GzJpCartItem.class))).thenReturn(1);

        assertTrue(service.updateQty(USER_ID, 77L, 5));

        ArgumentCaptor<GzJpCartItem> captor = ArgumentCaptor.forClass(GzJpCartItem.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals(5, captor.getValue().getQty(), "绝对赋值，不是 2+5");
    }

    @Test
    @DisplayName("改数量：不是本人的项 / 不存在 → 统一报「不存在」，不泄漏别人车里有什么")
    void updateQtyOnForeignItemRejected() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateQty(OTHER_USER_ID, 77L, 5));
        assertEquals("购物车项不存在", ex.getMessage());
        verify(baseMapper, never()).updateById(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("改数量为同值 → 幂等成功，不打无谓 UPDATE")
    void updateQtyToSameValueIsIdempotent() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cartItem(77L, USER_ID, PRODUCT_ID, 3));
        assertTrue(service.updateQty(USER_ID, 77L, 3));
        verify(baseMapper, never()).updateById(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("改数量为 0 / 负数 → 拒绝（移除请走 DELETE）")
    void updateQtyToZeroRejected() {
        assertThrows(ServiceException.class, () -> service.updateQty(USER_ID, 77L, 0));
        assertThrows(ServiceException.class, () -> service.updateQty(USER_ID, 77L, -1));
        verify(baseMapper, never()).updateById(any(GzJpCartItem.class));
    }

    @Test
    @DisplayName("改数量不校验商品是否仍可下单（购物车是暂存区，失效在读时标、在下单时拦）")
    void updateQtyDoesNotRevalidateProduct() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cartItem(77L, USER_ID, PRODUCT_ID, 1));
        when(baseMapper.updateById(any(GzJpCartItem.class))).thenReturn(1);

        assertTrue(service.updateQty(USER_ID, 77L, 4));
        verifyNoInteractions(productMapper);
        verifyNoInteractions(eventService);
    }

    // ============================================================
    //  删除（物理删 + 用户隔离）
    // ============================================================

    @Test
    @DisplayName("★ 删除条件必含 user_id（租户拦截器只隔离租户，漏了就能删别人的车）")
    void deleteScopedToOwner() {
        when(baseMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);

        assertEquals(2, service.deleteItems(USER_ID, List.of(77L, 78L, 77L)));

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).delete(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"), "条件缺 user_id：" + sql);
        assertTrue(sql.contains("id IN"), "应按 id 集合删：" + sql);
    }

    @Test
    @DisplayName("删除空集合 → 拒绝（避免误发无条件 DELETE）")
    void deleteWithoutIdsRejected() {
        assertThrows(ServiceException.class, () -> service.deleteItems(USER_ID, List.of()));
        assertThrows(ServiceException.class, () -> service.deleteItems(USER_ID, null));
        verify(baseMapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    // ============================================================
    //  ★ AC 2 / AC 3：列表按场分组 + 实时标失效项
    // ============================================================

    @Test
    @DisplayName("空车 → groups 空数组（不是 null）+ 计数全 0，且不查商品表")
    void emptyCartReturnsEmptyArrays() {
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        GzJpCartVO cart = service.selectCart(USER_ID);

        assertNotNull(cart.getGroups(), "mp 对 null 做 v-for 会崩");
        assertTrue(cart.getGroups().isEmpty());
        assertEquals(0, cart.getItemCount());
        assertEquals(0, cart.getInvalidCount());
        assertEquals(0, cart.getValidQty());
        assertEquals(0L, cart.getValidAmountCent());
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("有效项：合计 = Σ 单价×数量，行金额后端算好")
    void validItemsSumUp() {
        givenCart(cartItem(1L, USER_ID, PRODUCT_ID, 2));
        givenProducts(product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L));
        givenEvents(eventOption(EVENT_ID, "open"));
        givenImageUrl();

        GzJpCartVO cart = service.selectCart(USER_ID);

        GzJpCartItemVO item = cart.getGroups().get(0).getItems().get(0);
        assertFalse(item.getInvalid());
        assertNull(item.getInvalidReason());
        assertEquals(25600L, item.getAmountCent());
        assertEquals(MAIN_IMAGE_URL, item.getMainImageUrl());
        assertEquals(1, cart.getItemCount());
        assertEquals(0, cart.getInvalidCount());
        assertEquals(2, cart.getValidQty());
        assertEquals(25600L, cart.getValidAmountCent());
        assertTrue(cart.getGroups().get(0).getEventBookable());
    }

    @Test
    @DisplayName("★ 场已结束 → invalid=event_closed，且不计入合计")
    void closedEventItemMarkedInvalidAndExcludedFromTotal() {
        givenCart(cartItem(1L, USER_ID, PRODUCT_ID, 2));
        givenProducts(product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L));
        givenEvents(eventOption(EVENT_ID, "closed"));
        givenImageUrl();

        GzJpCartVO cart = service.selectCart(USER_ID);

        GzJpCartGroupVO group = cart.getGroups().get(0);
        GzJpCartItemVO item = group.getItems().get(0);
        assertTrue(item.getInvalid());
        assertEquals("event_closed", item.getInvalidReason());
        assertEquals("本场已结束", item.getInvalidText());
        assertEquals(25600L, item.getAmountCent(), "行金额照给（前端展示划线价），但不进合计");
        assertFalse(group.getEventBookable());
        assertEquals("closed", group.getEventStatus());
        assertEquals(1, cart.getInvalidCount());
        assertEquals(0, cart.getValidQty(), "★ 失效项不计入");
        assertEquals(0L, cart.getValidAmountCent(), "★ 失效项不计入合计");
    }

    @Test
    @DisplayName("商品已下架 → invalid=product_off_shelf，不计入合计")
    void offShelfProductMarkedInvalid() {
        givenCart(cartItem(1L, USER_ID, PRODUCT_ID, 3));
        givenProducts(product(PRODUCT_ID, EVENT_ID, "off_shelf", 9900L));
        givenEvents(eventOption(EVENT_ID, "open"));
        givenImageUrl();

        GzJpCartVO cart = service.selectCart(USER_ID);

        GzJpCartItemVO item = cart.getGroups().get(0).getItems().get(0);
        assertTrue(item.getInvalid());
        assertEquals("product_off_shelf", item.getInvalidReason());
        assertEquals("商品已下架", item.getInvalidText());
        assertEquals(0L, cart.getValidAmountCent());
        assertTrue(cart.getGroups().get(0).getEventBookable(), "场本身还开着，只是这件商品下架了");
    }

    @Test
    @DisplayName("商品已被删 → invalid=product_removed + 归入孤儿组 + 名称回落，不给空白行")
    void removedProductGoesToOrphanGroup() {
        givenCart(cartItem(1L, USER_ID, PRODUCT_ID, 1));
        givenProducts(); // 商品表查不到
        givenEvents();

        GzJpCartVO cart = service.selectCart(USER_ID);

        GzJpCartGroupVO group = cart.getGroups().get(0);
        assertNull(group.getEventId(), "孤儿组无场");
        assertEquals("已失效商品", group.getEventName());
        assertFalse(group.getEventBookable());
        GzJpCartItemVO item = group.getItems().get(0);
        assertTrue(item.getInvalid());
        assertEquals("product_removed", item.getInvalidReason());
        assertEquals("商品已下架", item.getName());
        assertEquals(PLACEHOLDER, item.getMainImageUrl());
        assertEquals(0L, item.getPriceCent());
        assertEquals(0L, item.getAmountCent());
        assertEquals(0L, cart.getValidAmountCent());
    }

    @Test
    @DisplayName("场被删（商品还在）→ 当作已结束，不让整车查询挂掉")
    void deletedEventTreatedAsClosed() {
        givenCart(cartItem(1L, USER_ID, PRODUCT_ID, 1));
        givenProducts(product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L));
        givenEvents(); // 场 map 里没有
        givenImageUrl();

        GzJpCartVO cart = service.selectCart(USER_ID);

        GzJpCartGroupVO group = cart.getGroups().get(0);
        assertEquals(EVENT_ID, group.getEventId());
        assertEquals("已失效商品", group.getEventName(), "场没了但商品还挂着 event_id，仍是一个组");
        assertFalse(group.getEventBookable());
        assertEquals("event_closed", group.getItems().get(0).getInvalidReason());
        assertEquals(0L, cart.getValidAmountCent());
    }

    @Test
    @DisplayName("★ AC 3：跨场商品共存 —— 两场各一件 → 两组，可下单的场排在前")
    void crossEventItemsCoexistAndBookableGroupFirst() {
        Long eventB = 3002L;
        Long productB = 5002L;
        // id 倒序：先加的 A(id=1) 在后，后加的 B(id=2) 在前
        givenCart(cartItem(2L, USER_ID, productB, 1), cartItem(1L, USER_ID, PRODUCT_ID, 2));
        givenProducts(
            product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L),
            product(productB, eventB, "on_shelf", 6800L));
        givenEvents(eventOption(EVENT_ID, "open"), eventOption(eventB, "closed"));
        givenImageUrl();

        GzJpCartVO cart = service.selectCart(USER_ID);

        assertEquals(2, cart.getGroups().size(), "★ 跨场共存 → 两组");
        assertEquals(EVENT_ID, cart.getGroups().get(0).getEventId(), "可下单的场排前面");
        assertEquals(eventB, cart.getGroups().get(1).getEventId());
        assertTrue(cart.getGroups().get(0).getEventBookable());
        assertFalse(cart.getGroups().get(1).getEventBookable());
        // 合计只算 open 场那件
        assertEquals(2, cart.getItemCount());
        assertEquals(1, cart.getInvalidCount());
        assertEquals(2, cart.getValidQty());
        assertEquals(25600L, cart.getValidAmountCent());
    }

    @Test
    @DisplayName("分组排序：可下单场 → 已结束场 → 孤儿组沉底")
    void groupsSortedBookableThenClosedThenOrphan() {
        Long eventB = 3002L;
        Long productB = 5002L;
        Long ghostProduct = 5999L;
        givenCart(
            cartItem(3L, USER_ID, ghostProduct, 1),
            cartItem(2L, USER_ID, productB, 1),
            cartItem(1L, USER_ID, PRODUCT_ID, 1));
        givenProducts(
            product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L),
            product(productB, eventB, "on_shelf", 6800L));
        givenEvents(eventOption(EVENT_ID, "open"), eventOption(eventB, "closed"));
        givenImageUrl();

        List<GzJpCartGroupVO> groups = service.selectCart(USER_ID).getGroups();

        assertEquals(3, groups.size());
        assertEquals(EVENT_ID, groups.get(0).getEventId(), "可下单在最前");
        assertEquals(eventB, groups.get(1).getEventId(), "已结束场居中");
        assertNull(groups.get(2).getEventId(), "★ 孤儿组沉底，客人不必滚过一堆买不了的才够到「去结算」");
    }

    @Test
    @DisplayName("组内最近加购在前（id 倒序）")
    void itemsWithinGroupNewestFirst() {
        Long productB = 5002L;
        givenCart(cartItem(9L, USER_ID, productB, 1), cartItem(1L, USER_ID, PRODUCT_ID, 1));
        givenProducts(
            product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L),
            product(productB, EVENT_ID, "on_shelf", 6800L));
        givenEvents(eventOption(EVENT_ID, "open"));
        givenImageUrl();

        List<GzJpCartItemVO> items = service.selectCart(USER_ID).getGroups().get(0).getItems();

        assertEquals(2, items.size());
        assertEquals(9L, items.get(0).getId());
        assertEquals(1L, items.get(1).getId());
    }

    @Test
    @DisplayName("★ 列表查询条件必含 user_id + 按 id 倒序")
    void cartQueryScopedToOwner() {
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        service.selectCart(USER_ID);

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"), "条件缺 user_id：" + sql);
        assertTrue(sql.contains("ORDER BY") && sql.contains("DESC"), "应 id 倒序：" + sql);
    }

    // ============================================================
    //  图片解析
    // ============================================================

    @Test
    @DisplayName("同一张图在一次请求内只换一次签名")
    void sameImageSignedOnlyOnce() {
        Long productB = 5002L;
        givenCart(cartItem(2L, USER_ID, productB, 1), cartItem(1L, USER_ID, PRODUCT_ID, 1));
        givenProducts(
            product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L),
            product(productB, EVENT_ID, "on_shelf", 6800L)); // 共用同一张 MAIN_IMAGE_ID
        givenEvents(eventOption(EVENT_ID, "open"));
        givenImageUrl();

        service.selectCart(USER_ID);

        verify(fileService, times(1)).getPresignedUrl(MAIN_IMAGE_ID);
    }

    @Test
    @DisplayName("图片解析失败 / 无主图 → 回落占位图，绝不给 null（mp 会渲染成裂图）")
    void imageFailureFallsBackToPlaceholder() {
        givenCart(cartItem(1L, USER_ID, PRODUCT_ID, 1));
        GzJpProduct p = product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L);
        givenProducts(p);
        givenEvents(eventOption(EVENT_ID, "open"));
        when(fileService.getPresignedUrl(MAIN_IMAGE_ID)).thenThrow(new ServiceException("文件不存在"));

        GzJpCartItemVO item = service.selectCart(USER_ID).getGroups().get(0).getItems().get(0);
        assertEquals(PLACEHOLDER, item.getMainImageUrl());
    }

    // ============================================================
    //  给 GZ-JP-105 的取数口
    // ============================================================

    @Test
    @DisplayName("selectByUserAndIds：空 ids → 空列表，不打库")
    void selectByUserAndIdsEmpty() {
        assertTrue(service.selectByUserAndIds(USER_ID, List.of()).isEmpty());
        verify(baseMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("selectByUserAndIds：条件必含 user_id（下单时不能拿到别人的车）")
    void selectByUserAndIdsScopedToOwner() {
        List<GzJpCartItem> rows = List.of(cartItem(77L, USER_ID, PRODUCT_ID, 1));
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(rows);

        assertSame(rows, service.selectByUserAndIds(USER_ID, List.of(77L)));

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("user_id"));
    }

    // ============================================================
    //  跨层契约
    // ============================================================

    @Test
    @DisplayName("ID 跨层契约：VO 的 id / productId / eventId 序列化为 string")
    void idsSerializedAsString() throws NoSuchFieldException {
        for (String name : List.of("id", "productId", "eventId")) {
            Field f = GzJpCartItemVO.class.getDeclaredField(name);
            JsonSerialize ann = f.getAnnotation(JsonSerialize.class);
            assertNotNull(ann, name + " 缺 @JsonSerialize（Java long → JS number 会丢精度）");
            assertEquals(ToStringSerializer.class, ann.using());
        }
        Field groupEventId = GzJpCartGroupVO.class.getDeclaredField("eventId");
        assertEquals(ToStringSerializer.class, groupEventId.getAnnotation(JsonSerialize.class).using());
    }

    @Test
    @DisplayName("★ 全包邮：整车 VO 不得出现运费字段")
    void cartVoHasNoFreightField() {
        List<String> suspicious = new ArrayList<>();
        for (Field f : GzJpCartVO.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            if (n.contains("freight") || n.contains("shipping") || n.contains("postage") || n.contains("express")) {
                suspicious.add(f.getName());
            }
        }
        assertTrue(suspicious.isEmpty(), "REQ-ORDER-004 全包邮，购物车不许有运费字段：" + suspicious);
    }

    // ============================================================
    //  fixtures
    // ============================================================

    private void givenBookableOnShelfProduct() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product(PRODUCT_ID, EVENT_ID, "on_shelf", 12800L));
        when(eventService.isBookable(EVENT_ID)).thenReturn(true);
    }

    /**
     * 车里已有一行 qty={@code before}，原子自增 {@code add} 成功后回查得 {@code after}。
     *
     * <p>两次 {@code selectOne}：第一次是「找行」，第二次是自增后的回查。</p>
     */
    private void givenExistingRowMergingTo(int before, int add, int after) {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
            cartItem(77L, USER_ID, PRODUCT_ID, before),
            cartItem(77L, USER_ID, PRODUCT_ID, after));
        when(baseMapper.increaseQty(USER_ID, PRODUCT_ID, add, 99)).thenReturn(1);
    }

    private void givenCart(GzJpCartItem... items) {
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(items));
    }

    private void givenProducts(GzJpProduct... products) {
        when(productMapper.selectByIds(any())).thenReturn(Arrays.asList(products));
    }

    private void givenEvents(GzJpEventOptionVO... events) {
        Map<Long, GzJpEventOptionVO> map = new java.util.HashMap<>();
        for (GzJpEventOptionVO e : events) {
            map.put(e.getId(), e);
        }
        when(eventService.selectOptionMap(any())).thenReturn(map);
    }

    private void givenImageUrl() {
        GzFileObjectVO file = new GzFileObjectVO();
        file.setUrl(MAIN_IMAGE_URL);
        when(fileService.getPresignedUrl(MAIN_IMAGE_ID)).thenReturn(file);
    }

    private GzJpCartItem cartItem(Long id, Long userId, Long productId, Integer qty) {
        GzJpCartItem item = new GzJpCartItem();
        item.setId(id);
        item.setUserId(userId);
        item.setProductId(productId);
        item.setQty(qty);
        return item;
    }

    private GzJpProduct product(Long id, Long eventId, String status, Long priceCent) {
        GzJpProduct p = new GzJpProduct();
        p.setId(id);
        p.setEventId(eventId);
        p.setProductNo("JPP-20260807-00000" + id);
        p.setName("商品-" + id);
        p.setMainImageId(MAIN_IMAGE_ID);
        p.setPriceCent(priceCent);
        p.setStatus(status);
        return p;
    }

    private GzJpEventOptionVO eventOption(Long id, String effectiveStatus) {
        GzJpEventOptionVO vo = new GzJpEventOptionVO();
        vo.setId(id);
        vo.setEventNo("EVT-20260807-00000" + id);
        vo.setName("场-" + id);
        vo.setStatus(effectiveStatus);
        return vo;
    }

    /** 保留：Set 形式的 ids 断言用（selectByIds 入参是 LinkedHashSet） */
    @SuppressWarnings("unused")
    private Set<Long> ids(Long... values) {
        return new java.util.LinkedHashSet<>(Arrays.asList(values));
    }
}
