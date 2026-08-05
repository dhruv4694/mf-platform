package com.mfplatform.mfplatform.nav;

import com.mfplatform.mfplatform.nav.dto.NavDtos.*;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.scheme.SchemeNotFoundException;
import com.mfplatform.mfplatform.scheme.SchemeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/**
 * NavImportService handles importing daily NAV values for schemes.
 *
 * IN A REAL AMC SYSTEM:
 * NAVs are published by the AMC to AMFI by 9 PM each business day.
 * The RTA (CAMS/KFintech) provides a file or API with the day's NAVs.
 * NavImportService would parse that file and bulk-insert the records.
 *
 * IN THIS PROJECT:
 * We simulate two things:
 *   1. Single NAV import: ADMIN manually sets a NAV for a scheme on a date
 *      (useful for testing and seeding data)
 *   2. Bulk simulation: generates random but realistic NAV values for all
 *      schemes for a given date range (useful for seeding historical data
 *      so return calculations and charts have something to display)
 *
 * EVENT PUBLISHING (Observer pattern):
 * After a successful NAV import, NavImportService publishes a NavImportedEvent.
 * Any Spring component can listen for this event using @EventListener.
 * This decouples NavImportService from whatever needs to happen next
 * (e.g. triggering pending SIP allotments, notifying ops staff) —
 * NavImportService doesn't need to know about those downstream consumers.
 * This is the Observer pattern, implemented via Spring's ApplicationEventPublisher.
 *
 * IDEMPOTENCY:
 * Importing the same scheme+date combination twice is silently ignored
 * (the existing record is returned). This makes the import safe to retry.
 */
@Service
public class NavImportService {

    private final NavHistoryRepository navHistoryRepository;
    private final SchemeRepository schemeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Random random = new Random();

    public NavImportService(
            NavHistoryRepository navHistoryRepository,
            SchemeRepository schemeRepository,
            ApplicationEventPublisher eventPublisher) {
        this.navHistoryRepository = navHistoryRepository;
        this.schemeRepository = schemeRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Imports a single NAV record for a specific scheme and date.
     * ADMIN-only (enforced at NavController).
     *
     * Idempotent: if a NAV already exists for this scheme+date, the existing
     * record is returned without modification. This means re-running an import
     * for the same date is safe.
     *
     * After a new record is saved, publishes a NavImportedEvent so downstream
     * listeners (e.g. SIP execution trigger) can react.
     */
    @Transactional
    public NavHistoryResponse importNav(ImportNavRequest request) {
        Scheme scheme = schemeRepository.findById(request.schemeId())
                .orElseThrow(() -> new SchemeNotFoundException(request.schemeId()));

        // Idempotency check: if this date's NAV already exists, return it as-is
        return navHistoryRepository
                .findBySchemeIdAndNavDate(scheme.getId(), request.navDate())
                .map(this::toResponse)
                .orElseGet(() -> {
                    NavHistory saved = navHistoryRepository.save(
                            NavHistory.builder()
                                    .scheme(scheme)
                                    .navDate(request.navDate())
                                    .navValue(request.navValue())
                                    .build()
                    );

                    // Publish event AFTER the DB write succeeds.
                    // If the save fails, the event is never published —
                    // no downstream action happens for a failed import.
                    eventPublisher.publishEvent(new NavImportedEvent(this, saved));

                    return toResponse(saved);
                });
    }

    /**
     * Bulk-imports NAVs for ALL schemes for a given date range.
     * Simulates realistic NAV values using a random walk from a base NAV.
     *
     * ADMIN-only. Primarily useful for:
     *   1. Seeding historical NAV data on a fresh database
     *   2. Testing return calculations without manually entering hundreds of NAVs
     *
     * The random walk model:
     *   - Start from a base NAV (passed in the request, or defaulted per scheme)
     *   - Each day's NAV = previous day's NAV × (1 + daily_return)
     *   - daily_return is drawn from a normal distribution: mean 0%, std dev 1%
     *   - This produces realistic-looking NAV series (like a stock/fund would have)
     *
     * @return count of new NAV records created (skips dates that already have NAVs)
     */
    @Transactional
    public BulkNavImportResponse simulateBulkNavImport(BulkNavImportRequest request) {
        List<Scheme> schemes = schemeRepository.findAll();
        int created = 0;

        for (Scheme scheme : schemes) {
            BigDecimal currentNav = request.baseNav(); // start point for this scheme

            LocalDate date = request.fromDate();
            while (!date.isAfter(request.toDate())) {

                // Skip weekends — NAVs are only published on business days
                if (!isWeekend(date)) {
                    boolean alreadyExists = navHistoryRepository
                            .findBySchemeIdAndNavDate(scheme.getId(), date)
                            .isPresent();

                    if (!alreadyExists) {
                        navHistoryRepository.save(
                                NavHistory.builder()
                                        .scheme(scheme)
                                        .navDate(date)
                                        .navValue(currentNav)
                                        .build()
                        );
                        created++;
                    }

                    // Advance NAV using random walk (±1% daily, like a real fund)
                    double dailyReturn = random.nextGaussian() * 0.01; // std dev 1%
                    currentNav = currentNav.multiply(
                            BigDecimal.valueOf(1 + dailyReturn)
                    ).setScale(4, java.math.RoundingMode.HALF_UP);

                    // Floor at 0.0001 — NAV can't go to zero or negative
                    if (currentNav.compareTo(BigDecimal.valueOf(0.0001)) < 0) {
                        currentNav = BigDecimal.valueOf(0.0001);
                    }
                }

                date = date.plusDays(1);
            }
        }

        return new BulkNavImportResponse(created, request.fromDate(), request.toDate());
    }

    /**
     * Returns NAV history for a scheme, optionally filtered by date range.
     * Read-only — available to all authenticated roles.
     */
    public List<NavHistoryResponse> getNavHistory(Long schemeId, LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            return navHistoryRepository
                    .findBySchemeIdAndDateRange(schemeId, from, to)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }
        return navHistoryRepository
                .findBySchemeIdOrderByNavDateAsc(schemeId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
    }

    private NavHistoryResponse toResponse(NavHistory nav) {
        return new NavHistoryResponse(
                nav.getId(),
                nav.getScheme().getId(),
                nav.getNavDate(),
                nav.getNavValue()
        );
    }
}
