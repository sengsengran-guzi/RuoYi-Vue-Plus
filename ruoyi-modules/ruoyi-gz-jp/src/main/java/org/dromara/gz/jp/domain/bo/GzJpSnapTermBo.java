package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.*;

/** Operator-managed dictionary input; system dictionary validators are deliberately avoided. */
public record GzJpSnapTermBo(
    @NotBlank @Size(max = 64) String zhTerm,
    @NotBlank @Size(max = 128) String jaTerm,
    @NotBlank @Pattern(regexp = "ip|character|goods") String termType,
    @NotNull Boolean enabled,
    @NotNull @Min(0) Integer sortNo
) { }
