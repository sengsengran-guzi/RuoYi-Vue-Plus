package org.dromara.gz.jp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * gz_jp_snap_term —— 中日词表（中文搜索词 → 日文，REQ-SNAP-014）。
 *
 * <p>删除走软删；同中文词重新新增时恢复原行（UNIQUE(tenant_id, zh_term) 不含 del_flag）。</p>
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_snap_term} 段；
 * 建表迁移 {@code V202610050723__GZ-SNAP-101-create-snap-tables.sql}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_snap_term")
public class GzJpSnapTerm extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露给前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 中文词；UNIQUE(tenant_id, zh_term) */
    private String zhTerm;

    /** 日文词 */
    private String jaTerm;

    /** 词类（字典 gz_jp_snap_term_type）ip 作品 / character 角色 / goods 品类 */
    private String termType;

    /** 启用 */
    private Boolean enabled;

    /** 同一中文前缀多条命中时的优先级，越小越前 */
    private Integer sortNo;

    /** 软删标志（'0' 正常 / '1' 删除） */
    @TableLogic
    private String delFlag;
}
