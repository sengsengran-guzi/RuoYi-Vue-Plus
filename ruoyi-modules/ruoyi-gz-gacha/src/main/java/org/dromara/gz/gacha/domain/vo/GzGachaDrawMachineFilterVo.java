package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 开盒历史「机器筛选 chip」项（GZ-GACHA-106 AC3，{@code GET /app/gz/gacha/draw/my/machines}）。
 *
 * <p>来源 = 本人历史 <b>distinct machine</b>（{@code SELECT DISTINCT machine_id} from 本人 draw），
 * <b>不全量拉 {@code gz_gacha_machine} 表</b>（强约束 #3 / 决策 D3 —— 用户只关心自己开过的机器）。
 * machineName 解自 draw 行的 {@code machine_snapshot_json}（非实时 join，与列表口径一致）。</p>
 *
 * <p>跨层契约：{@code machineId} 序列化为 string。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-106)
 */
@Data
public class GzGachaDrawMachineFilterVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 机器 id（string；筛选时回传 machineId 过滤列表） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long machineId;

    /** 机器名（解本人某条 draw 的 machine_snapshot_json） */
    private String machineName;
}
