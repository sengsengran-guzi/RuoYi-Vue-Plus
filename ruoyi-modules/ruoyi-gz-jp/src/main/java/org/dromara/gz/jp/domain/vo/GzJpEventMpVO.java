package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 场 <b>mp 端</b> VO（GZ-JP-101，FLOW:F-JP-02.step1 首页场列表 / 进场页头）。
 *
 * <p>与 {@link GzJpEventAdminVO} 的区别：mp 只看得到「生效状态 = open」的场，
 * 故不返回 rawStatus / sortNo / version 等运营字段；封面直接给可渲染的预签名 URL。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Data
public class GzJpEventMpVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 场编号 EVT-yyyyMMdd-6位（mp 路由 / 客服沟通用业务码，不暴露 id） */
    private String eventNo;

    /** 场名称 */
    private String name;

    /** 封面可渲染 URL（1h 预签名；无图 / 解析失败回落占位图） */
    private String coverImageUrl;

    /** 场简介 */
    private String description;

    /**
     * 生效状态 —— mp 侧恒为 {@code open}（其余状态根本查不到）。
     *
     * <p>保留该字段是为了让「mp 不下发 draft / closed」这条契约在回归包里可断言
     * （doc/jp/verify.sh L1「场列表结构完整且不下发 draft」）。</p>
     */
    private String status;

    /** 开场时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 闭场时间（mp 展示倒计时用） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
