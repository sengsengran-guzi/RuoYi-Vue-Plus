package org.dromara.gz.ord.domain.excel;

import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 预购商品导出行（GZ-ADMIN-101 AC 8）。product × SKU 平铺：一行 = 一个 SKU；
 * 同商品多 SKU 时商品列重复（便于运营按规格逐行核对价格 / 库存）。
 *
 * <p>金额 priceCent（分）导出为元（{@link #priceYuan}）；status 走中文文案（service 转）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-101)
 */
@Data
public class GzOrdProductExportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ColumnWidth(20)
    @ExcelProperty(value = "商品编号")
    private String productNo;

    @ColumnWidth(28)
    @ExcelProperty(value = "商品名")
    private String name;

    @ColumnWidth(16)
    @ExcelProperty(value = "IP标签")
    private String ipTag;

    @ColumnWidth(14)
    @ExcelProperty(value = "状态")
    private String statusText;

    @ColumnWidth(20)
    @ExcelProperty(value = "预订截止时间")
    private String deadlineTime;

    @ColumnWidth(16)
    @ExcelProperty(value = "到货时间")
    private String deliveryText;

    @ColumnWidth(10)
    @ExcelProperty(value = "销量")
    private Long salesCount;

    @ColumnWidth(20)
    @ExcelProperty(value = "SKU编号")
    private String skuNo;

    @ColumnWidth(18)
    @ExcelProperty(value = "规格名")
    private String specName;

    @ColumnWidth(12)
    @ExcelProperty(value = "单价(元)")
    private String priceYuan;

    @ColumnWidth(12)
    @ExcelProperty(value = "总库存")
    private String stockTotal;

    @ColumnWidth(12)
    @ExcelProperty(value = "剩余库存")
    private String stockRemain;

    @ColumnWidth(10)
    @ExcelProperty(value = "SKU启用")
    private String enabledText;
}
