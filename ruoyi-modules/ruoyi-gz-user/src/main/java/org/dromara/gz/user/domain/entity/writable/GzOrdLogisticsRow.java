package org.dromara.gz.user.domain.entity.writable;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 预购订单物流可写行（GZ-USER-104）—— 绑 {@code gz_ord_order}。
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_ord_order")
public class GzOrdLogisticsRow extends GzLogisticsOrderRow {

    @Serial
    private static final long serialVersionUID = 1L;
}
