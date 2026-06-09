package org.dromara.gz.ord.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量上下架结果（GZ-ADMIN-101 AC 7）。
 *
 * <p>批量上架/下架时，不满足条件的商品（无 SKU / 已过截止日 / 当前为 auto_off 不可被上架覆盖）不报错整体失败，
 * 而是过滤进 {@link #skipped} 返回原因，成功的进 {@link #success}，前端提示哪些被跳过（R5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-101)
 */
@Data
public class GzOrdBatchStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 成功流转的商品 id 列表（string，跨层契约 #1） */
    @JsonSerialize(using = ToStringSerializer.class)
    private List<Long> success = new ArrayList<>();

    /** 被跳过的商品（id + 原因），前端汇总提示 */
    private List<Skipped> skipped = new ArrayList<>();

    public void addSuccess(Long id) {
        this.success.add(id);
    }

    public void addSkipped(Long id, String reason) {
        this.skipped.add(new Skipped(id, reason));
    }

    /**
     * 单个被跳过项（商品 id + 跳过原因）。
     */
    @Data
    public static class Skipped implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        private String reason;

        public Skipped() {
        }

        public Skipped(Long id, String reason) {
            this.id = id;
            this.reason = reason;
        }
    }
}
