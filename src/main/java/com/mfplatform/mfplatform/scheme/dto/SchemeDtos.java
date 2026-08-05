package com.mfplatform.mfplatform.scheme.dto;

import com.mfplatform.mfplatform.scheme.SchemeCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class SchemeDtos {

    public record CreateSchemeRequest(
            @NotBlank String schemeName,
            @NotBlank String schemeCode,
            @NotNull SchemeCategory category
    ) {}

    // schemeCode intentionally absent — immutable once created
    public record UpdateSchemeRequest(
            @NotBlank String schemeName,
            @NotNull SchemeCategory category
    ) {}

    public record SchemeResponse(
            Long id,
            String schemeName,
            String schemeCode,
            SchemeCategory category
    ) {}
}
