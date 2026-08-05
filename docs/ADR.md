# Architecture Decision Records (ADR)

An ADR documents a significant architectural decision: the context, the decision,
the alternatives considered, and the trade-offs accepted.

Having this file in your repo is itself a portfolio signal — it shows you think
about design deliberately and can articulate trade-offs, not just write code.

---

## ADR-001: Immutable, Append-Only Transaction Ledger

**Status:** Accepted

**Context:**
Financial systems need a reliable audit trail. Transactions represent real money
movements. We need to know not just the current state, but every state a
transaction has been in and when.

**Decision:**
The `mf_transaction` table is append-only and immutable:
- Rows are INSERT-only. `request_amount`, `folio_id`, `scheme_id`, and
  `idempotency_key` are set at creation and never updated.
- Only `status`, `applicable_nav_id`, `allotted_units`, and `processed_at`
  are updated as the transaction progresses through its pipeline.
- Corrections and reversals are new rows with `reversal_of_id` pointing to
  the original — never edits to the original row.
- `@Version` provides optimistic locking as a safety net against concurrent
  accidental edits.

**Alternatives considered:**
- Mutable transactions: simpler to implement, but loses the audit trail.
  "Who changed this and when?" becomes unanswerable.
- Event sourcing (store only events, derive state): stronger audit, but
  significantly more complex — overkill for a portfolio project.

**Trade-offs:**
- Storage grows unbounded (every correction is a new row). Acceptable for
  a portfolio project; in production, archival policies would manage this.
- Reading "current state" requires checking `reversal_of_id` to exclude
  reversed transactions. Slightly more complex queries.

---

## ADR-002: `holding` as a Derived Table

**Status:** Accepted

**Context:**
The application needs to quickly answer "how many units does this folio hold
in this scheme?" Summing all transactions every time would be expensive.

**Decision:**
`holding` stores the current unit balance per (folio, scheme) pair as a
running total, updated after each allotment or redemption.

**`holding` is NOT the source of truth — `mf_transaction` is.**
If `holding` were ever corrupted, it could be fully reconstructed by replaying
all `ALLOTTED` and `REVERSED` transactions.

**`holding` is protected by `@Version` (optimistic locking):**
When two concurrent allotments target the same folio/scheme, only one can
succeed. The other gets an `OptimisticLockException` and retries with the
updated balance.

**Calculated fields (never stored in `holding`):**
- `current_value` = `units_held` × latest NAV (changes every time a new NAV
  is imported — storing it would go stale immediately)
- `invested_amount` = derived from `mf_transaction` history
- `returns` = derived from `current_value` and `invested_amount`

**Alternatives considered:**
- Recalculate from transactions every time: correct, but O(n) per query where
  n = number of transactions. Unacceptably slow for investors with years of SIP history.
- Store calculated values (current_value, returns): would need updating on every
  NAV import for every holding — expensive batch job, stale data between imports.

---

## ADR-003: Two-Phase Transaction Claiming for Multi-Worker Safety

**Status:** Accepted

**Context:**
The application may run as multiple instances (horizontal scaling, or multiple
threads from the scheduler). Multiple workers might simultaneously attempt to
allot the same transaction.

**Decision:**
A two-phase approach:
1. **Claim phase** (compare-and-set): atomically move the transaction from
   `NAV_APPLIED` to `ALLOTMENT_IN_PROGRESS` using a conditional UPDATE with
   `WHERE status = 'NAV_APPLIED'`. Only one worker can win this.
2. **Work phase**: the winning worker updates `holding` and marks the
   transaction `ALLOTTED` — both in the same database transaction.

`@Version` on `mf_transaction` remains as a secondary safety net.

**Why not rely on `@Version` alone:**
`@Version` catches conflicts on the status UPDATE — but by that point, the
holding UPDATE may have already executed. A financial error could have occurred
before the conflict was detected.

The compare-and-set claim prevents any work from starting unless you
exclusively own the transaction. `@Version` catches any residual concurrency
that slips through.

**Alternatives considered:**
- Pessimistic locking (`SELECT FOR UPDATE`): works but holds a DB lock for
  the entire allotment duration — throughput bottleneck.
- Redis distributed lock: more infrastructure, adds operational complexity.
- Single-threaded processing: simplest, but eliminates horizontal scalability.

---

## ADR-004: Separate `user_account` from `investor`/`distributor`

**Status:** Accepted

**Context:**
The system has three roles: INVESTOR, DISTRIBUTOR, ADMIN. Initially it seemed
natural to add username/password fields to the `investor` table.

**Decision:**
`user_account` is a separate table. It holds login credentials and links to
either `investor` or `distributor` via nullable FKs. Admin accounts have no
linked business entity.

**Why:**
1. Not all business entities need login access. An investor could be onboarded
   offline with no system access initially.
2. Admin accounts exist with no corresponding investor or distributor.
3. A clean separation means `investor` represents a real person in the fund's
   books; `user_account` represents a system principal. Different concerns.

**Consequence:**
Creating an investor or distributor requires TWO inserts — one in the business
entity table, one in `user_account`. These are wrapped in a single `@Transactional`
method in `AuthService` (the orchestrator). If either insert fails, both roll back.

---

## ADR-005: NavEligibilityStrategy per Scheme Category

**Status:** Accepted

**Context:**
SEBI mandates different NAV cutoff rules for different scheme categories.
The rules are likely to evolve and new categories may be added.

**Decision:**
Each scheme category maps to a `NavEligibilityStrategy` implementation.
`NavEligibilityService` holds a `Map<SchemeCategory, NavEligibilityStrategy>`
and delegates to the appropriate strategy based on `scheme.getCategory()`.

**Consequence:**
Adding a new scheme category requires:
1. Adding the value to the `SchemeCategory` enum
2. Creating a new `XxxNavEligibilityStrategy` class
3. Adding one entry to the map in `NavEligibilityService`

Zero changes to any existing strategy or service.

**Update (post Business Date + EOD refactor):** Removed. Settlement moved out
of the request path into an explicit, admin-triggered EOD batch
(`EodProcessingService`) that resolves NAV by exact `(scheme, businessDate)`
match rather than a real-clock cutoff-time calculation. Under a virtual,
admin-advanced business date, the cutoff strategies would compute eligibility
from real-clock timestamps and could resolve to a NAV older than the one
actually published for the business date being settled — settling at a stale
price. Cutoff-time eligibility conceptually belongs at request time (deciding
which business date a transaction lands on), which this project doesn't model;
so rather than adapt the strategies to a scenario they were never designed
for, they were deleted along with `NavEligibilityService`, `Scheme.cutoffTime`,
and their tests. See `EodProcessingService`'s javadoc for the replacement NAV
resolution design.

---

## ADR-006: SIP as a Standing Instruction, Not a Transaction Type

**Status:** Accepted

**Context:**
SIP (Systematic Investment Plan) creates recurring purchases on a schedule.
It could be modelled as a special transaction type, or as a separate entity.

**Decision:**
SIP is a `sip_mandate` — a standing instruction that generates PURCHASE
transactions on schedule. SIP-originated transactions are regular PURCHASE
transactions with `sip_mandate_id` set.

**Why:**
1. A SIP isn't itself a transaction — it's an instruction to create transactions.
2. The purchase pipeline (validation → payment → NAV → allotment) is identical
   whether the purchase was investor-initiated or SIP-initiated. Reusing it
   avoids duplicating logic.
3. `sip_mandate_id` on the transaction table enables clear attribution:
   "this purchase was SIP-originated, here's the mandate."

**Consequence:**
`SipExecutionService` is a scheduled job that calls `PurchaseService` — it's
an origination path, not a separate processing path. A failed SIP installment
marks that transaction FAILED but still advances `next_due_date` on the mandate.

---

## ADR-007: Spring ApplicationEventPublisher over Kafka (for now)

**Status:** Accepted (with documented upgrade path)

**Context:**
After a NAV is imported, multiple things need to happen (SIP allotment trigger,
notifications, etc.). These consumers should be decoupled from NAV import.

**Decision:**
Use Spring's `ApplicationEventPublisher` (synchronous, in-process).

**Trade-offs accepted:**
- Synchronous: the NAV import endpoint waits for all listeners to finish before
  returning. For a small number of schemes/transactions, this is acceptable.
- In-process: if the app crashes mid-listener, the event is lost. No retry.
- No ordering guarantees across events.

**Upgrade path to Kafka (not built, documented for portfolio):**
If volume grew to thousands of schemes or millions of SIP mandates:
1. Replace `eventPublisher.publishEvent(...)` with a Kafka producer
2. Replace `@EventListener` with `@KafkaListener`
3. Business logic in listeners is unchanged

The fact that the publisher and listeners are already decoupled makes this
upgrade non-breaking for business logic.

---

## ADR-008: Distributor Onboarding is Admin-Only (No Self-Signup)

**Status:** Accepted

**Context:**
Investors can self-signup via `POST /auth/signup/investor`. Should distributors
have a similar public endpoint?

**Decision:**
No. Distributors are onboarded exclusively by ADMIN via `POST /distributors`.

**Why:**
AMFI (Association of Mutual Funds in India) issues ARN (AMFI Registration Number)
codes to registered distributors. A real fund house would verify a distributor's
ARN with AMFI before granting system access. This verification happens out-of-band
(phone, email, document submission). A public self-signup endpoint would bypass
this verification, allowing anyone to claim to be a distributor.

**Consequence:**
There is no `POST /auth/signup/distributor`. The `POST /distributors` endpoint
(ADMIN-only) IS the signup mechanism. This is documented in `AuthController`
with a comment explaining the reasoning.
