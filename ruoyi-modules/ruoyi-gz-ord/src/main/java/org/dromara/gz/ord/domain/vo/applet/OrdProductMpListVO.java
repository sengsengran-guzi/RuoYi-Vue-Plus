package org.dromara.gz.ord.domain.vo.applet;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * mp 预购列表分页响应外层（GZ-ORD-102 AC2）。
 *
 * <p>ruoyi 默认分页 {@code TableDataInfo} 仅 rows/total，无处挂 {@code serverNow}；本卡需在<b>列表外层</b>
 * 返回服务器当前时间供前端倒计时校准（决策 D4 / R2，防客户端改表），故用自定义外层 VO 经 {@code R<T>} 返回
 * （前端 http 封装对非 TableDataInfo 结构走 R.data 解包，gz-news.ts 已验证此分支）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-102)
 */
@Data
public class OrdProductMpListVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前页卡片列表 */
    private List<OrdProductCardVO> rows;

    /** 满足条件总条数（前端判断是否还有下一页） */
    private long total;

    /** 服务器当前时间（ISO8601，前端倒计时基准，决策 D4） */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime serverNow;
}
