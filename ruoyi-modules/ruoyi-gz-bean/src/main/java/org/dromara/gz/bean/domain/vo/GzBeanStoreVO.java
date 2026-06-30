package org.dromara.gz.bean.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanStore;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * gz_bean_store 视图对象（admin / mp 共用）。
 *
 * <p>字段权威：doc/11 §3.1。</p>
 *
 * <p>admin 端：列表 / 详情完整返回；
 * mp 端：{@code GET /app/gz/bean/store/list} 仅返回 type='pindou' + status='open' 的子集。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@Data
@AutoMapper(target = GzBeanStore.class)
public class GzBeanStoreVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 业务码（如 CD001） */
    private String storeNo;

    /** 门店名 */
    private String name;

    /** 类型 pindou / guzi */
    private String type;

    /** 完整地址 */
    private String address;

    /** 经度 */
    private BigDecimal longitude;

    /** 纬度 */
    private BigDecimal latitude;

    /** 门店电话 */
    private String phone;

    /** 营业时间字符串 */
    private String businessHours;

    /** 门店图片 file id（gz_file_object.id；admin 回显 / GzImageThumb 用） */
    private Long imageId;

    /**
     * 门店图片可访问 URL（1h 预签名）。
     * <p>仅 mp 端 {@code selectMpList} 由 imageId 解析填充；admin 端不填（走 imageId + GzImageThumb）。
     * 无图 / 解析失败 → null（mp 不显示，不回退占位）。AutoMapper 无对应 entity 字段 → 不参与映射。</p>
     */
    private String imageUrl;

    /** 状态 open / closed / maintenance */
    private String status;

    /** 可预约最大提前天数 */
    private Integer maxAdvanceDays;

    /** 计时看板临近结束提前提醒分钟数（ADR-0016 §6，默认 30） */
    private Integer nearEndMinutes;

    /** 创建时间（公共字段） */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;
}
