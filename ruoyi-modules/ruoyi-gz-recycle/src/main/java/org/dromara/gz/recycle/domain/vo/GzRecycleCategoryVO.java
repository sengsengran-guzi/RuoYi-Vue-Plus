package org.dromara.gz.recycle.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收可选品类选项（GZ-RECYCLE-002 mp 填单品类下拉，doc/12 §MP-RECYCLE-FORM）。
 *
 * <p>= 价目表 {@code gz_recycle_price_rule} 中有 enabled 规则的 distinct category + 字典
 * {@code gz_recycle_category} 中文 label。无字典 label 时 fallback 用 value（防 mp 显示空）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GzRecycleCategoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 品类 dict value（如 card / goods） */
    private String value;

    /** 品类中文 label（如 卡牌 / 谷子）；无字典项时 = value */
    private String label;
}
