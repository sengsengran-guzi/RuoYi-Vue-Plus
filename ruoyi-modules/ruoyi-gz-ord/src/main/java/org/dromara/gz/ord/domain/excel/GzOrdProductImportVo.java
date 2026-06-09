package org.dromara.gz.ord.domain.excel;

import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 预购商品导入行（GZ-ADMIN-101 AC 8）。一行 = 一个 SKU；同名商品（按「商品名」聚合）的多行合并为一个商品 +
 * 多 SKU。新建商品固定 off_shelf（决策 D5），product_no / sku_no 系统生成。
 *
 * <p>行级校验（service 汇总）：商品名 / 规格名 / 单价必填；截止时间格式 {@code yyyy-MM-dd HH:mm:ss}；
 * 到货文案 / 到货精确日二选一（F6.1）；单价 ≥ 0；库存空 = 无限。任一行校验失败 → 全量回滚不部分提交（决策 D5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-101)
 */
@Data
public class GzOrdProductImportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ColumnWidth(28)
    @ExcelProperty(value = "商品名")
    private String name;

    @ColumnWidth(16)
    @ExcelProperty(value = "IP标签")
    private String ipTag;

    @ColumnWidth(22)
    @ExcelProperty(value = "预订截止时间(yyyy-MM-dd HH:mm:ss)")
    private String deadlineTime;

    @ColumnWidth(18)
    @ExcelProperty(value = "到货文案(与精确日二选一)")
    private String deliveryDateText;

    @ColumnWidth(20)
    @ExcelProperty(value = "到货精确日(yyyy-MM-dd 与文案二选一)")
    private String deliveryDateExact;

    @ColumnWidth(18)
    @ExcelProperty(value = "规格名")
    private String specName;

    @ColumnWidth(12)
    @ExcelProperty(value = "单价(元)")
    private String priceYuan;

    @ColumnWidth(12)
    @ExcelProperty(value = "总库存(空=无限)")
    private String stockTotal;
}
