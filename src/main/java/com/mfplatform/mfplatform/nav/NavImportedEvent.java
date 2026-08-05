package com.mfplatform.mfplatform.nav;

import org.springframework.context.ApplicationEvent;

/**
 * NavImportedEvent is published by NavImportService after a NAV record is
 * successfully saved to the database.
 *
 * THE OBSERVER PATTERN (via Spring's event system):
 *
 * NavImportService (the "subject" / publisher) doesn't know or care what
 * happens after a NAV is imported. It just announces "a NAV was imported"
 * and any interested component (the "observers" / listeners) can react.
 *
 * Current listeners: none. This previously had a listener (SipExecutionService)
 * that allotted pending SIP installments the moment a NAV they were waiting on
 * landed. That was removed when settlement moved to an explicit, admin-triggered
 * EOD batch (EodProcessingService) instead of firing per-event. Still published
 * — harmless, and a reasonable extension point for future consumers.
 *
 * Future listeners you could add without touching NavImportService:
 *   - An ops alerting service (notify staff that NAV was imported successfully)
 *   - A cache invalidation service (clear any cached NAV values)
 *   - A portfolio recalculation service (update current values for all holdings)
 *
 * This is the Open/Closed Principle in action again — NavImportService is
 * closed for modification but open for extension via new event listeners.
 *
 * WHY ApplicationEvent AND NOT Kafka/RabbitMQ?
 * Spring's ApplicationEventPublisher is synchronous and in-process — the event
 * is handled in the same thread, same transaction. It's simpler and sufficient
 * for a portfolio project. In a real high-volume system you'd use Kafka so the
 * NAV import doesn't have to wait for SIP allotment to finish before returning.
 * That trade-off is documented in PLAN.md and worth mentioning in interviews.
 */
public class NavImportedEvent extends ApplicationEvent {

    private final NavHistory navHistory;

    public NavImportedEvent(Object source, NavHistory navHistory) {
        super(source);
        this.navHistory = navHistory;
    }

    /**
     * The NavHistory record that was just imported.
     * Listeners use this to know which scheme/date was imported and
     * filter their work accordingly (e.g. only process SIPs for this scheme).
     */
    public NavHistory getNavHistory() {
        return navHistory;
    }
}
