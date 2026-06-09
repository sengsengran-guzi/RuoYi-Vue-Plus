package org.dromara.gz.ord.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 预购商品导入结果（GZ-ADMIN-101 AC 8）。行级校验全失败回滚（决策 D5），不部分提交；
 * 失败时返回每行错误（行号 + 原因）供运营修正后重导。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-101)
 */
@Data
public class GzOrdProductImportResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 是否整体成功（true 时已落库，false 时全量回滚） */
    private boolean success;

    /** 成功导入的商品数（success=true 时有效） */
    private int productCount;

    /** 成功导入的 SKU 数（success=true 时有效） */
    private int skuCount;

    /** 行级错误列表（success=false 时有效；行号从 1 起，对齐 Excel 数据行） */
    private List<String> errors = new ArrayList<>();

    public void addError(int rowNo, String reason) {
        this.errors.add("第 " + rowNo + " 行：" + reason);
    }
}
