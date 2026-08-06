package org.dromara.gz.jp.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.jp.domain.entity.GzJpCartItem;
import org.dromara.gz.jp.domain.entity.GzJpProduct;
import org.dromara.gz.jp.domain.enums.GzJpCartInvalidReason;
import org.dromara.gz.jp.domain.enums.GzJpEventStatus;
import org.dromara.gz.jp.domain.enums.GzJpProductStatus;
import org.dromara.gz.jp.domain.vo.GzJpCartGroupVO;
import org.dromara.gz.jp.domain.vo.GzJpCartItemVO;
import org.dromara.gz.jp.domain.vo.GzJpCartVO;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.mapper.GzJpCartItemMapper;
import org.dromara.gz.jp.mapper.GzJpProductMapper;
import org.dromara.gz.jp.service.IGzJpCartService;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 拼团购物车服务实现（GZ-JP-104，FLOW:F-JP-02.step2）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_cart_item} 段。
 * UI 口径：{@code UI:mp.cart} / {@code UI:mp.cart.group} / {@code UI:mp.cart.summary}。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li><b>重复加购 = 数量累加</b>（AC 第 1 条）：先查后累加；并发双击靠
 *       {@code UNIQUE(tenant_id, user_id, product_id)} 兜底，捕获 {@link DuplicateKeyException}
 *       后重查一次转累加，绝不产生第二行。</li>
 *   <li><b>加购走严格闸 {@code isBookable}</b>（不是只读路径的 {@code isVisible}）：
 *       已结束的场里的商品「看得到」但「加不进车」。这是 doc/jp 可见性契约里点名的写路径纪律。</li>
 *   <li><b>跨场共存</b>：车里不限制单场，列表按场分组下发（UI:mp.cart.group）。</li>
 *   <li><b>失效判定读时惰性、不落库</b>：每次 {@code selectCart} 实时算，场重开 / 商品重新上架
 *       购物车项自动恢复。本项目 prod 未部署 SnailJob，任何依赖 cron 刷状态的设计都会静默失效。</li>
 *   <li><b>失效项照常下发但不计入合计</b>：客人要看得见自己加过什么；
 *       {@code invalid=true} 让前端置灰、不可勾选（UI:mp.cart）。</li>
 *   <li><b>不锁价</b>：单价每次实时读商品表。价格锁定发生在下单那一刻（GZ-JP-105 的
 *       {@code product_snapshot_json}），购物车不是价格承诺。</li>
 *   <li><b>不维护勾选态</b>：勾选是纯前端交互，落库只会产生「勾选态与商品状态不一致」的脏数据。
 *       后端只给「全选口径」的合计，实际勾选合计由前端按 {@code amountCent} 累加。</li>
 *   <li><b>物理删</b>：见 {@link GzJpCartItem} 类注释（软删会撞唯一键）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpCartServiceImpl implements IGzJpCartService {

    /**
     * 单款商品数量上限。
     *
     * <p>99 是 stepper 的常规上限；集单预订无库存概念，这个数只防误触长按刷出天量数字
     * （下游订单金额 = 单价 × 数量，一个 6 位数的 qty 能让订单金额溢出成客服事故）。</p>
     */
    private static final int QTY_MAX = 99;

    /**
     * 车内款数（行数）上限。
     *
     * <p>REQ-ORDER-005 的场景是「一个客人买了 30 款」，50 留足余量。
     * 同时它给列表接口的预签名扇出封了顶 —— 每款主图一次 {@code getPresignedUrl}，
     * 无上限的车会把一次列表请求放大成上百次查询。</p>
     */
    private static final int CART_ROWS_MAX = 50;

    /** 主图缺失 / 解析失败 / 商品已删时的占位图（与 GZ-JP-103 商品 mp VO 同一张）。 */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    /** 商品已被删除时列表里的行标题（不留空白行，也不泄漏「被删除」这个后台动作）。 */
    private static final String REMOVED_PRODUCT_NAME = "商品已下架";

    /** 孤儿组（商品已删、归不到任何场）的分组头标题。 */
    private static final String ORPHAN_GROUP_NAME = "已失效商品";

    private final GzJpCartItemMapper baseMapper;
    private final GzJpProductMapper productMapper;
    private final IGzJpEventService eventService;
    private final IGzFileService fileService;

    // ============================================================
    //  写（FLOW:F-JP-02.step2）
    // ============================================================

    /**
     * {@inheritDoc}
     *
     * <p><b>★★ 刻意<u>不</u>包事务（{@link Propagation#NOT_SUPPORTED}），别"顺手"加回 {@code @Transactional}</b>
     * —— 本方法每条路径<b>只有一次写</b>（要么 INSERT 要么原子自增 UPDATE），没有需要事务保护的跨语句不变量，
     * 而包上事务会同时引入两个真实缺陷（都是真库压测逮出来的，不是理论顾虑）：</p>
     * <ol>
     *   <li><b>死锁</b>：事务里「INSERT 撞唯一键 → 再去锁那一行」，失败的 INSERT 会在冲突记录上留 S 锁并
     *       <b>持有到事务结束</b>；多个并发请求各持一把 S 锁再互相要 X 锁 → Deadlock（10 并发压出 7 个 500）。
     *       非事务下失败 INSERT 的隐式事务当场结束、S 锁立即释放，后续语句是全新事务，锁不跨语句累积。</li>
     *   <li><b>快照读看不见对手</b>：MySQL 隔离级别 REPEATABLE READ，事务内的一致性读快照在第一次读时就固定。
     *       「先查（没有）→ INSERT 撞键 → 重查」的重查仍在旧快照里，<b>永远返回 null</b>，兜底逻辑当场失效。
     *       非事务下每条语句自成事务，每次读都是最新已提交。</li>
     * </ol>
     * <p>用 {@code NOT_SUPPORTED} 而非"不写注解"：将来若被某个带事务的上层方法调用（如 GZ-JP-105 下单流程
     * 顺手加购），不写注解会静默加入外层事务、上面两个坑原样复活；{@code NOT_SUPPORTED} 会挂起外层事务。</p>
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int addItem(Long userId, Long productId, Integer qty) {
        requireUser(userId);
        if (ObjectUtil.isNull(productId)) {
            throw new ServiceException("请选择商品");
        }
        int add = ObjectUtil.defaultIfNull(qty, 1);
        if (add < 1) {
            throw new ServiceException("数量至少为 1");
        }
        if (add > QTY_MAX) {
            throw new ServiceException("单款商品最多 " + QTY_MAX + " 件");
        }

        // ── 加购是写路径：商品必须在架 + 场必须「可下单」（isBookable，不是只读用的 isVisible）
        GzJpProduct product = productMapper.selectById(productId);
        if (product == null || GzJpProductStatus.of(product.getStatus()) != GzJpProductStatus.ON_SHELF) {
            throw new ServiceException("该商品已下架");
        }
        if (!eventService.isBookable(product.getEventId())) {
            throw new ServiceException("本场已结束，无法加入购物车");
        }

        if (selectOneByProduct(userId, productId) != null) {
            return accumulate(userId, productId, add);
        }
        // 只有真要新增一行时才查车内款数（改数量 / 累加不受款数上限影响）
        long rows = baseMapper.selectCount(byUser(userId));
        if (rows >= CART_ROWS_MAX) {
            throw new ServiceException("购物车最多放 " + CART_ROWS_MAX + " 款商品，请先结算或删除部分商品");
        }

        GzJpCartItem item = new GzJpCartItem();
        item.setUserId(userId);
        item.setProductId(productId);
        item.setQty(add);
        try {
            if (baseMapper.insert(item) <= 0) {
                throw new ServiceException("加入购物车失败");
            }
        } catch (DuplicateKeyException dup) {
            // ★ 并发双击：另一个请求刚插进同一 (user, product)。唯一键把第二行挡在门外，
            //   这里转累加 —— 绝不吞掉这次加购，也绝不产生第二行（AC 第 1 条）。
            //   能这么写的前提是本方法不在事务里（见类上方法注释）：失败 INSERT 的 S 锁已随隐式事务释放，
            //   随后的 UPDATE 不会与它撞死锁；重查也不再受旧快照困住。
            log.info("[gz-jp-cart] 并发加购撞唯一键，转累加 userId={} productId={}", userId, productId);
            return accumulate(userId, productId, add);
        }
        log.info("[gz-jp-cart] ADD id={} userId={} productId={} qty={}", item.getId(), userId, productId, add);
        return add;
    }

    /**
     * 已有行 → 数量累加（<b>原子自增</b>，见 {@link GzJpCartItemMapper#increaseQty}）。
     *
     * <p>超上限直接报错<b>不静默截断</b>：客人以为加进去了才是真事故。
     * 上限守卫在 SQL 的 WHERE 里，所以影响行数 0 有两种可能（无此行 / 会超上限），再查一次区分 ——
     * 非事务执行，这次查读得到最新已提交数据。</p>
     */
    private int accumulate(Long userId, Long productId, int add) {
        int changed = baseMapper.increaseQty(userId, productId, add, QTY_MAX);
        GzJpCartItem after = selectOneByProduct(userId, productId);
        if (changed <= 0) {
            if (after == null) {
                // 极端并发：刚才还在的行，在自增前被本人在另一端删掉了。重试会走 INSERT 分支
                throw new ServiceException("加入购物车失败，请重试");
            }
            throw new ServiceException("该商品数量已达上限（" + QTY_MAX + " 件）");
        }
        // after 理论上不为 null（自己刚改过）；真被并发删掉时至少返回本次加入量，不让接口崩
        int merged = after == null ? add : ObjectUtil.defaultIfNull(after.getQty(), add);
        log.info("[gz-jp-cart] MERGE userId={} productId={} qty={} (+{})", userId, productId, merged, add);
        return merged;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateQty(Long userId, Long id, Integer qty) {
        requireUser(userId);
        if (ObjectUtil.isNull(id)) {
            throw new ServiceException("购物车项 ID 不能为空");
        }
        if (ObjectUtil.isNull(qty) || qty < 1) {
            throw new ServiceException("数量至少为 1，如需移除请删除该商品");
        }
        if (qty > QTY_MAX) {
            throw new ServiceException("单款商品最多 " + QTY_MAX + " 件");
        }
        GzJpCartItem exist = selectOneById(userId, id);
        if (exist == null) {
            // 不区分「不存在」与「不是你的」—— 不向调用方泄漏别人车里有什么
            throw new ServiceException("购物车项不存在");
        }
        if (Objects.equals(exist.getQty(), qty)) {
            // stepper 弱网重发同一个值：幂等成功，不必打一次无谓的 UPDATE
            return true;
        }
        exist.setQty(qty);
        if (baseMapper.updateById(exist) <= 0) {
            throw new ServiceException("修改数量失败");
        }
        log.info("[gz-jp-cart] UPDATE QTY id={} userId={} qty={}", id, userId, qty);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteItems(Long userId, Collection<Long> ids) {
        requireUser(userId);
        List<Long> clean = cleanIds(ids);
        if (clean.isEmpty()) {
            throw new ServiceException("请选择要删除的商品");
        }
        // ★ 条件里必须带 user_id：租户拦截器只隔离租户不隔离用户，漏了就能删别人的车
        int deleted = baseMapper.delete(byUser(userId).in(GzJpCartItem::getId, clean));
        log.info("[gz-jp-cart] DELETE userId={} ids={} deleted={}", userId, clean, deleted);
        return deleted;
    }

    // ============================================================
    //  读（UI:mp.cart —— 按场分组 + 实时标失效项）
    // ============================================================

    @Override
    public GzJpCartVO selectCart(Long userId) {
        requireUser(userId);
        // 最近加购在前（id 倒序）：客人刚加的那件应该一眼看到
        List<GzJpCartItem> items = baseMapper.selectList(byUser(userId).orderByDesc(GzJpCartItem::getId));
        if (items.isEmpty()) {
            return emptyCart();
        }

        // 商品批量取（selectByIds 已过滤软删 → 查不到即视为「商品已删」）
        Set<Long> productIds = new LinkedHashSet<>(items.stream().map(GzJpCartItem::getProductId).toList());
        Map<Long, GzJpProduct> productMap = new HashMap<>();
        for (GzJpProduct p : productMapper.selectByIds(productIds)) {
            productMap.put(p.getId(), p);
        }
        // 场批量取（一车可能跨多场 —— 逐条 isBookable 就是 N+1）；status 已是读时惰性判定后的生效状态
        Set<Long> eventIds = new LinkedHashSet<>(productMap.values().stream().map(GzJpProduct::getEventId).toList());
        Map<Long, GzJpEventOptionVO> eventMap = eventService.selectOptionMap(eventIds);
        // 同一张图在一次请求内只换一次签名（同场商品共用盒图是常态）
        Map<Long, String> urlCache = new HashMap<>();

        // 分组：key = 场 id；商品已删的项归不到场 → null key 的孤儿组（LinkedHashMap 允许 null key）
        Map<Long, List<GzJpCartItemVO>> grouped = new LinkedHashMap<>();
        int invalidCount = 0;
        int validQty = 0;
        long validAmountCent = 0L;

        for (GzJpCartItem item : items) {
            GzJpProduct product = productMap.get(item.getProductId());
            GzJpCartInvalidReason reason = resolveInvalidReason(product, eventMap);
            GzJpCartItemVO vo = toItemVO(item, product, reason, urlCache);
            if (reason != null) {
                invalidCount++;
            } else {
                validQty += vo.getQty();
                validAmountCent += vo.getAmountCent();
            }
            Long groupKey = product == null ? null : product.getEventId();
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(vo);
        }

        GzJpCartVO cart = new GzJpCartVO();
        cart.setGroups(buildGroups(grouped, eventMap));
        cart.setItemCount(items.size());
        cart.setInvalidCount(invalidCount);
        cart.setValidQty(validQty);
        cart.setValidAmountCent(validAmountCent);
        return cart;
    }

    @Override
    public List<GzJpCartItem> selectByUserAndIds(Long userId, Collection<Long> ids) {
        requireUser(userId);
        List<Long> clean = cleanIds(ids);
        if (clean.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectList(byUser(userId).in(GzJpCartItem::getId, clean));
    }

    // ============================================================
    //  internal
    // ============================================================

    /**
     * 失效判定（读时惰性，不落库）。
     *
     * <p>优先级：商品已删 → 商品已下架 → 场已结束。命中即返回，全过则 null（有效）。</p>
     *
     * <p>场侧用 {@code selectOptionMap} 给的<b>生效状态</b>判 open，等价于 {@code isBookable}
     * 但不产生 N+1；判定逻辑本身仍只有 {@code GzJpEventStatus.effective} 一处实现，这里不重写。</p>
     */
    private GzJpCartInvalidReason resolveInvalidReason(GzJpProduct product, Map<Long, GzJpEventOptionVO> eventMap) {
        if (product == null) {
            return GzJpCartInvalidReason.PRODUCT_REMOVED;
        }
        if (GzJpProductStatus.of(product.getStatus()) != GzJpProductStatus.ON_SHELF) {
            return GzJpCartInvalidReason.PRODUCT_OFF_SHELF;
        }
        GzJpEventOptionVO event = eventMap.get(product.getEventId());
        if (event == null || !GzJpEventStatus.OPEN.getCode().equals(event.getStatus())) {
            // 场被删 / draft / closed / 到点 —— 对客人都是「这场不收单了」
            return GzJpCartInvalidReason.EVENT_CLOSED;
        }
        return null;
    }

    private GzJpCartItemVO toItemVO(GzJpCartItem item, GzJpProduct product,
                                    GzJpCartInvalidReason reason, Map<Long, String> urlCache) {
        GzJpCartItemVO vo = new GzJpCartItemVO();
        vo.setId(item.getId());
        vo.setProductId(item.getProductId());
        vo.setQty(ObjectUtil.defaultIfNull(item.getQty(), 0));

        if (product == null) {
            vo.setProductNo(null);
            vo.setName(REMOVED_PRODUCT_NAME);
            vo.setMainImageUrl(PLACEHOLDER_IMAGE_URL);
            vo.setPriceCent(0L);
            vo.setEventId(null);
        } else {
            vo.setProductNo(product.getProductNo());
            vo.setName(product.getName());
            vo.setMainImageUrl(resolveImageUrl(product.getMainImageId(), urlCache));
            vo.setPriceCent(ObjectUtil.defaultIfNull(product.getPriceCent(), 0L));
            vo.setEventId(product.getEventId());
        }
        vo.setAmountCent(vo.getPriceCent() * vo.getQty());

        vo.setInvalid(reason != null);
        vo.setInvalidReason(reason == null ? null : reason.getCode());
        vo.setInvalidText(reason == null ? null : reason.getText());
        return vo;
    }

    /**
     * 分组装配 + 排序。
     *
     * <p>顺序：<b>可下单的场在前 → 已结束的场 → 孤儿组（商品已删）最后</b>；
     * 同类内按「组内最近加购的那件」倒序，让客人刚操作过的场浮上来。
     * 失效的东西沉底，是购物车的通行做法 —— 别让客人为了点「去结算」先滚过一堆买不了的。</p>
     */
    private List<GzJpCartGroupVO> buildGroups(Map<Long, List<GzJpCartItemVO>> grouped,
                                              Map<Long, GzJpEventOptionVO> eventMap) {
        List<GzJpCartGroupVO> groups = new ArrayList<>();
        Map<Long, Long> newestItemId = new HashMap<>();

        for (Map.Entry<Long, List<GzJpCartItemVO>> e : grouped.entrySet()) {
            Long eventId = e.getKey();
            List<GzJpCartItemVO> list = e.getValue();
            GzJpEventOptionVO event = eventId == null ? null : eventMap.get(eventId);

            GzJpCartGroupVO group = new GzJpCartGroupVO();
            group.setEventId(eventId);
            group.setItems(list);
            if (event != null) {
                group.setEventNo(event.getEventNo());
                group.setEventName(event.getName());
                group.setEventStatus(event.getStatus());
                group.setEventBookable(GzJpEventStatus.OPEN.getCode().equals(event.getStatus()));
            } else {
                // eventId != null 但场查不到 = 场已被删；eventId == null = 商品已删的孤儿组
                group.setEventName(ORPHAN_GROUP_NAME);
                group.setEventBookable(false);
            }
            groups.add(group);
            // items 已是 id 倒序，首个即本组最近加购的那件
            newestItemId.put(eventId == null ? -1L : eventId, list.get(0).getId());
        }

        groups.sort(Comparator
            // 1) 孤儿组永远沉底
            .comparing((GzJpCartGroupVO g) -> g.getEventId() == null)
            // 2) 可下单的场在前
            .thenComparing(g -> !Boolean.TRUE.equals(g.getEventBookable()))
            // 3) 组内最近加购的在前
            .thenComparing(g -> newestItemId.get(g.getEventId() == null ? -1L : g.getEventId()),
                Comparator.reverseOrder()));
        return groups;
    }

    /**
     * file id → 可渲染签名 URL。NULL / 文件已删（抛异常）/ 空串 → 占位图。
     *
     * <p>绝不返回 null：mp {@code <image>} 拿到 null 会渲染成裂图，占位图至少布局不塌。
     * （与 {@code GzJpProductServiceImpl.resolveImageUrl} 同一策略；两处各自持有请求级 cache，
     * 不共享状态，故未上提工具类。）</p>
     */
    private String resolveImageUrl(Long fileId, Map<Long, String> urlCache) {
        if (fileId == null) {
            return PLACEHOLDER_IMAGE_URL;
        }
        String cached = urlCache.get(fileId);
        if (cached != null) {
            return cached;
        }
        String url;
        try {
            url = fileService.getPresignedUrl(fileId).getUrl();
        } catch (Exception ex) {
            log.warn("[gz-jp-cart] 商品图片解析失败 fileId={}，回退占位图：{}", fileId, ex.getMessage());
            url = PLACEHOLDER_IMAGE_URL;
        }
        if (StrUtil.isBlank(url)) {
            url = PLACEHOLDER_IMAGE_URL;
        }
        urlCache.put(fileId, url);
        return url;
    }

    private GzJpCartVO emptyCart() {
        GzJpCartVO cart = new GzJpCartVO();
        // ★ 空数组不是 null：mp 对 null 做 v-for 会崩
        cart.setGroups(List.of());
        cart.setItemCount(0);
        cart.setInvalidCount(0);
        cart.setValidQty(0);
        cart.setValidAmountCent(0L);
        return cart;
    }

    /** 本人购物车的基础查询条件 —— 所有查询 / 删除都必须从这里起手。 */
    private LambdaQueryWrapper<GzJpCartItem> byUser(Long userId) {
        return Wrappers.<GzJpCartItem>lambdaQuery().eq(GzJpCartItem::getUserId, userId);
    }

    private GzJpCartItem selectOneByProduct(Long userId, Long productId) {
        return baseMapper.selectOne(byUser(userId).eq(GzJpCartItem::getProductId, productId));
    }

    private GzJpCartItem selectOneById(Long userId, Long id) {
        return baseMapper.selectOne(byUser(userId).eq(GzJpCartItem::getId, id));
    }

    private void requireUser(Long userId) {
        if (ObjectUtil.isNull(userId)) {
            // controller 已挡过一道；这里是最后一道，防止将来有人从别处调进来漏了登录态
            throw new ServiceException("未登录");
        }
    }

    private List<Long> cleanIds(Collection<Long> ids) {
        if (ObjectUtil.isEmpty(ids)) {
            return List.of();
        }
        return ids.stream().filter(ObjectUtil::isNotNull).distinct().toList();
    }
}
