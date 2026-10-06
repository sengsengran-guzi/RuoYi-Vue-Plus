package org.dromara.gz.jp.domain.bo;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class GzJpSnapSubmitBo {
    @NotBlank private String quoteId;
    @NotNull private Long addressId;
    @NotNull private Boolean ackNoCancel;
    @Size(max=500) private String userNote;
}
