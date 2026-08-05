# Design Patterns Used

Every design pattern deliberately applied in this project, with the exact
motivation, implementation location, and interview talking points.

---

## 1. Chain of Responsibility Pattern

### Intent
Pass a request along a chain of handlers. Each handler decides whether to
handle the request or pass it to the next handler. Any handler can short-circuit
the chain.

### The problem it solves here
A transaction (purchase or redemption) must pass multiple independent validations
before processing. These validations differ between purchase and redemption,
and new validations may be added over time.

Without Chain of Responsibility:
```java
public void validatePurchase(PurchaseRequest request) {
    validateKyc(request);
    validateFolioOwnership(request);
    validateSchemeOpen(request);
    validateMinimumAmount(request);
    // adding a new validation = modifying this method
}
```

### Implementation

```
TransactionValidator            ← interface (each link in the chain)
    KycValidator                ← checks KYC status
    FolioOwnershipValidator     ← checks folio belongs to caller (shared)
    SchemeOpenForPurchaseValidator
    MinimumPurchaseAmountValidator
    SufficientUnitsValidator    ← redemption only
    LockInPeriodValidator       ← redemption only

PurchaseValidationChain         ← builds and runs the purchase chain
RedemptionValidationChain       ← builds and runs the redemption chain
```

```java
// TransactionValidator.java
public interface TransactionValidator {
    void validate(TransactionContext context); // throws ValidationException to stop chain
}

// KycValidator.java
@Component
public class KycValidator implements TransactionValidator {
    @Override
    public void validate(TransactionContext context) {
        if (!context.investor().isKycComplete()) {
            throw new TransactionValidationException("KYC not complete for investor");
        }
        // if we reach here, chain continues to next validator
    }
}

// PurchaseValidationChain.java
@Component
public class PurchaseValidationChain {
    private final List<TransactionValidator> validators;

    public PurchaseValidationChain(
            KycValidator kycValidator,
            FolioOwnershipValidator folioValidator,
            SchemeOpenForPurchaseValidator schemeValidator,
            MinimumPurchaseAmountValidator minAmountValidator) {
        // Order matters — KYC first (cheapest), DB queries last
        this.validators = List.of(
                kycValidator,
                folioValidator,
                schemeValidator,
                minAmountValidator
        );
    }

    public void validate(TransactionContext context) {
        validators.forEach(v -> v.validate(context));
    }
}
```

### Adding a new validation
1. Create `FraudCheckValidator implements TransactionValidator`
2. Add it as a constructor parameter in `PurchaseValidationChain`
3. Add it to the `List.of(...)` at the right position
4. Zero changes to existing validators

### Interview talking point
"The chain runs cheapest checks first — KYC is an in-memory check, ownership
requires a DB query, fraud check might call an external service. Fail fast on
cheap checks before incurring the cost of expensive ones."

---

## 2. State Pattern

### Intent
Allow an object to alter its behaviour when its internal state changes.
Encapsulate valid state transitions so invalid ones are impossible.

### The problem it solves here
A transaction moves through a lifecycle:
```
PENDING → PAYMENT_REALIZED → NAV_APPLIED → ALLOTMENT_IN_PROGRESS → ALLOTTED
                                                                  → FAILED
ALLOTTED → REVERSED
```

Without State pattern, transition logic is scattered:
```java
// In UnitAllotmentService:
if (transaction.getStatus() != NAV_APPLIED) throw new IllegalStateException(...);
transaction.setStatus(ALLOTMENT_IN_PROGRESS);

// In NotificationService:
if (transaction.getStatus() != ALLOTTED) throw new IllegalStateException(...);
```

Every service that touches a transaction has to know valid transitions. Adding
a new status means hunting through every service for `if (status == X)` checks.

### Implementation

```java
// TransactionStatus.java
public enum TransactionStatus {
    PENDING {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            if (next == PAYMENT_REALIZED || next == FAILED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },
    PAYMENT_REALIZED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            if (next == NAV_APPLIED || next == FAILED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },
    NAV_APPLIED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            if (next == ALLOTMENT_IN_PROGRESS || next == FAILED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },
    ALLOTMENT_IN_PROGRESS {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            if (next == ALLOTTED || next == FAILED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },
    ALLOTTED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            if (next == REVERSED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },
    FAILED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            throw new InvalidTransactionStateException(this, next);
            // Terminal state — no transitions allowed
        }
    },
    REVERSED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            throw new InvalidTransactionStateException(this, next);
            // Terminal state — no transitions allowed
        }
    };

    public abstract TransactionStatus transitionTo(TransactionStatus next);
}

// MfTransaction.java
public void transitionTo(TransactionStatus next) {
    this.status = this.status.transitionTo(next); // enum enforces validity
}
```

### Interview talking point
"The transition logic lives in the enum itself — each status knows its own
valid next states. If you try to move ALLOTTED → PENDING, you get an exception
at the enum level, not a subtle data corruption that surfaces later."

---

## 3. Builder Pattern

### Intent
Separate the construction of a complex object from its representation,
allowing the same construction process to create different representations.

### Implementation
Used via Lombok `@Builder` on all JPA entities:

```java
// Clean, readable construction — no positional constructor confusion
Investor investor = Investor.builder()
        .name(request.name())
        .email(request.email())
        .panNumber(request.panNumber())
        .distributorId(distributorId)
        .createdAt(Instant.now())
        .build();
```

vs. the positional constructor alternative:
```java
// What does the 4th argument mean? Need to check the class definition
new Investor(null, "John", "john@email.com", "ABCDE1234F", 3L, Instant.now());
```

### Why NOT `@Setter`
Entities use `@Builder` without `@Setter`. To update an entity, we rebuild it:
```java
Scheme updated = Scheme.builder()
        .id(existing.getId())           // from existing
        .schemeName(request.schemeName()) // from request
        .schemeCode(existing.getSchemeCode()) // from existing (immutable)
        .category(request.category())
        .build();
```

This makes it impossible to "accidentally forget" to set a field — the builder
gives you a complete new object every time.

---

## 4. Repository Pattern

### Intent
Encapsulate the logic required to access data sources behind a collection-like
interface. The rest of the application never writes SQL or JPA queries directly.

### Implementation
Spring Data JPA repositories: declare the query you want, Spring generates the
implementation.

```java
// InvestorRepository.java
public interface InvestorRepository extends JpaRepository<Investor, Long> {

    // Spring generates: SELECT * FROM investor WHERE distributor_id = ?
    Page<Investor> findByDistributorId(Long distributorId, Pageable pageable);

    // Spring generates: SELECT * FROM investor WHERE email = ?
    Optional<Investor> findByEmail(String email);

    // Custom JPQL for cross-table query
    @Query("select f from Folio f where f.investorId in " +
           "(select i.id from Investor i where i.distributorId = :distributorId)")
    Page<Folio> findByInvestorDistributorId(@Param("distributorId") Long distributorId, Pageable pageable);
}
```

### Interview talking point
"The repository pattern means services never contain SQL. If we switch from
PostgreSQL to another database, only the repository implementations need to
change — and with Spring Data JPA, we don't even write those implementations."

---

## 5. Observer Pattern (via Spring Events)

### Intent
Define a one-to-many dependency between objects so that when one object changes
state, all its dependents are notified and updated automatically.

### The problem it solves here
After a NAV is imported, other parts of the system may want to react — ops
notification, portfolio refresh, etc. — without `NavImportService` needing to
know who's listening.

Without Observer, `NavImportService` would call each downstream service directly:
```java
public void importNav(...) {
    navHistoryRepository.save(nav);
    notificationService.notifyOpsNavImported(nav);    // tight coupling
    portfolioService.refreshValues(nav);              // tight coupling
}
```

Every new consumer requires modifying `NavImportService`.

### Implementation

```java
// NavImportedEvent.java — the event (the notification)
public class NavImportedEvent extends ApplicationEvent {
    private final NavHistory navHistory;
    // ...
}

// NavImportService.java — the subject (publishes events)
eventPublisher.publishEvent(new NavImportedEvent(this, saved));
```

Note: this event previously had a listener — `SipExecutionService` used it to
allot pending SIP installments the moment a NAV they were waiting on landed.
That listener was removed when settlement moved to an explicit EOD batch
(`EodProcessingService`, triggered by an admin) instead of firing per-event —
see the ADR. `NavImportedEvent` is still published (harmless, and a reasonable
extension point for future consumers like ops notifications) but currently has
no listener.

### Interview talking point
"Spring's event system is synchronous and in-process — the listener runs in the
same thread and transaction as the publisher. For a portfolio project this is
fine and simple. In a real high-volume AMC system, you'd replace this with Kafka
so the NAV import endpoint returns immediately and allotment happens asynchronously.
That trade-off is documented in our ADR."

---

## 6. Template Method Pattern (implicit)

### Intent
Define the skeleton of an algorithm in a base class, deferring some steps to
subclasses.

### Where it appears (implicit, not a dedicated class)
The role-based query routing pattern is identical across all listing services:

```java
// Same skeleton in FolioService, InvestorService, TransactionService
Page<X> page = switch (actor.role()) {
    case ADMIN       -> repository.findAll(pageable);
    case DISTRIBUTOR -> repository.findByDistributorScope(actor.distributorId(), pageable);
    case INVESTOR    -> repository.findByInvestorScope(actor.investorId(), pageable);
};
return page.map(this::toResponse);
```

The template (switch on role → query → map to DTO) is the same everywhere.
Only the repository method names differ. This isn't a formal Template Method
implementation (no base class) but the pattern is present.

### Interview talking point
"This pattern emerging naturally across services was actually a signal — it
meant the role-scoped query logic was a cross-cutting concern that could be
extracted. In a larger system you'd consider a generic `RoleScopedRepository`
abstraction. For this project size, the repetition is acceptable and explicit."

---

## 7. Facade Pattern (implicit)

### Intent
Provide a simplified interface to a complex subsystem.

### Where it appears
`AuthService` is a facade over the investor/distributor/user creation subsystem:

```java
// The caller (InvestorController) sees one simple method:
authService.addInvestor(request, authentication);

// Behind the facade, AuthService orchestrates:
// 1. InvestorService.createInvestor()    → investor table
// 2. UserService.createInvestorAccount() → user_account table
// Both in one @Transactional boundary
```

`TransactionService` (when built) will be a facade over the full transaction
pipeline: validation → payment simulation → NAV eligibility → allotment → notification.

---

## 8. Aspect-Oriented Programming (AOP)

### Intent
Modularize cross-cutting concerns — logic that would otherwise be scattered
across many unrelated classes — into a single, reusable aspect.

### The problem it solves here
Two cross-cutting concerns existed before AOP was added:

**Distributor status enforcement:**
Before: `assertDistributorActive()` manually called in `InvestorController`,
`FolioController`, and `TransactionController` — same boilerplate in three places.
After: `@RequireActiveDistributor` annotation, enforced once in `DistributorStatusAspect`.

**Performance monitoring:**
Without AOP: add timing code to every service method — dozens of identical
`long start = System.currentTimeMillis()` blocks scattered everywhere.
With AOP: one `ExecutionTimeAspect` class handles all service methods via a
single pointcut expression.

### Two aspects implemented

**`@RequireActiveDistributor` + `DistributorStatusAspect`:**
```java
// Custom annotation — the marker
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireActiveDistributor {}

// Aspect — the enforcement
@Aspect @Component
public class DistributorStatusAspect {
    @Before("@annotation(RequireActiveDistributor)")
    public void checkDistributorActive(JoinPoint joinPoint) {
        ActorContext actor = (ActorContext) SecurityContextHolder
            .getContext().getAuthentication().getPrincipal();
        if (actor.role() == Role.DISTRIBUTOR) {
            distributorService.assertDistributorActive(actor.distributorId());
        }
    }
}

// Usage — just annotate the endpoint
@PostMapping
@PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
@RequireActiveDistributor  // aspect fires here automatically
public ResponseEntity<InvestorResponse> addInvestor(...) { ... }
```

**`ExecutionTimeAspect`:**
```java
@Aspect @Component
public class ExecutionTimeAspect {
    @Around("execution(* com.mfplatform.mfplatform..*Service.*(..))")
    public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= 500) log.warn("SLOW: {}.{}() took {}ms", ...);
            else log.debug("SERVICE: {}.{}() {}ms", ...);
        }
    }
}
```

### AOP terminology

| Term | What it means | Our example |
|---|---|---|
| **Aspect** | The class containing cross-cutting logic | `DistributorStatusAspect` |
| **Advice** | What the aspect does | `@Before` check, `@Around` timing |
| **Pointcut** | Where the advice applies | `@annotation(RequireActiveDistributor)` |
| **Join point** | A specific point in program execution | Method call to `addInvestor()` |
| **Weaving** | Applying aspects to targets | Spring does this at runtime via proxy |

### Execution order

```
HTTP request arrives
  → JwtAuthFilter (sets SecurityContext)          ← servlet filter
  → @PreAuthorize AOP proxy (role check)          ← Spring Security AOP (order 0)
  → @RequireActiveDistributor AOP proxy (status)  ← our aspect
  → ExecutionTimeAspect (starts timer)            ← our aspect
  → controller method body
  → ExecutionTimeAspect (stops timer, logs)
```

### Interview talking point
"@PreAuthorize and @Transactional are both AOP under the hood — Spring already
uses AOP extensively. Adding custom aspects for distributor status enforcement
was the natural next step: instead of copying the same assertDistributorActive()
call into every controller method, we declared it as a concern, gave it an
annotation, and let the aspect enforce it uniformly. The result is that adding
a new distributor-restricted endpoint just requires one annotation — no risk of
forgetting the manual check."


### Intent
Atomically update a row only if it's in an expected state. Only one concurrent
writer can succeed.

### The problem it solves here
Multiple worker instances (or scheduled job threads) might simultaneously try
to allot the same pending transaction. This must be prevented at the database
level, not just the application level.

### Implementation
```java
// MfTransactionRepository.java
@Modifying
@Query("""
    update MfTransaction t
       set t.status = :newStatus
     where t.id = :transactionId
       and t.status = :expectedStatus
    """)
int claimForAllotment(
        @Param("transactionId") Long transactionId,
        @Param("expectedStatus") TransactionStatus expectedStatus,
        @Param("newStatus") TransactionStatus newStatus);

// UnitAllotmentService.java
int claimed = transactionRepository.claimForAllotment(
        transactionId, NAV_APPLIED, ALLOTMENT_IN_PROGRESS);

if (claimed == 0) {
    return; // another worker already claimed it — exit safely
}
// Only reaches here if we won the claim
```

### Why this beats @Version alone
`@Version` catches the conflict AFTER the holding update might have already
happened. The compare-and-set claim prevents any work from starting unless
you exclusively own the transaction. `@Version` on `MfTransaction` remains
as a secondary safety net for unexpected concurrent edits.

### Interview talking point
"This is the same idea as a database-level mutex or Redis SETNX — claim
ownership atomically before doing any work. In distributed systems this pattern
appears everywhere: optimistic concurrency, leader election, job scheduling.
The key insight is that the WHERE clause in the UPDATE IS the lock."
