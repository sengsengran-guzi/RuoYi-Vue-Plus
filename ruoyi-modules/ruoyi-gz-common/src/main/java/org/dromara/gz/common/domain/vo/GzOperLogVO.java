package org.dromara.gz.common.domain.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.common.excel.annotation.ExcelDictFormat;
import org.dromara.common.excel.convert.ExcelDictConvert;
import org.dromara.gz.common.domain.GzSysOperLog;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * GZ-SYS-006 操作日志展示 VO（admin 列表 / 详情）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-006)
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = GzSysOperLog.class)
public class GzOperLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日志主键（Jackson 全局 Long→String，前端按字符串处理） */
    @ExcelProperty(value = "日志编号")
    private Long operId;

    /** 租户编号（V1.0 固定 1001） */
    private String tenantId;

    /** 操作模块（@Log title 字段） */
    @ExcelProperty(value = "操作模块")
    private String title;

    /** 业务类型（0其它 1新增 2修改 3删除 …，走 sys_oper_type 字典） */
    @ExcelProperty(value = "业务类型", converter = ExcelDictConvert.class)
    @ExcelDictFormat(dictType = "sys_oper_type")
    private Integer businessType;

    /** 请求方法（com.x.y.Controller.method） */
    @ExcelProperty(value = "请求方法")
    private String method;

    /** 请求方式 */
    @ExcelProperty(value = "请求方式")
    private String requestMethod;

    /** 操作类别（0 其它 / 1 后台用户 / 2 手机端用户） */
    @ExcelProperty(value = "操作类别", converter = ExcelDictConvert.class)
    @ExcelDictFormat(readConverterExp = "0=其它,1=后台用户,2=手机端用户")
    private Integer operatorType;

    /** 操作人员（登录用户名） */
    @ExcelProperty(value = "操作人员")
    private String operName;

    /** 部门名称（@Log AOP 写入时已 resolve） */
    @ExcelProperty(value = "部门名称")
    private String deptName;

    /** 请求 URL */
    @ExcelProperty(value = "请求URL")
    private String operUrl;

    /** 操作 IP */
    @ExcelProperty(value = "操作地址")
    private String operIp;

    /** 操作地点 */
    @ExcelProperty(value = "操作地点")
    private String operLocation;

    /** 请求参数（最大 4000） */
    @ExcelProperty(value = "请求参数")
    private String operParam;

    /** 返回结果（最大 4000） */
    @ExcelProperty(value = "返回结果")
    private String jsonResult;

    /** 操作状态（0 正常 / 1 异常，走 sys_common_status 字典） */
    @ExcelProperty(value = "操作状态", converter = ExcelDictConvert.class)
    @ExcelDictFormat(dictType = "sys_common_status")
    private Integer status;

    /** 错误信息 */
    @ExcelProperty(value = "错误信息")
    private String errorMsg;

    /** 操作时间 */
    @ExcelProperty(value = "操作时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date operTime;

    /** 消耗时间（毫秒） */
    @ExcelProperty(value = "消耗时间（毫秒）")
    private Long costTime;
}
