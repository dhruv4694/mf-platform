package com.mfplatform.mfplatform.folio;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "folio")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Folio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "folio_number", nullable = false, unique = true)
    private String folioNumber;

    @Column(name = "investor_id", nullable = false)
    private Long investorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
