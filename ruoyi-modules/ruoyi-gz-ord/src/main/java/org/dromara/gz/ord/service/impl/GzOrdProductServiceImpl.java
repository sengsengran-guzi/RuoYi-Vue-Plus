package org.dromara.gz.ord.service.impl;

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
import org.dromara.gz.ord.domain.bo.GzOrdProductBo;
import org.dromara.gz.ord.domain.bo.GzOrdProductQueryBo;
import org.dromara.gz.ord.domain.bo.GzOrdSkuBo;
import org.dromara.gz.ord.domain.dto.applet.OrdProductListReq;
import org.dromara.gz.ord.domain.dto.applet.ValidatePurchaseReq;
import org.dromara.gz.ord.domain.entity.GzOrdProduct;
import org.dromara.gz.ord.domain.entity.GzOrdSku;
import org.dromara.gz.ord.domain.vo.GzOrdProductAdminVO;
import org.dromara.gz.ord.domain.vo.GzOrdSkuVO;
import org.dromara.gz.ord.domain.vo.applet.OrdProductCardVO;
import org.dromara.gz.ord.domain.vo.applet.OrdProductDetailVO;
import org.dromara.gz.ord.domain.vo.applet.OrdProductMpListVO;
import org.dromara.gz.ord.domain.vo.applet.OrdSkuMpVO;
import org.dromara.gz.ord.domain.vo.applet.ValidatePurchaseVO;
import org.dromara.gz.ord.enums.OrdErrCodeEnum;
import org.dromara.gz.ord.enums.OrdProductSortEnum;
import org.dromara.gz.ord.enums.OrdProductStatusEnum;
import org.dromara.gz.ord.exception.GzOrdErrorCode;
import org.dromara.gz.ord.mapper.GzOrdProductMapper;
import org.dromara.gz.ord.mapper.GzOrdSkuMapper;
import org.dromara.gz.ord.service.IGzOrdProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预购商品服务实现（GZ-ORD-101 admin CRUD + 截止下架）。
 *
 * <p>字段口径权威：doc/11 §6.1 / §6.2。关键决策：</p>
 * <ul>
 *   <li>商品 + SKU 同事务增改（决策 D1）；单规格商品也建一条 SKU（spec_name='标准款'）</li>
 *   <li>product_no / sku_no「查当日最大 + 1」生成（同 article_no 模式，DB 自增防重启丢号）</li>
 *   <li>新增 status 固定 off_shelf；状态流转走 {@link #changeStatus}（auto_off 仅 cron，决策 D5）</li>
 *   <li>SKU diff（编辑）：新增（id 空）/ 更新（id 存在）/ 删除（列表缺失的既有 SKU）；
 *       被订单引用的 SKU 改 enabled=0 软停用而非物理删（决策 D4 / R3）</li>
 *   <li>软删 del_flag=2（@TableLogic）；被订单引用的商品拒删（决策 D4）</li>
 *   <li>到货日 text / exact 二选一（F6.1 业务层校验，DB 不约束）</li>
 *   <li>description_html 落库前白名单清洗（R5）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzOrdProductServiceImpl implements IGzOrdProductService {

    private static final DateTimeFormatter NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DELIVERY_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** PRD-yyyyMMdd-6位序号 = 4 + 8 + 1 + 6 = 19 */
    private static final int PRODUCT_NO_TOTAL_LEN = 19;
    /** SKU-yyyyMMdd-6位序号 = 4 + 8 + 1 + 6 = 19 */
    private static final int SKU_NO_TOTAL_LEN = 19;
    private static final int NO_SEQ_LEN = 6;
    /** mp 列表 ipTags IN 子句最多个数（强约束 R5，超出截断 + warn） */
    private static final int MAX_IP_TAGS = 10;
    /** main_image_id 为空 / 解析失败时的 C 系统占位图（待甲方素材替换，R4 / CLAUDE.md §7.5） */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    private final GzOrdProductMapper baseMapper;
    private final GzOrdSkuMapper skuMapper;
    private final org.dromara.gz.ord.service.internal.OrdHtmlSanitizer htmlSanitizer;
    private final IGzFileService fileService;

    // ============================================================
    //  AC 2 — admin 列表 / 详情
    // ============================================================

    @Override
    public TableDataInfo<GzOrdProductAdminVO> selectAdminPage(GzOrdProductQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzOrdProduct> lqw = Wrappers.<GzOrdProduct>lambdaQuery()
            .like(StrUtil.isNotBlank(query.getName()), GzOrdProduct::getName, query.getName())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzOrdProduct::getStatus, query.getStatus())
            .eq(StrUtil.isNotBlank(query.getIpTag()), GzOrdProduct::getIpTag, query.getIpTag())
            // 列表不投影 description_html（MEDIUMTEXT 大字段，省内存/带宽）
            .select(GzOrdProduct.class, f -> !"descriptionHtml".equals(f.getProperty()))
            .orderByDesc(GzOrdProduct::getSortNo)
            .orderByDesc(GzOrdProduct::getCreateTime);
        Page<GzOrdProduct> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzOrdProductAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        // 列表不含 SKU 列表（详情才查；列表如需 SKU 摘要可后续加，当前省 N+1）
        voPage.setRecords(page.getRecords().stream().map(e -> toAdminVO(e, false)).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzOrdProductAdminVO selectAdminById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzOrdProduct e = baseMapper.selectById(id);
        if (e == null) {
            return null;
        }
        GzOrdProductAdminVO vo = toAdminVO(e, true);
        // 详情含 SKU 列表（sort_no 升序）
        vo.setSkuList(listSkuVO(id));
        return vo;
    }

    // ============================================================
    //  AC 2 — 新增（product + SKU 同事务）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzOrdProductBo bo) {
        validateDeliveryDate(bo);
        validateSkuList(bo.getSkuList());

        GzOrdProduct add = new GzOrdProduct();
        copyEditableProductFields(bo, add);
        LocalDate today = LocalDate.now();
        add.setProductNo(generateProductNo(today));
        // 新增固定 off_shelf（决策 D5：上架走 changeStatus）
        add.setStatus(OrdProductStatusEnum.OFF_SHELF.getCode());
        add.setSalesCount(0L);
        add.setVersion(0);
        add.setDescriptionHtml(htmlSanitizer.sanitize(bo.getDescriptionHtml()));
        if (baseMapper.insert(add) <= 0) {
            throw new ServiceException("商品新建失败");
        }

        // 子 SKU 同事务插入（sku_no 系统生成；stock_remain 初始 = stock_total）
        insertSkus(add.getId(), bo.getSkuList(), today);
        log.info("[gz-ord-product] INSERT id={} productNo={} name={} skuCount={} status=off_shelf",
            add.getId(), add.getProductNo(), add.getName(), bo.getSkuList().size());
        return add.getId();
    }

    // ============================================================
    //  AC 2 — 更新（product + SKU diff 同事务）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzOrdProductBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("商品 ID 不能为空");
        }
        GzOrdProduct existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException(GzOrdErrorCode.PRODUCT_NOT_FOUND_MSG, GzOrdErrorCode.PRODUCT_NOT_FOUND);
        }
        validateDeliveryDate(bo);
        validateSkuList(bo.getSkuList());

        GzOrdProduct update = new GzOrdProduct();
        update.setId(bo.getId());
        copyEditableProductFields(bo, update);
        // product_no / status / salesCount / version 不在编辑路径改（status 走 changeStatus）
        update.setDescriptionHtml(htmlSanitizer.sanitize(bo.getDescriptionHtml()));
        boolean ok = baseMapper.updateById(update) > 0;

        // SKU diff（决策 D4 / R3：被订单引用的删请求 → enabled=0 软停用，未引用 → 物理删）
        diffSkus(bo.getId(), bo.getSkuList(), LocalDate.now());
        if (ok) {
            log.info("[gz-ord-product] UPDATE id={} name={} skuCount={}", bo.getId(), bo.getName(), bo.getSkuList().size());
        }
        return ok;
    }

    // ============================================================
    //  AC 2 — 上下架（on_shelf ↔ off_shelf；auto_off 仅 cron）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStatus(Long id, String targetStatus) {
        if (id == null) {
            throw new ServiceException("商品 ID 不能为空");
        }
        // 决策 D5：admin 仅可切 on_shelf / off_shelf；auto_off 仅 cron 写
        if (!OrdProductStatusEnum.isManualTarget(targetStatus)) {
            throw new ServiceException(GzOrdErrorCode.INVALID_STATUS_MSG, GzOrdErrorCode.INVALID_STATUS);
        }
        GzOrdProduct e = baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException(GzOrdErrorCode.PRODUCT_NOT_FOUND_MSG, GzOrdErrorCode.PRODUCT_NOT_FOUND);
        }
        GzOrdProduct update = new GzOrdProduct();
        update.setId(id);
        update.setStatus(targetStatus);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-ord-product] CHANGE-STATUS id={} {} → {}", id, e.getStatus(), targetStatus);
        }
        return ok;
    }

    // ============================================================
    //  AC 2 — 软删（被订单引用拒删）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        // 决策 D4：被订单引用的商品拒删（gz_ord_order 由 ORD-104 建，当前恒未引用 — hook 占位）
        for (Long id : ids) {
            if (isReferencedByOrder(id)) {
                throw new ServiceException(GzOrdErrorCode.PRODUCT_REFERENCED_MSG, GzOrdErrorCode.PRODUCT_REFERENCED);
            }
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        if (ok) {
            // 关联 SKU 一并软删（@TableLogic 转 del_flag=2）
            LambdaQueryWrapper<GzOrdSku> lqw = Wrappers.<GzOrdSku>lambdaQuery().in(GzOrdSku::getProductId, ids);
            skuMapper.delete(lqw);
            log.info("[gz-ord-product] LOGIC-DELETE ids={} (含关联 SKU)", ids);
        }
        return ok;
    }

    // ============================================================
    //  AC 5 — 截止下架（SnailJob 委托的可单测 service）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int autoOffExpiredProducts() {
        int affected = baseMapper.autoOffExpiredProducts();
        if (affected > 0) {
            log.info("[gz-ord-cron] auto-off expired products count={}", affected);
        }
        return affected;
    }

    // ============================================================
    //  GZ-ORD-102 — mp 端 C 端浏览（列表 + IP 标签）
    // ============================================================

    @Override
    public OrdProductMpListVO listForMp(OrdProductListReq req) {
        OrdProductSortEnum sort = OrdProductSortEnum.ofCodeOrDefault(req.getSortBy());
        List<String> ipTags = parseIpTags(req.getIpTags());

        LocalDateTime now = LocalDateTime.now();
        // 强约束：on_shelf + 未截止 + 软删过滤（del_flag 由 @TableLogic 自动 append）
        LambdaQueryWrapper<GzOrdProduct> lqw = Wrappers.<GzOrdProduct>lambdaQuery()
            .eq(GzOrdProduct::getStatus, OrdProductStatusEnum.ON_SHELF.getCode())
            .gt(GzOrdProduct::getDeadlineTime, now)
            .in(!ipTags.isEmpty(), GzOrdProduct::getIpTag, ipTags)
            // 列表不投影 description_html（MEDIUMTEXT 大字段）
            .select(GzOrdProduct.class, f -> !"descriptionHtml".equals(f.getProperty()))
            // 白名单 ORDER BY（列名来自闭区间枚举，无注入风险，决策 D2）
            .last("ORDER BY " + sort.getOrderByClause());

        int pageNum = req.getPageNum() == null ? 1 : req.getPageNum();
        int pageSize = req.getPageSize() == null ? 20 : req.getPageSize();
        Page<GzOrdProduct> page = baseMapper.selectPage(new Page<>(pageNum, pageSize), lqw);

        List<GzOrdProduct> records = page.getRecords();
        Map<Long, Long> minPriceMap = batchMinPrice(records);

        List<OrdProductCardVO> rows = records.stream()
            .map(e -> toCardVO(e, minPriceMap.get(e.getId())))
            .toList();

        OrdProductMpListVO result = new OrdProductMpListVO();
        result.setRows(rows);
        result.setTotal(page.getTotal());
        result.setServerNow(now);
        return result;
    }

    @Override
    public List<String> listOnSaleIpTags() {
        // DISTINCT ip_tag WHERE on_shelf + 未截止 + ip_tag 非空（同 AC1 强约束）
        List<GzOrdProduct> records = baseMapper.selectList(
            Wrappers.<GzOrdProduct>lambdaQuery()
                .select(GzOrdProduct::getIpTag)
                .eq(GzOrdProduct::getStatus, OrdProductStatusEnum.ON_SHELF.getCode())
                .gt(GzOrdProduct::getDeadlineTime, LocalDateTime.now())
                .isNotNull(GzOrdProduct::getIpTag)
                .ne(GzOrdProduct::getIpTag, ""));
        return records.stream()
            .map(GzOrdProduct::getIpTag)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }

    // ============================================================
    //  GZ-ORD-103 — mp 端 商品详情 + 下单前校验
    // ============================================================

    @Override
    public OrdProductDetailVO getDetailForMp(Long id) {
        if (id == null) {
            return null;
        }
        // 详情不做在售强约束：下架商品旧链接仍可打开看详情，由下单校验（validatePurchase）拦截（决策 D1）
        GzOrdProduct e = baseMapper.selectById(id);
        if (e == null) {
            return null;
        }

        OrdProductDetailVO vo = new OrdProductDetailVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setMainImageUrl(resolveImageUrl(e.getMainImageId()));
        vo.setGalleryImageUrls(resolveGalleryUrls(e.getGalleryImageIds()));
        vo.setDescriptionHtml(e.getDescriptionHtml());
        vo.setIpTag(e.getIpTag());
        vo.setDeadlineTime(e.getDeadlineTime());
        vo.setDeliveryText(formatDeliveryText(e));
        vo.setStatus(e.getStatus());
        vo.setSalesCount(e.getSalesCount());
        vo.setSkus(listSkuMpVO(id));
        vo.setServerNow(LocalDateTime.now());
        return vo;
    }

    @Override
    public ValidatePurchaseVO validatePurchase(ValidatePurchaseReq req) {
        // ① 商品存在 + 在售 + 未截止（下架 / 截止已过统一 PRODUCT_OFF，doc/10 §7.E1 / E3，强约束 #3）
        GzOrdProduct product = baseMapper.selectById(req.getProductId());
        if (product == null) {
            return ValidatePurchaseVO.of(OrdErrCodeEnum.PRODUCT_NOT_FOUND);
        }
        boolean onSale = OrdProductStatusEnum.ON_SHELF.getCode().equals(product.getStatus())
            && product.getDeadlineTime() != null
            && product.getDeadlineTime().isAfter(LocalDateTime.now());
        if (!onSale) {
            return ValidatePurchaseVO.of(OrdErrCodeEnum.PRODUCT_OFF);
        }

        // ② SKU 存在 + 属于该商品 + 可售 + 库存足（stock_remain IS NULL = 无限，doc/10 §7.E2，强约束 #3）
        GzOrdSku sku = skuMapper.selectById(req.getSkuId());
        if (sku == null || !req.getProductId().equals(sku.getProductId())) {
            return ValidatePurchaseVO.of(OrdErrCodeEnum.PRODUCT_NOT_FOUND);
        }
        boolean skuOk = Integer.valueOf(1).equals(sku.getEnabled())
            && (sku.getStockRemain() == null || sku.getStockRemain() >= req.getQuantity());
        if (!skuOk) {
            return ValidatePurchaseVO.of(OrdErrCodeEnum.SKU_OUT_OF_STOCK);
        }
        return ValidatePurchaseVO.ok();
    }

    // ============================================================
    //  内部辅助
    // ============================================================

    /**
     * 图集 gallery_image_ids（逗号分隔 file_id）→ 可访问签名 URL 列表（AC1）。
     * 空 / 无图 → 空列表（轮播只用 mainImageUrl）；单个解析失败回退占位图（R4，不整体失败）。
     */
    private List<String> resolveGalleryUrls(String galleryImageIds) {
        if (StrUtil.isBlank(galleryImageIds)) {
            return Collections.emptyList();
        }
        List<String> urls = new ArrayList<>();
        for (String idStr : galleryImageIds.split(",")) {
            String trimmed = idStr.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }
            try {
                urls.add(resolveImageUrl(Long.valueOf(trimmed)));
            } catch (NumberFormatException ex) {
                log.warn("[gz-ord-mp] 图集 id 非法，跳过：{}", trimmed);
            }
        }
        return urls;
    }

    /**
     * 查商品 SKU 列表 → mp VO（sort_no 升序；含停用 SKU 前端置灰，AC1）。
     */
    private List<OrdSkuMpVO> listSkuMpVO(Long productId) {
        List<GzOrdSku> skus = skuMapper.selectList(
            Wrappers.<GzOrdSku>lambdaQuery()
                .eq(GzOrdSku::getProductId, productId)
                .orderByAsc(GzOrdSku::getSortNo)
                .orderByAsc(GzOrdSku::getId));
        return skus.stream().map(this::toSkuMpVO).toList();
    }

    /**
     * SKU entity → mp 详情 VO。{@code stockRemain} 原样（NULL = 无限，前端不显数字）。
     */
    private OrdSkuMpVO toSkuMpVO(GzOrdSku e) {
        OrdSkuMpVO vo = new OrdSkuMpVO();
        vo.setId(e.getId());
        vo.setSpecName(e.getSpecName());
        vo.setPriceCent(e.getPriceCent());
        vo.setStockRemain(e.getStockRemain());
        vo.setEnabled(e.getEnabled());
        return vo;
    }

    /**
     * 解析 ipTags 逗号分隔串 → 去重非空列表，最多取前 {@value #MAX_IP_TAGS} 个（强约束 R5，超出截断 + warn）。
     */
    private List<String> parseIpTags(String ipTagsRaw) {
        if (StrUtil.isBlank(ipTagsRaw)) {
            return Collections.emptyList();
        }
        List<String> all = java.util.Arrays.stream(ipTagsRaw.split(","))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        if (all.size() > MAX_IP_TAGS) {
            log.warn("[gz-ord-mp] ipTags 超过 {} 个（实际 {}），截断取前 {} 个", MAX_IP_TAGS, all.size(), MAX_IP_TAGS);
            return all.subList(0, MAX_IP_TAGS);
        }
        return all;
    }

    /**
     * 批量取各商品起始价（enabled SKU MIN price_cent，强约束 #4），一次 GROUP BY 查询避免 N+1。
     * 无 enabled SKU 的商品不在 map 中（卡片 startPriceCent → null）。
     */
    private Map<Long, Long> batchMinPrice(List<GzOrdProduct> products) {
        if (products.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = products.stream().map(GzOrdProduct::getId).toList();
        List<Map<String, Object>> rows = skuMapper.selectMinPriceByProductIds(ids);
        Map<Long, Long> result = new HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object pid = row.get("productId");
            Object minPrice = row.get("minPrice");
            if (pid != null && minPrice != null) {
                result.put(((Number) pid).longValue(), ((Number) minPrice).longValue());
            }
        }
        return result;
    }

    /**
     * 商品 entity → mp 卡片 VO。主图解析签名 URL（NULL / 解析失败 → 占位图，R4）；
     * 到货日 exact 优先 yyyy-MM-dd，否则 text，都空则空串。
     */
    private OrdProductCardVO toCardVO(GzOrdProduct e, Long startPriceCent) {
        OrdProductCardVO vo = new OrdProductCardVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setMainImageUrl(resolveImageUrl(e.getMainImageId()));
        vo.setStartPriceCent(startPriceCent);
        vo.setIpTag(e.getIpTag());
        vo.setDeadlineTime(e.getDeadlineTime());
        vo.setDeliveryText(formatDeliveryText(e));
        vo.setSalesCount(e.getSalesCount());
        return vo;
    }

    /**
     * main_image_id → 可访问签名 URL。NULL / 文件不存在（getPresignedUrl 抛 ServiceException）→ 占位图（R4）。
     */
    private String resolveImageUrl(Long mainImageId) {
        if (mainImageId == null) {
            return PLACEHOLDER_IMAGE_URL;
        }
        try {
            return fileService.getPresignedUrl(mainImageId).getUrl();
        } catch (Exception ex) {
            log.warn("[gz-ord-mp] 主图解析失败 fileId={}，回退占位图：{}", mainImageId, ex.getMessage());
            return PLACEHOLDER_IMAGE_URL;
        }
    }

    /**
     * 到货日文案：delivery_date_exact 优先格式化 yyyy-MM-dd，否则取 delivery_date_text，都空则空串（AC2）。
     */
    private String formatDeliveryText(GzOrdProduct e) {
        if (e.getDeliveryDateExact() != null) {
            return e.getDeliveryDateExact().format(DELIVERY_DATE_FMT);
        }
        return StrUtil.isNotBlank(e.getDeliveryDateText()) ? e.getDeliveryDateText() : "";
    }

    /**
     * 商品引用检查 hook（决策 D4 / R3）。gz_ord_order 表由 ORD-104 建；本 ticket 恒返回 false。
     * ORD-104 实施时改为 {@code SELECT EXISTS(SELECT 1 FROM gz_ord_order WHERE product_id=?)}。
     */
    private boolean isReferencedByOrder(Long productId) {
        return false;
    }

    /**
     * 到货日二选一校验（F6.1）：text 与 exact 不能同时为空，也不能同时非空。
     */
    private void validateDeliveryDate(GzOrdProductBo bo) {
        boolean hasText = StrUtil.isNotBlank(bo.getDeliveryDateText());
        boolean hasExact = bo.getDeliveryDateExact() != null;
        if (hasText == hasExact) {
            // 都空 或 都填 → 非法
            throw new ServiceException(GzOrdErrorCode.DELIVERY_DATE_INVALID_MSG, GzOrdErrorCode.DELIVERY_DATE_INVALID);
        }
    }

    /**
     * SKU 列表非空校验（决策 D1：商品至少一个 SKU，单规格也建一条）。
     */
    private void validateSkuList(List<GzOrdSkuBo> skuList) {
        if (skuList == null || skuList.isEmpty()) {
            throw new ServiceException(GzOrdErrorCode.SKU_LIST_EMPTY_MSG, GzOrdErrorCode.SKU_LIST_EMPTY);
        }
    }

    /**
     * BO → 商品 entity 可编辑字段（手写，避免 MapstructUtils 单测 mockStatic 报错 — 同 gz-news/bean）。
     */
    private void copyEditableProductFields(GzOrdProductBo bo, GzOrdProduct e) {
        e.setName(bo.getName());
        e.setMainImageId(bo.getMainImageId());
        e.setGalleryImageIds(bo.getGalleryImageIds());
        e.setIpTag(bo.getIpTag());
        e.setDeadlineTime(bo.getDeadlineTime());
        e.setDeliveryDateText(bo.getDeliveryDateText());
        e.setDeliveryDateExact(bo.getDeliveryDateExact());
        e.setSortNo(bo.getSortNo() == null ? 0 : bo.getSortNo());
        e.setRemark(bo.getRemark());
    }

    /**
     * 批量插入子 SKU（新增商品 / 编辑新增 SKU 共用）。stock_remain 初始 = stock_total；sku_no 系统生成。
     */
    private void insertSkus(Long productId, List<GzOrdSkuBo> skuList, LocalDate today) {
        for (GzOrdSkuBo skuBo : skuList) {
            GzOrdSku sku = new GzOrdSku();
            sku.setProductId(productId);
            sku.setSkuNo(generateSkuNo(today));
            copyEditableSkuFields(skuBo, sku);
            // 新建剩余 = 总库存（NULL = 无限）
            sku.setStockRemain(skuBo.getStockTotal());
            sku.setVersion(0);
            skuMapper.insert(sku);
        }
    }

    /**
     * 编辑路径 SKU diff（决策 D4 / R3）：
     * <ul>
     *   <li>BO 含 id 且 DB 存在 → 更新（spec/price/stock/enabled/sort）</li>
     *   <li>BO id 空 → 新增（sku_no 系统生成，stock_remain = stock_total）</li>
     *   <li>DB 有但 BO 列表缺失 → 删除请求：被订单引用 → enabled=0 软停用；未引用 → 物理删</li>
     * </ul>
     */
    private void diffSkus(Long productId, List<GzOrdSkuBo> boSkus, LocalDate today) {
        List<GzOrdSku> dbSkus = skuMapper.selectList(
            Wrappers.<GzOrdSku>lambdaQuery().eq(GzOrdSku::getProductId, productId));
        Set<Long> dbIds = dbSkus.stream().map(GzOrdSku::getId).collect(Collectors.toSet());
        Set<Long> boKeepIds = new HashSet<>();

        List<GzOrdSkuBo> toInsert = new ArrayList<>();
        for (GzOrdSkuBo boSku : boSkus) {
            if (boSku.getId() != null && dbIds.contains(boSku.getId())) {
                // 更新既有 SKU（spec/price/stock_total/enabled/sort；stock_remain 不在编辑覆盖，扣减专管）
                boKeepIds.add(boSku.getId());
                GzOrdSku update = new GzOrdSku();
                update.setId(boSku.getId());
                copyEditableSkuFields(boSku, update);
                skuMapper.updateById(update);
            } else {
                // 新增 SKU（id 空 或 id 不属于本商品）
                toInsert.add(boSku);
            }
        }
        if (!toInsert.isEmpty()) {
            insertSkus(productId, toInsert, today);
        }

        // DB 有但 BO 不保留 → 删除请求
        for (GzOrdSku dbSku : dbSkus) {
            if (!boKeepIds.contains(dbSku.getId())) {
                if (isSkuReferencedByOrder(dbSku.getId())) {
                    // 被订单引用 → 软停用（决策 D4 / R3：保留 snapshot 语义）
                    GzOrdSku disable = new GzOrdSku();
                    disable.setId(dbSku.getId());
                    disable.setEnabled(0);
                    skuMapper.updateById(disable);
                    log.info("[gz-ord-sku] DISABLE (referenced) skuId={}", dbSku.getId());
                } else {
                    // 未引用 → 物理删（@TableLogic 软删 del_flag=2）
                    skuMapper.deleteById(dbSku.getId());
                    log.info("[gz-ord-sku] DELETE skuId={}", dbSku.getId());
                }
            }
        }
    }

    /**
     * SKU 引用检查 hook（gz_ord_order 由 ORD-104 建；本 ticket 恒 false）。
     */
    private boolean isSkuReferencedByOrder(Long skuId) {
        return false;
    }

    /**
     * BO → SKU entity 可编辑字段。enabled 默认 1；sortNo 默认 0。
     */
    private void copyEditableSkuFields(GzOrdSkuBo bo, GzOrdSku e) {
        e.setSpecName(bo.getSpecName());
        e.setPriceCent(bo.getPriceCent());
        e.setStockTotal(bo.getStockTotal());
        e.setEnabled(bo.getEnabled() == null ? 1 : bo.getEnabled());
        e.setSortNo(bo.getSortNo() == null ? 0 : bo.getSortNo());
    }

    /**
     * 生成 product_no = PRD-yyyyMMdd-6位序号（「查当日最大 + 1」，同 article_no 模式）。
     */
    private String generateProductNo(LocalDate date) {
        String prefix = "PRD-" + date.format(NO_DATE_FMT) + "-";
        LambdaQueryWrapper<GzOrdProduct> wrapper = Wrappers.<GzOrdProduct>lambdaQuery()
            .likeRight(GzOrdProduct::getProductNo, prefix)
            .orderByDesc(GzOrdProduct::getProductNo)
            .last("LIMIT 1");
        GzOrdProduct last = baseMapper.selectOne(wrapper);
        long nextSeq = 1L;
        if (last != null && last.getProductNo() != null && last.getProductNo().length() == PRODUCT_NO_TOTAL_LEN) {
            try {
                nextSeq = Long.parseLong(last.getProductNo().substring(prefix.length())) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常退回 1
            }
        }
        return prefix + String.format("%0" + NO_SEQ_LEN + "d", nextSeq);
    }

    /**
     * 生成 sku_no = SKU-yyyyMMdd-6位序号（「查当日最大 + 1」）。
     */
    private String generateSkuNo(LocalDate date) {
        String prefix = "SKU-" + date.format(NO_DATE_FMT) + "-";
        LambdaQueryWrapper<GzOrdSku> wrapper = Wrappers.<GzOrdSku>lambdaQuery()
            .likeRight(GzOrdSku::getSkuNo, prefix)
            .orderByDesc(GzOrdSku::getSkuNo)
            .last("LIMIT 1");
        GzOrdSku last = skuMapper.selectOne(wrapper);
        long nextSeq = 1L;
        if (last != null && last.getSkuNo() != null && last.getSkuNo().length() == SKU_NO_TOTAL_LEN) {
            try {
                nextSeq = Long.parseLong(last.getSkuNo().substring(prefix.length())) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常退回 1
            }
        }
        return prefix + String.format("%0" + NO_SEQ_LEN + "d", nextSeq);
    }

    /**
     * 查商品 SKU 列表 → VO（sort_no 升序）。
     */
    private List<GzOrdSkuVO> listSkuVO(Long productId) {
        List<GzOrdSku> skus = skuMapper.selectList(
            Wrappers.<GzOrdSku>lambdaQuery()
                .eq(GzOrdSku::getProductId, productId)
                .orderByAsc(GzOrdSku::getSortNo)
                .orderByAsc(GzOrdSku::getId));
        return skus.stream().map(this::toSkuVO).toList();
    }

    /**
     * 商品 entity → admin VO。{@code withDescription} 控制是否投影 description_html（列表 false / 详情 true）。
     */
    private GzOrdProductAdminVO toAdminVO(GzOrdProduct e, boolean withDescription) {
        GzOrdProductAdminVO vo = new GzOrdProductAdminVO();
        vo.setId(e.getId());
        vo.setProductNo(e.getProductNo());
        vo.setName(e.getName());
        vo.setMainImageId(e.getMainImageId());
        vo.setGalleryImageIds(e.getGalleryImageIds());
        if (withDescription) {
            vo.setDescriptionHtml(e.getDescriptionHtml());
        }
        vo.setIpTag(e.getIpTag());
        vo.setDeadlineTime(e.getDeadlineTime());
        vo.setDeliveryDateText(e.getDeliveryDateText());
        vo.setDeliveryDateExact(e.getDeliveryDateExact());
        vo.setStatus(e.getStatus());
        vo.setSalesCount(e.getSalesCount());
        vo.setSortNo(e.getSortNo());
        vo.setVersion(e.getVersion());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }

    /**
     * SKU entity → VO。
     */
    private GzOrdSkuVO toSkuVO(GzOrdSku e) {
        GzOrdSkuVO vo = new GzOrdSkuVO();
        vo.setId(e.getId());
        vo.setProductId(e.getProductId());
        vo.setSkuNo(e.getSkuNo());
        vo.setSpecName(e.getSpecName());
        vo.setPriceCent(e.getPriceCent());
        vo.setStockTotal(e.getStockTotal());
        vo.setStockRemain(e.getStockRemain());
        vo.setEnabled(e.getEnabled());
        vo.setSortNo(e.getSortNo());
        return vo;
    }
}
