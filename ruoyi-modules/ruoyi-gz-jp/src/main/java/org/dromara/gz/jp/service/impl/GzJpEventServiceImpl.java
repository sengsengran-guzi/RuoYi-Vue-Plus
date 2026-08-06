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
import org.dromara.gz.jp.domain.bo.GzJpEventBo;
import org.dromara.gz.jp.domain.bo.GzJpEventQueryBo;
import org.dromara.gz.jp.domain.entity.GzJpEvent;
import org.dromara.gz.jp.domain.enums.GzJpEventStatus;
import org.dromara.gz.jp.domain.vo.GzJpEventAdminVO;
import org.dromara.gz.jp.domain.vo.GzJpEventMpVO;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.mapper.GzJpEventMapper;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 拼团场服务实现（GZ-JP-101，FLOW:F-JP-01）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_event} 段。
 * 多租户 / 软删 / 公共字段注入 / 乐观锁均由 ruoyi MyBatis-Plus 拦截器完成。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li><b>状态读时惰性判定</b>（FLOW:F-JP-01.step4）：到 end_time 即视为已结束，
 *       但绝不写库、绝不上 cron —— 本项目 prod 未部署 SnailJob，{@code @JobExecutor} 一个都不会跑。
 *       判定逻辑集中在 {@link GzJpEventStatus#effective}，admin 展示与 mp 过滤共用同一份。</li>
 *   <li><b>admin 状态筛选下沉 SQL</b>：不做内存过滤，否则分页 total 会失真。</li>
 *   <li><b>mp 可见 ≠ mp 可下单</b>（两条闸粗细不同，写代码别混）：
 *       可见 = 存库 open / closed（{@link GzJpEventStatus#isVisibleForMp}，draft 永不可见）；
 *       可下单 = 生效状态 open（{@link GzJpEventStatus#isBookableForMp}，关场 / 到点即 false）。
 *       已结束的场照样下发，UI:mp.home 要拿它填「已结束」分组、UI:mp.event_detail 要它显示
 *       「本场已结束」并置灰加购。不额外以 start_time 卡门 —— FLOW:F-JP-01.step3 明确「开场」
 *       是店员的显式动作，开了就该可见；「即将开始」是前端按 start_time 派生的展示分组，不是状态。</li>
 *   <li><b>event_no 生成含软删行</b>：uk_event_no 覆盖软删，序号必须跳过已被软删占用的号
 *       （gz_bean_booking 曾因此撞唯一键）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpEventServiceImpl implements IGzJpEventService {

    /** 封面缺失 / 解析失败时的 mp 占位图（同 gz-gacha / gz-ord）。 */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    /** event_no = "EVT-" (4) + yyyyMMdd (8) + "-" (1) + 6 位序号 = 19。 */
    private static final DateTimeFormatter EVENT_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String EVENT_NO_PREFIX = "EVT-";
    private static final int EVENT_NO_TOTAL_LEN = 19;
    private static final int EVENT_NO_SEQ_LEN = 6;
    private static final int EVENT_NO_SEQ_MAX = 999999;

    private final GzJpEventMapper baseMapper;
    private final IGzFileService fileService;

    // ============================================================
    //  admin（UI:admin.event）
    // ============================================================

    @Override
    public TableDataInfo<GzJpEventAdminVO> selectAdminPage(GzJpEventQueryBo query, PageQuery pageQuery) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<GzJpEvent> lqw = Wrappers.<GzJpEvent>lambdaQuery()
            .like(StrUtil.isNotBlank(query.getEventNo()), GzJpEvent::getEventNo, query.getEventNo())
            .like(StrUtil.isNotBlank(query.getName()), GzJpEvent::getName, query.getName())
            .ge(StrUtil.isNotBlank(query.getBeginStartTime()), GzJpEvent::getStartTime, query.getBeginStartTime())
            .le(StrUtil.isNotBlank(query.getEndStartTime()), GzJpEvent::getStartTime, query.getEndStartTime())
            .orderByAsc(GzJpEvent::getSortNo)
            .orderByDesc(GzJpEvent::getStartTime)
            .orderByDesc(GzJpEvent::getId);
        applyEffectiveStatusFilter(lqw, query.getStatus(), now);

        Page<GzJpEvent> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzJpEventAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(e -> toAdminVO(e, now)).toList());
        return TableDataInfo.build(voPage);
    }

    /**
     * 把「生效状态」筛选翻译成 SQL 条件（不做内存过滤，保证分页 total 准确）。
     *
     * <ul>
     *   <li>closed → {@code status = 'closed' OR end_time <= now}（含惰性结束的 draft / open）</li>
     *   <li>draft / open → {@code status = ? AND end_time > now}（排除已到点的）</li>
     *   <li>空 / 未知值 → 不加条件（全部）</li>
     * </ul>
     */
    private void applyEffectiveStatusFilter(LambdaQueryWrapper<GzJpEvent> lqw, String status, LocalDateTime now) {
        if (StrUtil.isBlank(status)) {
            return;
        }
        GzJpEventStatus target = GzJpEventStatus.of(status);
        if (!target.getCode().equals(status)) {
            // 未知 status 值：of() 会回落 DRAFT，此处不误当 draft 过滤，直接忽略该筛选条件
            log.warn("[gz-jp-admin] 忽略未知的场状态筛选值 status={}", status);
            return;
        }
        if (target == GzJpEventStatus.CLOSED) {
            lqw.and(w -> w.eq(GzJpEvent::getStatus, GzJpEventStatus.CLOSED.getCode())
                .or().le(GzJpEvent::getEndTime, now));
        } else {
            lqw.eq(GzJpEvent::getStatus, target.getCode()).gt(GzJpEvent::getEndTime, now);
        }
    }

    @Override
    public GzJpEventAdminVO selectAdminById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzJpEvent e = baseMapper.selectById(id);
        return e == null ? null : toAdminVO(e, LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzJpEventBo bo) {
        validateTimeWindow(bo.getStartTime(), bo.getEndTime());
        GzJpEvent add = new GzJpEvent();
        add.setName(StrUtil.trim(bo.getName()));
        add.setCoverImageId(bo.getCoverImageId());
        add.setDescription(bo.getDescription());
        add.setStartTime(bo.getStartTime());
        add.setEndTime(bo.getEndTime());
        add.setRemark(bo.getRemark());
        add.setSortNo(ObjectUtil.defaultIfNull(bo.getSortNo(), 0));
        add.setEventNo(generateEventNo(LocalDate.now()));
        add.setStatus(GzJpEventStatus.DRAFT.getCode());
        // ★ @Version 字段必须在 insert 前配齐：留 null 会让内存 version 与库里的 DEFAULT 0 脱节，
        //   后续 updateById 因 version 为空而退化成无乐观锁更新（本项目 GZ-BEAN-039 踩过）。
        add.setVersion(0);
        if (baseMapper.insert(add) <= 0) {
            throw new ServiceException("场新建失败");
        }
        log.info("[gz-jp-admin] INSERT event id={} eventNo={} name={} status=draft",
            add.getId(), add.getEventNo(), add.getName());
        return add.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzJpEventBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("场 ID 不能为空");
        }
        validateTimeWindow(bo.getStartTime(), bo.getEndTime());
        GzJpEvent exist = requireExist(bo.getId());
        // eventNo / status / version 系统管理：只覆盖 admin 可填字段
        exist.setName(StrUtil.trim(bo.getName()));
        exist.setCoverImageId(bo.getCoverImageId());
        exist.setDescription(bo.getDescription());
        exist.setStartTime(bo.getStartTime());
        exist.setEndTime(bo.getEndTime());
        exist.setRemark(bo.getRemark());
        exist.setSortNo(ObjectUtil.defaultIfNull(bo.getSortNo(), 0));
        boolean ok = baseMapper.updateById(exist) > 0;
        if (!ok) {
            throw new ServiceException("场信息已被其他人修改，请刷新后重试");
        }
        log.info("[gz-jp-admin] UPDATE event id={} eventNo={} name={}",
            exist.getId(), exist.getEventNo(), exist.getName());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ObjectUtil.isEmpty(ids)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        List<GzJpEvent> events = baseMapper.selectByIds(ids);
        for (GzJpEvent e : events) {
            if (GzJpEventStatus.effective(e.getStatus(), e.getEndTime(), now) == GzJpEventStatus.OPEN) {
                throw new ServiceException("场「" + e.getName() + "」正在进行中，请先关场再删除");
            }
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        log.info("[gz-jp-admin] DELETE event ids={} result={}", ids, ok);
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean open(Long id) {
        GzJpEvent e = requireExist(id);
        LocalDateTime now = LocalDateTime.now();
        if (GzJpEventStatus.of(e.getStatus()) == GzJpEventStatus.OPEN
            && GzJpEventStatus.effective(e.getStatus(), e.getEndTime(), now) == GzJpEventStatus.OPEN) {
            throw new ServiceException("场已在进行中，无需重复开场");
        }
        if (e.getEndTime() == null || !e.getEndTime().isAfter(now)) {
            throw new ServiceException("闭场时间已过，请先把闭场时间改到当前时间之后再开场");
        }
        e.setStatus(GzJpEventStatus.OPEN.getCode());
        if (baseMapper.updateById(e) <= 0) {
            throw new ServiceException("场信息已被其他人修改，请刷新后重试");
        }
        log.info("[gz-jp-admin] OPEN event id={} eventNo={}", e.getId(), e.getEventNo());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean close(Long id) {
        GzJpEvent e = requireExist(id);
        if (GzJpEventStatus.of(e.getStatus()) == GzJpEventStatus.CLOSED) {
            throw new ServiceException("场已关闭，无需重复关场");
        }
        e.setStatus(GzJpEventStatus.CLOSED.getCode());
        if (baseMapper.updateById(e) <= 0) {
            throw new ServiceException("场信息已被其他人修改，请刷新后重试");
        }
        log.info("[gz-jp-admin] CLOSE event id={} eventNo={}", e.getId(), e.getEventNo());
        return true;
    }

    // ============================================================
    //  跨 ticket 复用（GZ-JP-102 商品管理 / 后续订单与履约）
    // ============================================================

    @Override
    public List<GzJpEventOptionVO> selectOptions() {
        LocalDateTime now = LocalDateTime.now();
        List<GzJpEvent> events = baseMapper.selectList(Wrappers.<GzJpEvent>lambdaQuery()
            .orderByAsc(GzJpEvent::getSortNo)
            .orderByDesc(GzJpEvent::getId));
        return events.stream().map(e -> toOptionVO(e, now)).toList();
    }

    @Override
    public Map<Long, GzJpEventOptionVO> selectOptionMap(Collection<Long> ids) {
        if (ObjectUtil.isEmpty(ids)) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> distinctIds = ids.stream().filter(ObjectUtil::isNotNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return baseMapper.selectByIds(distinctIds).stream()
            .collect(Collectors.toMap(GzJpEvent::getId, e -> toOptionVO(e, now), (a, b) -> a, LinkedHashMap::new));
    }

    // ============================================================
    //  mp（FLOW:F-JP-02.step1）
    // ============================================================

    @Override
    public TableDataInfo<GzJpEventMpVO> selectMpPage(PageQuery pageQuery) {
        LocalDateTime now = LocalDateTime.now();
        // 可见性下沉 SQL：status IN (open, closed) —— 白名单而非「!= draft」，脏 / 未知状态值一并挡在外面
        // （与 GzJpEventStatus.of 回落 DRAFT 同一个「最保守」口径）。分页 total 也因此是客人真看得到的条数。
        // ★ 这里不再卡 end_time：到点的场是「已结束」不是「不存在」，UI:mp.home 的已结束分组要它
        LambdaQueryWrapper<GzJpEvent> lqw = Wrappers.<GzJpEvent>lambdaQuery()
            .in(GzJpEvent::getStatus, GzJpEventStatus.OPEN.getCode(), GzJpEventStatus.CLOSED.getCode())
            .orderByAsc(GzJpEvent::getSortNo)
            // end_time 倒序一举两得：未结束的场（end_time 在未来）天然排在已结束之前，保证首页
            // 「进行中置顶」在分页下也成立；组内则是「最近的场在前」（已结束组 = 刚结束的先看到）
            .orderByDesc(GzJpEvent::getEndTime)
            .orderByDesc(GzJpEvent::getId);
        Page<GzJpEvent> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzJpEventMpVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(e -> toMpVO(e, now)).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzJpEventMpVO selectMpDetail(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzJpEvent e = baseMapper.selectById(id);
        // 可浏览即下发（含已结束的场）；能不能下单交给 VO 的 status，前端据此置灰加购
        if (e == null || !GzJpEventStatus.isVisibleForMp(e.getStatus())) {
            return null;
        }
        return toMpVO(e, LocalDateTime.now());
    }

    @Override
    public boolean isBookable(Long id) {
        if (ObjectUtil.isNull(id)) {
            return false;
        }
        GzJpEvent e = baseMapper.selectById(id);
        return e != null
            && GzJpEventStatus.isBookableForMp(e.getStatus(), e.getEndTime(), LocalDateTime.now());
    }

    @Override
    public boolean isVisible(Long id) {
        if (ObjectUtil.isNull(id)) {
            return false;
        }
        GzJpEvent e = baseMapper.selectById(id);
        return e != null && GzJpEventStatus.isVisibleForMp(e.getStatus());
    }

    // ============================================================
    //  internal
    // ============================================================

    private GzJpEvent requireExist(Long id) {
        GzJpEvent e = id == null ? null : baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException("场不存在：" + id);
        }
        return e;
    }

    private void validateTimeWindow(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            throw new ServiceException("开场 / 闭场时间不能为空");
        }
        if (!endTime.isAfter(startTime)) {
            throw new ServiceException("闭场时间必须晚于开场时间");
        }
    }

    private GzJpEventAdminVO toAdminVO(GzJpEvent e, LocalDateTime now) {
        GzJpEventAdminVO vo = new GzJpEventAdminVO();
        vo.setId(e.getId());
        vo.setEventNo(e.getEventNo());
        vo.setName(e.getName());
        vo.setCoverImageId(e.getCoverImageId());
        vo.setDescription(e.getDescription());
        vo.setStartTime(e.getStartTime());
        vo.setEndTime(e.getEndTime());
        vo.setStatus(GzJpEventStatus.effective(e.getStatus(), e.getEndTime(), now).getCode());
        vo.setRawStatus(e.getStatus());
        vo.setSortNo(e.getSortNo());
        vo.setVersion(e.getVersion());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }

    /** 场 → 轻量选项（状态用生效状态，与 {@link #isBookable} 同一份 effective 逻辑）。 */
    private GzJpEventOptionVO toOptionVO(GzJpEvent e, LocalDateTime now) {
        GzJpEventOptionVO vo = new GzJpEventOptionVO();
        vo.setId(e.getId());
        vo.setEventNo(e.getEventNo());
        vo.setName(e.getName());
        vo.setStatus(GzJpEventStatus.effective(e.getStatus(), e.getEndTime(), now).getCode());
        return vo;
    }

    /**
     * 场 → mp VO。走到这里的场必然可浏览（查询条件 / {@link #selectMpDetail} 已守），
     * 状态给<b>生效状态</b>：{@code open} = 还能下单，{@code closed} = 已结束只可浏览。
     * draft 永远走不到这里（可见性闸已挡），所以 mp 端拿不到 draft —— verify.sh L1 断言的就是这条。
     */
    private GzJpEventMpVO toMpVO(GzJpEvent e, LocalDateTime now) {
        GzJpEventMpVO vo = new GzJpEventMpVO();
        vo.setId(e.getId());
        vo.setEventNo(e.getEventNo());
        vo.setName(e.getName());
        vo.setCoverImageUrl(resolveImageUrl(e.getCoverImageId()));
        vo.setDescription(e.getDescription());
        vo.setStatus(GzJpEventStatus.effective(e.getStatus(), e.getEndTime(), now).getCode());
        vo.setStartTime(e.getStartTime());
        vo.setEndTime(e.getEndTime());
        return vo;
    }

    /** cover_image_id → 可渲染签名 URL。NULL / 文件已删（抛异常）→ 占位图（同 gz-gacha / gz-ord）。 */
    private String resolveImageUrl(Long coverImageId) {
        if (coverImageId == null) {
            return PLACEHOLDER_IMAGE_URL;
        }
        try {
            return fileService.getPresignedUrl(coverImageId).getUrl();
        } catch (Exception ex) {
            log.warn("[gz-jp-mp] 场封面解析失败 fileId={}，回退占位图：{}", coverImageId, ex.getMessage());
            return PLACEHOLDER_IMAGE_URL;
        }
    }

    /**
     * 生成 event_no：{@code EVT-yyyyMMdd-000001}，当日序号自增。
     *
     * <p>取最大值时<b>含软删行</b>（见 {@link GzJpEventMapper#selectMaxEventNoIncludeDeleted}），
     * 否则「当日建场 → 软删 → 当日再建」会重用已占号撞 uk_event_no。</p>
     */
    private String generateEventNo(LocalDate day) {
        String prefix = EVENT_NO_PREFIX + day.format(EVENT_NO_DATE_FMT) + "-";
        String max = baseMapper.selectMaxEventNoIncludeDeleted(prefix);
        int next = 1;
        if (StrUtil.isNotBlank(max) && max.length() == EVENT_NO_TOTAL_LEN) {
            String seq = max.substring(prefix.length());
            if (StrUtil.isNumeric(seq)) {
                next = Integer.parseInt(seq) + 1;
            } else {
                log.warn("[gz-jp-admin] event_no 序号段非数字，从 1 重新起算：max={}", max);
            }
        }
        if (next > EVENT_NO_SEQ_MAX) {
            throw new ServiceException("当日建场数量已达上限（" + EVENT_NO_SEQ_MAX + "），请次日再建");
        }
        return prefix + StrUtil.padPre(String.valueOf(next), EVENT_NO_SEQ_LEN, '0');
    }
}
