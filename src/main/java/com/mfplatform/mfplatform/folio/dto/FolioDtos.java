package com.mfplatform.mfplatform.folio.dto;

import java.time.Instant;

public class FolioDtos {

    // investorId ignored server-side for INVESTOR role — always their own.
    // Required for ADMIN/DISTRIBUTOR creating on behalf of a client.
    public record CreateFolioRequest(
            Long investorId
    ) {}

    public record FolioResponse(
            Long id,
            String folioNumber,
            Long investorId,
            Instant createdAt
    ) {}
}
