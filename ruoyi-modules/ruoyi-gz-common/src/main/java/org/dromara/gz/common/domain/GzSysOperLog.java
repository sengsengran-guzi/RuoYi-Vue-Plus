package org.dromara.gz.common.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * GZ-SYS-006 操作日志实体（独立映射 ruoyi 自带 sys_oper_log 表）。
 *
 * <p>设计动机：避免 ruoyi-gz-common 依赖 ruoyi-system 模块（违反 CLAUDE.md §6 #1 "不动 ruoyi 自带模块"
 * 的耦合面 — pom 不引入 ruoyi-system 才算干净）。本实体字段与 {@code SysOperLog} 完全一致，
 * 只是位于 gz 包下、由 gz 模块自管 mapper/controller。</p>
 *
 * <p>写日志由 ruoyi 自带 LogAspect / OperLogEvent / SysOperLogServiceImpl.recordOper 完成
 * （AOP @Log 自动写入）— 本模块**只读 + 删过期**。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-006)
 */
@Data
@TableName("sys_oper_log")
public class GzSysOperLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日志主键 */
    @TableId(value = "oper_id")
    private Long operId;

    /** 租户编号 */
    private String tenantId;

    /** 操作模块 */
    private String title;

    /** 业务类型（0 其它 / 1 新增 / 2 修改 / 3 删除 / 4 授权 / 5 导出 / 6 导入 / 7 强退 / 8 生成代码 / 9 清空数据 / ...） */
    private Integer businessType;

    /** 请求方法 */
    private String method;

    /** 请求方式（GET/POST/PUT/DELETE）*/
    private String requestMethod;

    /** 操作类别（0 其它 / 1 后台用户 / 2 手机端用户） */
    private Integer operatorType;

    /** 操作人员（登录用户名） */
    private String operName;

    /** 部门名称 */
    private String deptName;

    /** 请求 url */
    private String operUrl;

    /** 操作地址 */
    private String operIp;

    /** 操作地点 */
    private String operLocation;

    /** 请求参数 */
    private String operParam;

    /** 返回参数 */
    private String jsonResult;

    /** 操作状态（0 正常 / 1 异常） */
    private Integer status;

    /** 错误消息 */
    private String errorMsg;

    /** 操作时间 */
    private Date operTime;

    /** 消耗时间（毫秒） */
    private Long costTime;
}
