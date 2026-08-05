package com.mfplatform.mfplatform.common;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BusinessDateService holds the platform's current business date — the virtual
 * "today" that transaction stamping, SIP due-date checks, and EOD settlement
 * are all driven by, instead of the real system clock.
 *
 * WHY A VIRTUAL BUSINESS DATE:
 * A real AMC advances its books one business day at a time, running EOD
 * settlement at the close of each day. Demoing that cadence (SIP due dates,
 * NAV import lag, multi-day settlement backlogs) shouldn't require waiting on
 * the real calendar — an admin can advance the business date explicitly via
 * AdminController, then trigger EOD for that date.
 *
 * IN-MEMORY ONLY:
 * The current business date lives in this singleton bean's memory, seeded from
 * the real clock at startup. It is not persisted — a restart resets it to
 * today's real date. That's an accepted simplification for this project; a
 * production system would persist and audit business date advances.
 *
 * THREAD SAFETY:
 * AtomicReference gives safe concurrent reads/writes without needing a lock —
 * sufficient for a single LocalDate value with no compound operations.
 */
@Service
public class BusinessDateService {

    private final AtomicReference<LocalDate> currentDate =
            new AtomicReference<>(LocalDate.now());

    /**
     * Returns the platform's current business date.
     * Every service that needs "today" for business-logic purposes (transaction
     * stamping, due-date checks, EOD's default target date) should call this
     * instead of LocalDate.now().
     */
    public LocalDate today() {
        return currentDate.get();
    }

    /**
     * Advances the business date to the given date.
     *
     * @param next the new business date — must not be before the current one.
     *             Business dates move forward only, mirroring how a real AMC's
     *             books advance one day at a time.
     * @throws IllegalArgumentException if next is before the current business date
     */
    public void advance(LocalDate next) {
        LocalDate current = currentDate.get();
        if (next.isBefore(current)) {
            throw new IllegalArgumentException(
                    "Cannot move business date backwards: current=" + current + ", requested=" + next);
        }
        currentDate.set(next);
    }
}
