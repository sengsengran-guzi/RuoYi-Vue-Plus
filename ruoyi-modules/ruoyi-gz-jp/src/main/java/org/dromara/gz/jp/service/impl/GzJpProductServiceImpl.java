package org.dromara.gz.jp.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.jp.domain.bo.GzJpProductBo;
import org.dromara.gz.jp.domain.bo.GzJpProductQueryBo;
import org.dromara.gz.jp.domain.bo.GzJpProductStatusBo;
import org.dromara.gz.jp.domain.entity.GzJpProduct;
import org.dromara.gz.jp.domain.enums.GzJpEventStatus;
import org.dromara.gz.jp.domain.enums.GzJpProductStatus;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.domain.vo.GzJpProductAdminVO;
import org.dromara.gz.jp.domain.vo.GzJpProductMpVO;
import org.dromara.gz.jp.mapper.GzJpProductMapper;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.dromara.gz.jp.service.IGzJpProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拼团商品服务实现（GZ-JP-102，FLOW:F-JP-01.step2）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_product} 段。
 * 多租户 / 软删 / 公共字段注入 / 乐观锁均由 ruoyi MyBatis-Plus 拦截器完成。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li><b>新建即 off_shelf</b>：上架是显式动作（走 {@link #changeStatus}），避免半成品商品直接见客。</li>
 *   <li><b>建商品不要求场已 open</b>：FLOW:F-JP-01 的正常顺序是 建场(draft) → 上架商品 → 开场，
 *       所以这里只校验「场存在」，<b>不</b>调 {@code isBookable} 卡门。可见性由
 *       {@code visibleToCustomer = 商品 on_shelf && 场生效状态 open} 在读侧表达。</li>
 *   <li><b>场信息批量回填</b>：一页商品可能跨多个场，用
 *       {@link IGzJpEventService#selectOptionMap} 一次取回，避免逐条 {@code isBookable} 的 N+1。
 *       场的「读时惰性状态判定」只有一处实现（{@code GzJpEventStatus.effective}），本类不重写。</li>
 *   <li><b>product_no 生成含软删行</b>：uk_product_no 覆盖软删，序号必须跳过已被软删占用的号
 *       （gz_bean_booking 曾因此撞 409 DuplicateKey）。</li>
 *   <li><b>图集存逗号分隔 file id 串</b>（同 gz_recycle_appointment.verify_image_ids），出参展开成数组。
 *       一期不做富文本详情编辑器。</li>
 *   <li><b>无库存 / 无 SKU</b>：一期刻意不做（REQ-PROD-007 —— 不同规格各上架一个商品）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpProductServiceImpl implements IGzJpProductService {

    /** product_no = "JPP-" (4) + yyyyMMdd (8) + "-" (1) + 6 位序号 = 19。 */
    private static final DateTimeFormatter PRODUCT_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PRODUCT_NO_PREFIX = "JPP-";
    private static final int PRODUCT_NO_TOTAL_LEN = 19;
    private static final int PRODUCT_NO_SEQ_LEN = 6;
    private static final int PRODUCT_NO_SEQ_MAX = 999999;

    /** 售价上限：¥999,999.99。防手滑多打两个零（一期无二次确认，先用硬上限兜住）。 */
    private static final long PRICE_CENT_MAX = 99_999_999L;

    /** 图集张数上限 —— gallery_image_ids VARCHAR(512)，9 张（微信九宫格惯例）远在容量内。 */
    private static final int GALLERY_MAX = 9;

    /** 批量上下架单次条数上限（与履约看板批量操作同口径，防一次性刷全表）。 */
    private static final int BATCH_STATUS_MAX = 200;

    /** 主图 / 图集缺失或解析失败时的 mp 占位图（同 gz-jp-event / gz-gacha / gz-ord）。 */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    /**
     * mp 列表分页默认 / 上限（UI:mp.event_detail 两列网格上拉加载，20 = 10 行）。
     *
     * <p><b>不能用 {@code PageQuery} 的默认值</b>：它的 DEFAULT_PAGE_SIZE 是 {@code Integer.MAX_VALUE}，
     * mp 漏传 pageSize 就会 {@code LIMIT 2147483647} 把整场商品一次拉走 —— 而本接口<b>匿名可调</b>
     * 且每行都要一次 {@code selectById} + 一次预签名，等于给未鉴权调用方一个放大器。故此处强制收口。</p>
     */
    private static final int MP_PAGE_SIZE_DEFAULT = 20;
    private static final int MP_PAGE_SIZE_MAX = 50;

    private final GzJpProductMapper baseMapper;
    private final IGzJpEventService eventService;
    private final IGzFileService fileService;

    // ============================================================
    //  查询（UI:admin.product）
    // ============================================================

    @Override
    public TableDataInfo<GzJpProductAdminVO> selectAdminPage(GzJpProductQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzJpProduct> lqw = Wrappers.<GzJpProduct>lambdaQuery()
            .eq(ObjectUtil.isNotNull(query.getEventId()), GzJpProduct::getEventId, query.getEventId())
            .like(StrUtil.isNotBlank(query.getProductNo()), GzJpProduct::getProductNo, query.getProductNo())
            .like(StrUtil.isNotBlank(query.getName()), GzJpProduct::getName, query.getName())
            .orderByAsc(GzJpProduct::getSortNo)
            .orderByDesc(GzJpProduct::getId);
        applyStatusFilter(lqw, query.getStatus());

        Page<GzJpProduct> page = baseMapper.selectPage(pageQuery.build(), lqw);
        List<GzJpProduct> records = page.getRecords();

        // 场信息批量回填（一次查询，不做逐行 isBookable 的 N+1）
        Set<Long> eventIds = new LinkedHashSet<>(records.stream().map(GzJpProduct::getEventId).toList());
        Map<Long, GzJpEventOptionVO> eventMap = eventService.selectOptionMap(eventIds);

        Page<GzJpProductAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records.stream().map(p -> toAdminVO(p, eventMap.get(p.getEventId()))).toList());
        return TableDataInfo.build(voPage);
    }

    /** 状态筛选：未知值忽略（不误当 off_shelf 过滤，与场侧同口径）。 */
    private void applyStatusFilter(LambdaQueryWrapper<GzJpProduct> lqw, String status) {
        if (StrUtil.isBlank(status)) {
            return;
        }
        if (!GzJpProductStatus.isValid(status)) {
            log.warn("[gz-jp-admin] 忽略未知的商品状态筛选值 status={}", status);
            return;
        }
        lqw.eq(GzJpProduct::getStatus, status);
    }

    @Override
    public GzJpProductAdminVO selectAdminById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzJpProduct p = baseMapper.selectById(id);
        if (p == null) {
            return null;
        }
        return toAdminVO(p, eventService.selectOptionMap(List.of(p.getEventId())).get(p.getEventId()));
    }

    // ============================================================
    //  写（FLOW:F-JP-01.step2）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzJpProductBo bo) {
        requireEventExist(bo.getEventId());
        validatePrice(bo.getPriceCent());

        GzJpProduct add = new GzJpProduct();
        applyEditableFields(add, bo);
        add.setProductNo(generateProductNo(LocalDate.now()));
        // 新建即下架：上架是显式动作，避免半成品商品直接见客（UI:admin.product 批量上下架）
        add.setStatus(GzJpProductStatus.OFF_SHELF.getCode());
        // ★ @Version 字段必须在 insert 前配齐：留 null 会让内存 version 与库里的 DEFAULT 0 脱节，
        //   后续 updateById 因 version 为空而退化成无乐观锁更新（本项目 GZ-BEAN-039 踩过）。
        add.setVersion(0);
        if (baseMapper.insert(add) <= 0) {
            throw new ServiceException("商品新建失败");
        }
        log.info("[gz-jp-admin] INSERT product id={} productNo={} eventId={} name={} priceCent={} status=off_shelf",
            add.getId(), add.getProductNo(), add.getEventId(), add.getName(), add.getPriceCent());
        return add.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzJpProductBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("商品 ID 不能为空");
        }
        requireEventExist(bo.getEventId());
        validatePrice(bo.getPriceCent());

        GzJpProduct exist = requireExist(bo.getId());
        // productNo / status / version 系统管理：只覆盖 admin 可填字段
        applyEditableFields(exist, bo);
        if (baseMapper.updateById(exist) <= 0) {
            throw new ServiceException("商品信息已被其他人修改，请刷新后重试");
        }
        log.info("[gz-jp-admin] UPDATE product id={} productNo={} eventId={} name={} priceCent={}",
            exist.getId(), exist.getProductNo(), exist.getEventId(), exist.getName(), exist.getPriceCent());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(GzJpProductStatusBo bo) {
        if (ObjectUtil.isEmpty(bo.getIds())) {
            throw new ServiceException("请至少选择一个商品");
        }
        if (bo.getIds().size() > BATCH_STATUS_MAX) {
            throw new ServiceException("单次最多操作 " + BATCH_STATUS_MAX + " 个商品，请分批处理");
        }
        if (!GzJpProductStatus.isValid(bo.getStatus())) {
            throw new ServiceException("非法的商品状态：" + bo.getStatus());
        }
        String target = bo.getStatus();

        List<GzJpProduct> products = baseMapper.selectByIds(bo.getIds());
        if (products.isEmpty()) {
            throw new ServiceException("所选商品不存在或已删除");
        }
        int changed = 0;
        for (GzJpProduct p : products) {
            if (target.equals(p.getStatus())) {
                // 已是目标态：跳过而不报错（批量场景里混入同态项属常态）
                continue;
            }
            p.setStatus(target);
            if (baseMapper.updateById(p) <= 0) {
                throw new ServiceException("商品「" + p.getName() + "」已被其他人修改，请刷新后重试");
            }
            changed++;
        }
        log.info("[gz-jp-admin] CHANGE STATUS product ids={} target={} changed={}", bo.getIds(), target, changed);
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ObjectUtil.isEmpty(ids)) {
            return false;
        }
        List<GzJpProduct> products = baseMapper.selectByIds(ids);
        for (GzJpProduct p : products) {
            if (GzJpProductStatus.of(p.getStatus()) == GzJpProductStatus.ON_SHELF) {
                throw new ServiceException("商品「" + p.getName() + "」仍在上架中，请先下架再删除");
            }
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        log.info("[gz-jp-admin] DELETE product ids={} result={}", ids, ok);
        return ok;
    }

    // ============================================================
    //  mp（GZ-JP-103，FLOW:F-JP-02.step1）
    // ============================================================

    @Override
    public TableDataInfo<GzJpProductMpVO> selectMpPage(Long eventId, PageQuery pageQuery) {
        if (ObjectUtil.isNull(eventId)) {
            throw new ServiceException("请选择场");
        }
        // ── 可见性第 1 层：场必须仍可下单（读时惰性判定 end_time，判定逻辑只在场侧一处实现）
        if (!eventService.isBookable(eventId)) {
            log.info("[gz-jp-mp] 场不可下单，商品列表返回空页 eventId={}", eventId);
            // ★ 必须 build(List.of()) 不能 build()：无参版本不 setRows，mp 会拿到 "rows": null 直接崩
            return TableDataInfo.build(List.of());
        }
        // ── 可见性第 2 层：商品 on_shelf（下沉 SQL，保证分页 total 与「客人真看得到的条数」一致）
        LambdaQueryWrapper<GzJpProduct> lqw = Wrappers.<GzJpProduct>lambdaQuery()
            .eq(GzJpProduct::getEventId, eventId)
            .eq(GzJpProduct::getStatus, GzJpProductStatus.ON_SHELF.getCode())
            .orderByAsc(GzJpProduct::getSortNo)
            .orderByAsc(GzJpProduct::getId);

        Page<GzJpProduct> page = baseMapper.selectPage(buildMpPage(pageQuery), lqw);
        List<GzJpProduct> records = page.getRecords();

        GzJpEventOptionVO event = eventService.selectOptionMap(List.of(eventId)).get(eventId);
        // 同一批里重复引用的图片只换一次签名（一番赏 A/B/C 赏共用同一张盒图是常态）
        Map<Long, String> urlCache = new HashMap<>();

        Page<GzJpProductMpVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records.stream().map(p -> toMpVO(p, event, urlCache, false)).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzJpProductMpVO selectMpDetail(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzJpProduct p = baseMapper.selectById(id);
        // 可见性两层同口径：商品 on_shelf + 场仍可下单（任一不满足都当作「不存在」，不泄漏下架商品信息）
        if (p == null || GzJpProductStatus.of(p.getStatus()) != GzJpProductStatus.ON_SHELF) {
            return null;
        }
        if (!eventService.isBookable(p.getEventId())) {
            log.info("[gz-jp-mp] 商品所属场不可下单，详情不下发 productId={} eventId={}", id, p.getEventId());
            return null;
        }
        GzJpEventOptionVO event = eventService.selectOptionMap(List.of(p.getEventId())).get(p.getEventId());
        // 详情页要换主图 + 图集，主图常同时出现在图集里 —— 用同一个 cache 去重
        return toMpVO(p, event, new HashMap<>(), true);
    }

    // ============================================================
    //  internal
    // ============================================================

    /** 把 BO 里 admin 可填的字段写进实体（新建 / 编辑共用，保证两条路径字段集合永不漂移）。 */
    private void applyEditableFields(GzJpProduct target, GzJpProductBo bo) {
        target.setEventId(bo.getEventId());
        target.setName(StrUtil.trim(bo.getName()));
        target.setMainImageId(bo.getMainImageId());
        target.setGalleryImageIds(joinGallery(bo.getGalleryImageIds()));
        target.setPriceCent(bo.getPriceCent());
        target.setDeliveryDateText(StrUtil.trimToNull(bo.getDeliveryDateText()));
        target.setNoticeText(StrUtil.trimToNull(bo.getNoticeText()));
        target.setSortNo(ObjectUtil.defaultIfNull(bo.getSortNo(), 0));
        target.setRemark(bo.getRemark());
    }

    private GzJpProduct requireExist(Long id) {
        GzJpProduct p = id == null ? null : baseMapper.selectById(id);
        if (p == null) {
            throw new ServiceException("商品不存在：" + id);
        }
        return p;
    }

    /**
     * 校验所属场存在（<b>不</b>要求已开场 —— FLOW:F-JP-01 正常顺序是 建场 → 上架商品 → 开场）。
     */
    private void requireEventExist(Long eventId) {
        if (eventId == null) {
            throw new ServiceException("请选择所属场");
        }
        if (!eventService.selectOptionMap(List.of(eventId)).containsKey(eventId)) {
            throw new ServiceException("所属场不存在或已删除：" + eventId);
        }
    }

    private void validatePrice(Long priceCent) {
        if (priceCent == null || priceCent <= 0) {
            throw new ServiceException("售价必须大于 0");
        }
        if (priceCent > PRICE_CENT_MAX) {
            throw new ServiceException("售价超出上限（¥" + (PRICE_CENT_MAX / 100) + "），请确认是否多输了 0");
        }
    }

    /** 图集 List&lt;Long&gt; → 逗号分隔串（去空、去重、限 {@value #GALLERY_MAX} 张）。 */
    private String joinGallery(List<Long> ids) {
        if (ObjectUtil.isEmpty(ids)) {
            return null;
        }
        List<Long> clean = ids.stream().filter(ObjectUtil::isNotNull).distinct().toList();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.size() > GALLERY_MAX) {
            throw new ServiceException("图集最多 " + GALLERY_MAX + " 张");
        }
        return StrUtil.join(",", clean);
    }

    /** 逗号分隔串 → 图集 List&lt;Long&gt;（脏数据里的非数字段跳过并告警，不让整行渲染失败）。 */
    private List<Long> splitGallery(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = StrUtil.trim(part);
            if (StrUtil.isBlank(s)) {
                continue;
            }
            if (!StrUtil.isNumeric(s)) {
                log.warn("[gz-jp-admin] gallery_image_ids 含非数字片段，已跳过：{}", s);
                continue;
            }
            out.add(Long.parseLong(s));
        }
        return out;
    }

    /**
     * 商品实体 → admin VO。
     *
     * @param event 所属场轻量信息（可为 null —— 场被删时不让整行查询挂掉，字段留空即可）
     */
    private GzJpProductAdminVO toAdminVO(GzJpProduct p, GzJpEventOptionVO event) {
        GzJpProductAdminVO vo = new GzJpProductAdminVO();
        vo.setId(p.getId());
        vo.setProductNo(p.getProductNo());
        vo.setEventId(p.getEventId());
        vo.setName(p.getName());
        vo.setMainImageId(p.getMainImageId());
        vo.setGalleryImageIds(splitGallery(p.getGalleryImageIds()));
        vo.setPriceCent(p.getPriceCent());
        vo.setDeliveryDateText(p.getDeliveryDateText());
        vo.setNoticeText(p.getNoticeText());
        vo.setStatus(p.getStatus());
        vo.setSortNo(p.getSortNo());
        vo.setVersion(p.getVersion());
        vo.setCreateTime(p.getCreateTime());
        vo.setUpdateTime(p.getUpdateTime());
        vo.setRemark(p.getRemark());

        if (event != null) {
            vo.setEventNo(event.getEventNo());
            vo.setEventName(event.getName());
            vo.setEventStatus(event.getStatus());
        }
        // 客人真看得到 = 商品 on_shelf 且场生效状态 open（FLOW:F-JP-01.step2「未开场时仍不可见」）
        vo.setVisibleToCustomer(
            GzJpProductStatus.of(p.getStatus()) == GzJpProductStatus.ON_SHELF
                && event != null
                && GzJpEventStatus.OPEN.getCode().equals(event.getStatus()));
        return vo;
    }

    /**
     * mp 分页参数收口：pageSize 缺省 {@value #MP_PAGE_SIZE_DEFAULT}、封顶 {@value #MP_PAGE_SIZE_MAX}。
     *
     * <p>不复用 {@link PageQuery#build()} 的原因见 {@link #MP_PAGE_SIZE_DEFAULT} 注释。
     * 排序在 wrapper 里固定（sort_no, id），不接受调用方传 orderByColumn。</p>
     */
    private Page<GzJpProduct> buildMpPage(PageQuery pageQuery) {
        int pageNum = ObjectUtil.defaultIfNull(pageQuery == null ? null : pageQuery.getPageNum(), 1);
        int pageSize = ObjectUtil.defaultIfNull(
            pageQuery == null ? null : pageQuery.getPageSize(), MP_PAGE_SIZE_DEFAULT);
        if (pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize < 1) {
            pageSize = MP_PAGE_SIZE_DEFAULT;
        }
        if (pageSize > MP_PAGE_SIZE_MAX) {
            pageSize = MP_PAGE_SIZE_MAX;
        }
        return new Page<>(pageNum, pageSize);
    }

    /**
     * 商品实体 → mp VO（GZ-JP-103）。
     *
     * @param p          商品实体（调用方已确认可见性两层都过）
     * @param event      所属场轻量信息（可为 null —— 场被删时不让整页挂掉，场字段留空即可）
     * @param urlCache   本次请求内的 fileId → URL 缓存（同图只换一次签名）
     * @param withGallery 是否解析图集：<b>详情 true / 列表 false</b>。
     *                    列表卡片只用主图（UI:mp.event_detail 商品卡 = 主图+名+价+到货），
     *                    一页 20 条 × 最多 9 张图集 = 180 次预签名查询，纯浪费
     */
    private GzJpProductMpVO toMpVO(GzJpProduct p, GzJpEventOptionVO event,
                                   Map<Long, String> urlCache, boolean withGallery) {
        GzJpProductMpVO vo = new GzJpProductMpVO();
        vo.setId(p.getId());
        vo.setProductNo(p.getProductNo());
        vo.setEventId(p.getEventId());
        vo.setName(p.getName());
        vo.setMainImageUrl(resolveImageUrl(p.getMainImageId(), urlCache));
        vo.setGalleryImageUrls(withGallery
            ? splitGallery(p.getGalleryImageIds()).stream().map(fid -> resolveImageUrl(fid, urlCache)).toList()
            : List.of());
        vo.setPriceCent(p.getPriceCent());
        vo.setDeliveryDateText(p.getDeliveryDateText());
        vo.setNoticeText(p.getNoticeText());
        // 走到这里的商品必然 on_shelf（查询条件 / selectMpDetail 已守），显式回填便于回归包断言
        vo.setStatus(GzJpProductStatus.ON_SHELF.getCode());
        if (event != null) {
            vo.setEventNo(event.getEventNo());
            vo.setEventName(event.getName());
        }
        return vo;
    }

    /**
     * file id → 可渲染签名 URL。NULL / 文件已删（抛异常）/ 空串 → 占位图。
     *
     * <p>绝不返回 null：mp {@code <image>} 拿到 null 会渲染成裂图，占位图至少布局不塌。</p>
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
            log.warn("[gz-jp-mp] 商品图片解析失败 fileId={}，回退占位图：{}", fileId, ex.getMessage());
            url = PLACEHOLDER_IMAGE_URL;
        }
        if (StrUtil.isBlank(url)) {
            url = PLACEHOLDER_IMAGE_URL;
        }
        urlCache.put(fileId, url);
        return url;
    }

    /**
     * 生成 product_no：{@code JPP-yyyyMMdd-000001}，当日序号自增。
     *
     * <p>取最大值时<b>含软删行</b>（见 {@link GzJpProductMapper#selectMaxProductNoIncludeDeleted}），
     * 否则「当日上架 → 软删 → 当日再上架」会重用已占号撞 uk_product_no。</p>
     */
    private String generateProductNo(LocalDate day) {
        String prefix = PRODUCT_NO_PREFIX + day.format(PRODUCT_NO_DATE_FMT) + "-";
        String max = baseMapper.selectMaxProductNoIncludeDeleted(prefix);
        int next = 1;
        if (StrUtil.isNotBlank(max) && max.length() == PRODUCT_NO_TOTAL_LEN) {
            String seq = max.substring(prefix.length());
            if (StrUtil.isNumeric(seq)) {
                next = Integer.parseInt(seq) + 1;
            } else {
                log.warn("[gz-jp-admin] product_no 序号段非数字，从 1 重新起算：max={}", max);
            }
        }
        if (next > PRODUCT_NO_SEQ_MAX) {
            throw new ServiceException("当日上架商品数量已达上限（" + PRODUCT_NO_SEQ_MAX + "），请次日再上");
        }
        return prefix + StrUtil.padPre(String.valueOf(next), PRODUCT_NO_SEQ_LEN, '0');
    }
}
