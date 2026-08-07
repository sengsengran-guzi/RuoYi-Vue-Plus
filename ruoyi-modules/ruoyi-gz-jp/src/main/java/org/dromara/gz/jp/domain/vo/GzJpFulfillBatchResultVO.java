package org.dromara.gz.jp.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量推进 / 批量发货的执行结果（GZ-JP-106）。
 *
 * <p><b>为什么是「部分成功 + 明细」而不是全有或全无</b>：店员一次勾 30 行，
 * 里面混着同事刚推过的、客人没付钱的、已经发出去的很正常。整批因为 1 行不合法就全部失败，
 * 会逼着店员一行行试。所以：<b>能推的推掉，推不动的逐行说明</b>。</p>
 *
 * <p>唯一的例外是「一行都没推动且也没有任何行是幂等跳过」——那说明店员的操作整体就是错的
 * （比如选错了目标状态），此时抛 {@code 4108} 让 admin 弹红字，不返回一个 0/0/N 的假成功。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Data
public class GzJpFulfillBatchResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标状态（发货时恒 delivered） */
    private String targetStatus;

    /** 目标状态中文 */
    private String targetStatusLabel;

    /** 本次请求的行数（去重后） */
    private int requested;

    /** 真正改动的行数 */
    private int advanced;

    /** 已是目标态、无需改动的行数（幂等跳过，不算失败） */
    private int skipped;

    /** 被拒的行数（= {@link #rejects} 的长度） */
    private int rejected;

    /** 本次发货写入的快递编码（仅 /ship） */
    private String carrierCode;

    /** 本次发货写入的快递中文名（仅 /ship；字典查不到时为 null，不阻断发货） */
    private String carrierLabel;

    /** 本次发货写入的运单号（仅 /ship）。★ 同单号的行即同一包裹 */
    private String trackingNo;

    /** 被拒明细（逐行原因） */
    private List<GzJpFulfillRejectVO> rejects = new ArrayList<>();
}
