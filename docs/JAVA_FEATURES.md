# Java Features Used

This document covers every Java language feature deliberately used in this project,
why it was chosen, and where to find it in the codebase.

---

## 1. Records (Java 16+)

### What they are
Records are immutable data carriers. A record automatically generates:
- A constructor taking all fields
- Getters for all fields (named `fieldName()`, not `getFieldName()`)
- `equals()`, `hashCode()`, and `toString()` implementations

### Why we use them
DTOs and value objects are the perfect fit — they carry data, nothing else.
Using records instead of regular classes eliminates boilerplate and makes
immutability the default. You can't accidentally mutate a DTO mid-request.

### Where in this project
Every DTO in every `dto/` package:
```java
// auth/dto/AuthDtos.java
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {}

public record LoginResponse(
        String accessToken,
        String refreshToken
) {}
```

```java
// security/ActorContext.java
public record ActorContext(
        Long userId,
        Role role,
        Long investorId,
        Long distributorId
) {}
```

### Interview talking point
"I used records for all DTOs because DTOs should be immutable — once you build
a request object, nothing should be changing its fields mid-flight. Records
enforce that structurally rather than by convention."

---

## 2. Sealed Interfaces + Pattern Matching (Java 17)

### What they are
A sealed interface restricts which classes can implement it. Combined with
pattern matching in switch expressions, this gives you exhaustive type checking —
the compiler forces you to handle every possible subtype.

### Why we use them
`AllotmentResult` — the outcome of a unit allotment attempt — can only be one
of three things: success, failure, or pending. A sealed interface expresses
this constraint at the type level. The compiler will flag it if a new subtype
is added but not handled in a switch.

### Where in this project
```java
// transaction/AllotmentResult.java
public sealed interface AllotmentResult
        permits AllotmentResult.Success,
                AllotmentResult.Failed,
                AllotmentResult.Pending {

    record Success(BigDecimal allottedUnits, BigDecimal applicableNav) implements AllotmentResult {}
    record Failed(String reason) implements AllotmentResult {}
    record Pending(String reason) implements AllotmentResult {}
}
```

```java
// Used in UnitAllotmentService with pattern matching switch:
AllotmentResult result = unitAllotmentService.allot(transaction);

String message = switch (result) {
    case AllotmentResult.Success s ->
        "Allotted " + s.allottedUnits() + " units at NAV " + s.applicableNav();
    case AllotmentResult.Failed f ->
        "Allotment failed: " + f.reason();
    case AllotmentResult.Pending p ->
        "Allotment pending: " + p.reason();
    // No default needed — compiler verifies all subtypes are handled
};
```

### Interview talking point
"The exhaustive switch is the key benefit — if we ever add a fourth outcome
type, every switch on AllotmentResult becomes a compile error until it's handled.
This is much safer than an enum with a default branch that silently ignores
new values."

---

## 3. Switch Expressions (Java 14+)

### What they are
Switch expressions return a value (unlike switch statements which just execute
branches). Combined with arrow cases, they're more concise and don't have
fall-through bugs.

### Why we use them
Role-based routing appears throughout the codebase. Switch expressions make
the intent clear: "given this role, produce this value."

### Where in this project
```java
// FolioService.java — role-based query selection
Page<Folio> page = switch (actor.role()) {
    case ADMIN       -> folioRepository.findAll(pageable);
    case DISTRIBUTOR -> folioRepository.findByInvestorDistributorId(actor.distributorId(), pageable);
    case INVESTOR    -> folioRepository.findByInvestorId(actor.investorId(), pageable);
};
```

```java
// FolioService.java — role-based target investor determination
Long targetInvestorId = switch (actor.role()) {
    case INVESTOR    -> actor.investorId();
    case ADMIN       -> request.investorId();
    case DISTRIBUTOR -> {
        ownershipValidator.assertIsDistributorClient(request.investorId(), actor.distributorId());
        yield request.investorId();  // yield returns a value from a block case
    }
};
```

### The `yield` keyword
In a block case (with `{}`), `yield` is used to return the value, like `return`
is used inside a method.

### Interview talking point
"Switch expressions over enums are exhaustive by default — the compiler tells
you if you haven't handled a case. In a role-based system where adding a role
should immediately surface every place that needs updating, this is valuable."

---

## 4. Text Blocks (Java 15+)

### What they are
Multi-line string literals delimited by `"""`. Preserves formatting without
concatenation or escape sequences.

### Where in this project
JPQL queries in repositories:
```java
// MfTransactionRepository.java
@Query("""
    update MfTransaction t
       set t.status = :newStatus
     where t.id = :transactionId
       and t.status = :expectedStatus
    """)
int conditionalStatusUpdate(
        @Param("transactionId") Long transactionId,
        @Param("expectedStatus") TransactionStatus expectedStatus,
        @Param("newStatus") TransactionStatus newStatus);
```

Much more readable than:
```java
@Query("update MfTransaction t set t.status = :newStatus " +
       "where t.id = :transactionId and t.status = :expectedStatus")
```

---

## 5. `Optional` (Java 8, used properly here)

### What it is
A container that may or may not hold a value. Forces the caller to explicitly
handle the "not present" case.

### How we use it — and how NOT to use it
The wrong way (just replaces a null check):
```java
Optional<Investor> opt = investorRepository.findById(id);
if (opt.isPresent()) {
    return opt.get();
}
throw new InvestorNotFoundException(id);
```

The right way (functional chain):
```java
return investorRepository.findById(id)
        .map(this::toResponse)                              // transform if present
        .orElseThrow(() -> new InvestorNotFoundException(id)); // handle absent
```

### Where in this project
Every service `getById()` method, every repository lookup that might return
nothing, and `OwnershipValidator`:
```java
public boolean isDistributorClient(Long investorId, Long distributorId) {
    return investorRepository.findById(investorId)
            .map(inv -> distributorId.equals(inv.getDistributorId()))
            .orElse(false);  // unknown investor = no access
}
```

### Interview talking point
"`orElse(false)` as a default is a deliberate security decision — if the entity
doesn't exist, we deny access rather than throwing an exception that leaks
the fact that the entity doesn't exist."

---

## 6. Stream API (Java 8+)

### What it is
Functional-style operations on collections: `map`, `filter`, `reduce`,
`collect`, `toList()`, etc.

### Where in this project
DTO mapping in services:
```java
return navHistoryRepository
        .findBySchemeIdOrderByNavDateAsc(schemeId)
        .stream()
        .map(this::toResponse)
        .toList();  // Java 16+ — unmodifiable list, no Collectors.toList() needed
```

Portfolio aggregation (Phase 6 — coming soon):
```java
// Group holdings by scheme category, sum invested amount per category
Map<SchemeCategory, BigDecimal> investedByCategory = holdings.stream()
        .collect(Collectors.groupingBy(
                h -> h.getScheme().getCategory(),
                Collectors.reducing(BigDecimal.ZERO,
                        HoldingView::getInvestedAmount,
                        BigDecimal::add)
        ));
```

---

## 7. `BigDecimal` for Financial Arithmetic

### Why NOT `double` or `float`
```java
double result = 0.1 + 0.2;
System.out.println(result); // prints 0.30000000000000004 — NOT 0.3
```

Binary floating point cannot represent most decimal fractions exactly.
For financial calculations, this is unacceptable — rounding errors compound
across millions of transactions.

### How we use it
```java
// NavHistory.java
@Column(precision = 12, scale = 4)
private BigDecimal navValue;  // exact decimal, 4 places

// UnitAllotmentService.java
BigDecimal allottedUnits = requestAmount
        .divide(applicableNav, 4, RoundingMode.HALF_UP);
        //                     ^   ^
        //                     |   always explicit rounding mode
        //                     4 decimal places (AMFI standard)
```

### Interview talking point
"I used `BigDecimal` throughout for all monetary and unit values with explicit
`RoundingMode.HALF_UP` to match AMFI's published rounding convention for
mutual fund unit allotment."

---

## 8. `@Transactional` and Transaction Propagation

### What it does
Wraps a method in a database transaction. If anything throws an unchecked
exception inside the method, every DB write in that method is rolled back.

### Where the design decision matters
```java
// AuthService.java
@Transactional
public LoginResponse signupInvestor(InvestorSignupRequest request) {
    Investor investor = investorService.createInvestor(...);  // INSERT investor
    userService.createInvestorAccount(investor.getId(), ...); // INSERT user_account
    return login(...);
}
```

If `createInvestorAccount()` fails (duplicate username), the `investor` INSERT
is rolled back. Without `@Transactional`, you'd have an investor row with no
login account — orphaned data.

### Propagation
Both `investorService.createInvestor()` and `userService.createInvestorAccount()`
are also `@Transactional`. Spring's default propagation is `REQUIRED` — they
join the existing transaction started by `AuthService.signupInvestor()` rather
than starting new ones. There's always exactly one transaction for the whole
operation.

---

## 9. Lombok Annotations

### What we use and why

| Annotation | What it generates | Why |
|---|---|---|
| `@Getter` | Getter for every field | Entities need getters; avoids boilerplate |
| `@Builder` | Builder pattern constructor | Clean multi-field object construction |
| `@NoArgsConstructor` | No-arg constructor | Required by JPA for entity instantiation |
| `@AllArgsConstructor` | All-args constructor | Required by `@Builder` to work correctly |

### Why NOT `@Setter` on entities
Entities use `@Builder` but not `@Setter`. Making entities immutable-by-default
means you can always understand an entity's state from its builder call —
no hidden setter calls somewhere else in the code.

For updates, we rebuild using the builder:
```java
// SchemeService.java
Scheme updated = Scheme.builder()
        .id(existing.getId())          // preserve id
        .schemeName(request.schemeName())
        .schemeCode(existing.getSchemeCode())  // preserve immutable field
        .category(request.category())
        .cutoffTime(request.cutoffTime())
        .build();
```

---

## 10. `instanceof` Pattern Matching (Java 16+)

### What it is
Combines `instanceof` check with variable binding in one expression —
no separate cast needed.

### Where in this project
```java
// Every Security bean (FolioSecurity, InvestorSecurity, DistributorSecurity)
public boolean canView(Long id, Authentication authentication) {
    if (!(authentication.getPrincipal() instanceof ActorContext actor)) {
        return false;
        // 'actor' is bound here — no separate cast to (ActorContext) needed
    }
    return switch (actor.role()) { ... };
}
```

Without pattern matching:
```java
if (!(authentication.getPrincipal() instanceof ActorContext)) {
    return false;
}
ActorContext actor = (ActorContext) authentication.getPrincipal(); // redundant cast
```

---

## 11. `Map.of()` — Immutable Maps (Java 9+)

### Where in this project
```java
// NavEligibilityService.java
this.strategies = Map.of(
        SchemeCategory.EQUITY, equityStrategy,
        SchemeCategory.HYBRID, equityStrategy,
        SchemeCategory.DEBT,   debtStrategy
);
```

`Map.of()` returns an unmodifiable map — no one can add or remove strategy
mappings at runtime. The strategy registry is fixed at construction time,
which is exactly what we want.

---

## 12. `var` — Local Variable Type Inference (Java 10+)

### What it is
`var` lets the compiler infer the type of a local variable from the right-hand
side of the assignment. It's purely a compile-time feature — the bytecode is
identical to using the explicit type.

### Where in this project
Used sparingly — only where the type is obvious from context and `var` reduces
visual noise:
```java
// AuthService.java
var claims = jwtService.parseClaims(request.refreshToken());
// Claims type is obvious from the method name — var is fine here

// NOT used where it reduces clarity:
var page = folioRepository.findAll(pageable);
// Page<Folio>? Page<FolioResponse>? Explicit type is clearer here
```

### Interview talking point
"`var` is a readability tool, not a shortcut. I use it where the type is
immediately obvious from the right-hand side, and avoid it where it would
make the reader wonder what type they're dealing with."
