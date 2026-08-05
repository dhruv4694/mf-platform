package com.mfplatform.mfplatform.notification.event;

import com.mfplatform.mfplatform.distributor.Distributor;
import com.mfplatform.mfplatform.investor.Investor;
import org.springframework.context.ApplicationEvent;

/**
 * Application events published by AuthService and DistributorVerificationWorker.
 * NotificationService listens for these and dispatches notifications.
 *
 * WHY ApplicationEvent (not a plain record/class):
 * Spring's @TransactionalEventListener requires events to extend ApplicationEvent
 * (or be published via ApplicationEventPublisher which wraps plain objects).
 * Using ApplicationEvent directly is more explicit about intent.
 *
 * WHY @TransactionalEventListener(AFTER_COMMIT) (not @EventListener):
 * These events are published inside @Transactional methods in AuthService.
 * If we used @EventListener, the notification listener would fire BEFORE
 * the investor/distributor row is committed — the row might not yet be
 * visible to queries in the notification handler (another thread, another
 * transaction). AFTER_COMMIT guarantees the DB write is durable before
 * the notification is sent.
 */
public class ApplicationEvents {

    /**
     * Published by AuthService.signupInvestor() after the investor and
     * user_account rows are committed.
     */
    public static class InvestorSignedUpEvent extends ApplicationEvent {
        private final Investor investor;

        public InvestorSignedUpEvent(Object source, Investor investor) {
            super(source);
            this.investor = investor;
        }

        public Investor getInvestor() { return investor; }
    }

    /**
     * Published by DistributorVerificationWorker.activateAfterVerification()
     * after the distributor's status is set to ACTIVE and committed.
     */
    public static class DistributorActivatedEvent extends ApplicationEvent {
        private final Distributor distributor;

        public DistributorActivatedEvent(Object source, Distributor distributor) {
            super(source);
            this.distributor = distributor;
        }

        public Distributor getDistributor() { return distributor; }
    }

    /**
     * Published by AuthService.signupDistributor() after the distributor and
     * user_account rows are committed (status = PENDING_VERIFICATION).
     */
    public static class DistributorSignedUpEvent extends ApplicationEvent {
        private final Distributor distributor;

        public DistributorSignedUpEvent(Object source, Distributor distributor) {
            super(source);
            this.distributor = distributor;
        }

        public Distributor getDistributor() { return distributor; }
    }
}
