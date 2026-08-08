package com.mfplatform.mfplatform.sip;

import com.mfplatform.mfplatform.aspect.Auditable;
import com.mfplatform.mfplatform.common.BusinessDateService;
import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.security.CurrentUserResolver;
import com.mfplatform.mfplatform.sip.dto.SipDtos.*;
import com.mfplatform.mfplatform.transaction.validation.TransactionValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * SipMandateService handles all SIP mandate lifecycle operations.
 *
 * REGISTRATION:
 * Validates folio ownership, checks for duplicate active mandates on the
 * same folio/scheme, generates a simulated mandate reference, and creates
 * the SipMandate row. No transaction is created at this point — the first
 * installment transaction is created by the SIP batch job (SipItemProcessor)
 * once nextDueDate is reached, and settled by EodProcessingService.
 *
 * LIFECYCLE OPERATIONS (pause/resume/cancel):
 * Each delegates to the corresponding domain method on SipMandate.
 * Ownership is verified before any mutation — an investor can only
 * manage their own mandates; a distributor can only manage their clients'.
 *
 * LISTING:
 * Same role-scoped pattern as FolioService and TransactionService —
 * one endpoint, different query based on actor role.
 */
@Service
public class SipMandateService {

    private final SipMandateRepository sipMandateRepository;
    private final FolioRepository folioRepository;
    private final CurrentUserResolver currentUserResolver;
    private final OwnershipValidator ownershipValidator;
    private final BusinessDateService businessDateService;

    public SipMandateService(
            SipMandateRepository sipMandateRepository,
            FolioRepository folioRepository,
            CurrentUserResolver currentUserResolver,
            OwnershipValidator ownershipValidator,
            BusinessDateService businessDateService) {
        this.sipMandateRepository = sipMandateRepository;
        this.folioRepository = folioRepository;
        this.currentUserResolver = currentUserResolver;
        this.ownershipValidator = ownershipValidator;
        this.businessDateService = businessDateService;
    }

    // ─── Registration ─────────────────────────────────────────────────────────

    /**
     * Registers a new SIP mandate.
     *
     * Validations:
     *   1. Folio exists and caller has access to it
     *   2. No active SIP already exists for this folio/scheme pair
     *   3. startDate is not in the past
     *   4. endDate (if provided) is after startDate
     *   5. sipDay is present (1-31) when frequency is MONTHLY, and absent
     *      otherwise — WEEKLY/QUARTERLY use fixed calendar anchors, no
     *      user-chosen day
     *
     * Generates a simulated mandate reference (NACH-style reference number).
     * nextDueDate is the nearest schedule anchor on or after startDate —
     * see SipMandate.computeFirstDueDate().
     */
    @Auditable(operation = "SIP_REGISTRATION")
    @Transactional
    public SipMandateResponse register(RegisterSipRequest request, Authentication auth) {
        ActorContext actor = currentUserResolver.resolve(auth);

        // Validate folio ownership
        assertCanAccessFolio(request.folioId(), actor);

        // Validate start date
        if (request.startDate().isBefore(businessDateService.today())) {
            throw new TransactionValidationException(
                    "SIP start date cannot be in the past.");
        }

        // Validate end date (if provided)
        if (request.endDate() != null &&
                !request.endDate().isAfter(request.startDate())) {
            throw new TransactionValidationException(
                    "SIP end date must be after the start date.");
        }

        // Validate sipDay: required (and 1-31) for MONTHLY, must be absent for
        // WEEKLY/QUARTERLY. The @Min/@Max on the DTO only fire via the
        // controller's @Valid pipeline — this service is the source of truth
        // for the business rule itself, same as the units/amount XOR check
        // in RedemptionService, so it re-checks the range explicitly rather
        // than trusting the annotation alone.
        if (request.frequency() == SipFrequency.MONTHLY) {
            if (request.sipDay() == null) {
                throw new TransactionValidationException(
                        "sipDay is required for MONTHLY SIPs (the day of the month deductions land on).");
            }
            if (request.sipDay() < 1 || request.sipDay() > 31) {
                throw new TransactionValidationException(
                        "sipDay must be between 1 and 31.");
            }
        }
        // Don't trust a client-supplied sipDay for frequencies that shouldn't have one —
        // WEEKLY/QUARTERLY always use their fixed anchors regardless of what was sent.
        Integer sipDay = request.frequency() == SipFrequency.MONTHLY ? request.sipDay() : null;

        // Prevent duplicate active SIP for same folio/scheme
        if (sipMandateRepository.existsActiveMandateForFolioAndScheme(
                request.folioId(), request.schemeId())) {
            throw new TransactionValidationException(
                    "An active SIP already exists for this folio and scheme. " +
                    "Cancel the existing SIP before registering a new one.");
        }

        LocalDate firstDueDate = SipMandate.computeFirstDueDate(
                request.startDate(), request.frequency(), sipDay);

        SipMandate mandate = sipMandateRepository.save(
                SipMandate.builder()
                        .folioId(request.folioId())
                        .schemeId(request.schemeId())
                        .amount(request.amount())
                        .frequency(request.frequency())
                        .startDate(request.startDate())
                        .endDate(request.endDate())
                        .sipDay(sipDay)
                        .nextDueDate(firstDueDate)
                        .status(SipMandateStatus.ACTIVE)
                        .mandateReference(generateMandateReference())
                        .initiatedByUserId(actor.userId())
                        .createdAt(Instant.now())
                        .build()
        );

        return toResponse(mandate);
    }

    // ─── Lifecycle operations ─────────────────────────────────────────────────

    /**
     * Pauses an active SIP. Reversible — investor can resume later.
     * SipItemReader's due-mandate query only selects ACTIVE mandates, so
     * paused ones are skipped by the batch.
     * The schedule continues normally — nextDueDate still advances — so
     * resuming picks up on the correct date.
     */
    @Transactional
    public SipMandateResponse pause(Long mandateId, Authentication auth) {
        SipMandate mandate = findAndVerifyOwnership(mandateId, auth);
        mandate.pause();
        return toResponse(sipMandateRepository.save(mandate));
    }

    /**
     * Resumes a paused SIP.
     */
    @Transactional
    public SipMandateResponse resume(Long mandateId, Authentication auth) {
        SipMandate mandate = findAndVerifyOwnership(mandateId, auth);
        mandate.resume();
        return toResponse(sipMandateRepository.save(mandate));
    }

    /**
     * Permanently cancels a SIP. Irreversible.
     * In a real system, the bank mandate (NACH) would also need to be cancelled
     * via the payment network. We simulate this by just updating the status.
     */
    @Auditable(operation = "SIP_CANCEL")
    @Transactional
    public SipMandateResponse cancel(Long mandateId, Authentication auth) {
        SipMandate mandate = findAndVerifyOwnership(mandateId, auth);
        mandate.cancel();
        return toResponse(sipMandateRepository.save(mandate));
    }

    // ─── Listing ──────────────────────────────────────────────────────────────

    /**
     * Returns SIP mandates scoped to the caller's role.
     * Same pattern as GET /transactions/my and GET /folios.
     */
    public Page<SipMandateResponse> getMySipMandates(Authentication auth, Pageable pageable) {
        ActorContext actor = currentUserResolver.resolve(auth);

        return switch (actor.role()) {
            case ADMIN ->
                sipMandateRepository.findAll(pageable).map(this::toResponse);

            case INVESTOR -> {
                List<Long> folioIds = folioRepository
                        .findByInvestorId(actor.investorId(), Pageable.unpaged())
                        .map(f -> f.getId())
                        .toList();
                yield sipMandateRepository
                        .findByFolioIdIn(folioIds, pageable)
                        .map(this::toResponse);
            }

            case DISTRIBUTOR -> {
                List<Long> folioIds = folioRepository
                        .findByInvestorDistributorId(actor.distributorId(), Pageable.unpaged())
                        .map(f -> f.getId())
                        .toList();
                yield sipMandateRepository
                        .findByFolioIdIn(folioIds, pageable)
                        .map(this::toResponse);
            }
        };
    }

    public SipMandateResponse getById(Long id) {
        return sipMandateRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new SipMandateNotFoundException(id));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Loads a mandate and verifies the caller has ownership of its folio.
     * Used before any mutation (pause/resume/cancel).
     */
    private SipMandate findAndVerifyOwnership(Long mandateId, Authentication auth) {
        ActorContext actor = currentUserResolver.resolve(auth);

        SipMandate mandate = sipMandateRepository.findById(mandateId)
                .orElseThrow(() -> new SipMandateNotFoundException(mandateId));

        assertCanAccessFolio(mandate.getFolioId(), actor);
        return mandate;
    }

    /**
     * Asserts the actor has access to the given folio.
     * Delegates to OwnershipValidator — same rule as FolioSecurity.
     */
    private void assertCanAccessFolio(Long folioId, ActorContext actor) {
        boolean hasAccess = switch (actor.role()) {
            case ADMIN -> true;
            case INVESTOR -> ownershipValidator.isInvestorFolio(folioId, actor.investorId());
            case DISTRIBUTOR -> ownershipValidator.isDistributorFolio(folioId, actor.distributorId());
        };

        if (!hasAccess) {
            throw new AccessDeniedException(
                    "You do not have permission to manage SIP mandates for this folio.");
        }
    }

    /**
     * Generates a simulated NACH mandate reference number.
     * Format: NACH-XXXXXXXXXXXXXXXX (16 hex chars).
     * In a real system this is returned by the bank after mandate registration.
     */
    private String generateMandateReference() {
        return "NACH-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 16)
                .toUpperCase();
    }

    private SipMandateResponse toResponse(SipMandate mandate) {
        return new SipMandateResponse(
                mandate.getId(),
                mandate.getFolioId(),
                mandate.getSchemeId(),
                mandate.getAmount(),
                mandate.getFrequency().name(),
                mandate.getStartDate(),
                mandate.getEndDate(),
                mandate.getNextDueDate(),
                mandate.getStatus().name(),
                mandate.getMandateReference(),
                mandate.getCreatedAt(),
                buildScheduleDescription(mandate)
        );
    }

    /**
     * Human-readable summary of the recurring schedule — computed once here
     * so the frontend renders it verbatim with no frequency-specific display
     * logic of its own.
     *
     * WEEKLY/QUARTERLY use fixed anchors, so their text is constant.
     * MONTHLY interpolates the mandate's actual sipDay with the correct
     * ordinal suffix (1st, 2nd, 3rd, 4th... 21st, 22nd, 23rd...).
     */
    private String buildScheduleDescription(SipMandate mandate) {
        return switch (mandate.getFrequency()) {
            case MONTHLY -> "Deducted on the " + ordinal(mandate.getSipDay()) + " of each month";
            case WEEKLY -> "Deducted on the 7th, 14th, 21st, and 28th of each month";
            case QUARTERLY -> "Deducted on the 8th of January, April, July, and October";
        };
    }

    /**
     * English ordinal suffix for a day-of-month number.
     * 11th/12th/13th are always "th" regardless of their last digit — the
     * usual exception to the "last digit decides" rule (1st, 2nd, 3rd, else th).
     */
    private String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}
