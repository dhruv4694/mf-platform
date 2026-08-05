package com.mfplatform.mfplatform.sip;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * SipMandateRepository.
 *
 * The most important query is findDueMandates() — used by the SIP batch job
 * (SipItemReader) every morning to find which SIP installments are due today.
 *
 * The partial index in the schema (WHERE status = 'ACTIVE') makes this query
 * efficient even with many cancelled/completed mandates — Postgres only indexes
 * active rows, so the index stays small and fast.
 */
public interface SipMandateRepository extends JpaRepository<SipMandate, Long> {

    /**
     * Finds all ACTIVE mandates due for execution on or before the given date.
     *
     * "On or before" handles edge cases:
     *   - Scheduler runs at 9 AM, mandate was due yesterday (holiday/weekend skip)
     *   - App was down for a day and missed yesterday's run
     *
     * In a real system you'd be more careful about processing missed installments —
     * for this project, processing all overdue ones in one run is acceptable.
     */
    @Query("""
        select m from SipMandate m
         where m.status = com.mfplatform.mfplatform.sip.SipMandateStatus.ACTIVE
           and m.nextDueDate <= :asOfDate
        """)
    List<SipMandate> findDueMandates(@Param("asOfDate") LocalDate asOfDate);

    /**
     * All mandates for a specific folio — used by GET /sip-mandates endpoint.
     */
    Page<SipMandate> findByFolioId(Long folioId, Pageable pageable);

    /**
     * All mandates across a set of folios — used for DISTRIBUTOR/ADMIN listing.
     */
    Page<SipMandate> findByFolioIdIn(List<Long> folioIds, Pageable pageable);

    /**
     * Active mandates for a specific folio/scheme pair.
     * Used to check for duplicate SIP registrations — prevents registering
     * two active SIPs for the same folio/scheme combination.
     */
    @Query("""
        select count(m) > 0 from SipMandate m
         where m.folioId = :folioId
           and m.schemeId = :schemeId
           and m.status = com.mfplatform.mfplatform.sip.SipMandateStatus.ACTIVE
        """)
    boolean existsActiveMandateForFolioAndScheme(
            @Param("folioId") Long folioId,
            @Param("schemeId") Long schemeId);
}
