-- V1__init_schema.sql
-- Initial schema for the Mutual Fund Platform

CREATE TABLE distributor (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(150)    NOT NULL,
    arn_code        VARCHAR(20)     NOT NULL UNIQUE,
    email           VARCHAR(150)    NOT NULL UNIQUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE TABLE investor (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(150)    NOT NULL,
    email           VARCHAR(150)    NOT NULL UNIQUE,
    pan_number      VARCHAR(10)     NOT NULL UNIQUE,
    distributor_id  BIGINT          REFERENCES distributor(id),  -- nullable: null = direct investment
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE TABLE folio (
    id              BIGSERIAL PRIMARY KEY,
    folio_number    VARCHAR(30)     NOT NULL UNIQUE,
    investor_id     BIGINT          NOT NULL REFERENCES investor(id),
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE TABLE scheme (
    id              BIGSERIAL PRIMARY KEY,
    scheme_name     VARCHAR(200)    NOT NULL,
    scheme_code     VARCHAR(30)     NOT NULL UNIQUE,
    category        VARCHAR(20)     NOT NULL CHECK (category IN ('EQUITY', 'DEBT', 'HYBRID')),
    cutoff_time     TIME            NOT NULL
);

CREATE TABLE nav_history (
    id              BIGSERIAL PRIMARY KEY,
    scheme_id       BIGINT          NOT NULL REFERENCES scheme(id),
    nav_date        DATE            NOT NULL,
    nav_value       NUMERIC(12,4)   NOT NULL CHECK (nav_value > 0),
    UNIQUE (scheme_id, nav_date)
);

CREATE TABLE sip_mandate (
    id                  BIGSERIAL PRIMARY KEY,
    folio_id            BIGINT          NOT NULL REFERENCES folio(id),
    scheme_id           BIGINT          NOT NULL REFERENCES scheme(id),
    amount              NUMERIC(12,2)   NOT NULL CHECK (amount > 0),
    frequency           VARCHAR(20)     NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'QUARTERLY')),
    start_date          DATE            NOT NULL,
    end_date            DATE,
    next_due_date       DATE            NOT NULL,
    status              VARCHAR(20)     NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'COMPLETED')),
    mandate_reference   VARCHAR(50)     NOT NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE TABLE user_account (
    id                  BIGSERIAL PRIMARY KEY,
    investor_id         BIGINT          REFERENCES investor(id),      -- set only for ROLE_INVESTOR
    distributor_id      BIGINT          REFERENCES distributor(id),   -- set only for ROLE_DISTRIBUTOR
    username            VARCHAR(100)    NOT NULL UNIQUE,
    password_hash       VARCHAR(255)    NOT NULL,
    role                VARCHAR(20)     NOT NULL CHECK (role IN ('INVESTOR', 'DISTRIBUTOR', 'ADMIN')),
    created_at          TIMESTAMP       NOT NULL DEFAULT now(),
    CHECK (
        (role = 'INVESTOR'    AND investor_id IS NOT NULL AND distributor_id IS NULL) OR
        (role = 'DISTRIBUTOR' AND distributor_id IS NOT NULL AND investor_id IS NULL) OR
        (role = 'ADMIN'       AND investor_id IS NULL AND distributor_id IS NULL)
    )
);

CREATE TABLE transaction (
    id                      BIGSERIAL PRIMARY KEY,
    folio_id                BIGINT          NOT NULL REFERENCES folio(id),
    scheme_id               BIGINT          NOT NULL REFERENCES scheme(id),
    type                    VARCHAR(20)     NOT NULL CHECK (type IN ('PURCHASE', 'REDEMPTION')),
    status                  VARCHAR(20)     NOT NULL CHECK (status IN ('PENDING', 'NAV_APPLIED', 'ALLOTTED', 'FAILED', 'REVERSED')),
    request_amount          NUMERIC(12,2),
    request_units           NUMERIC(12,4),
    applicable_nav_id       BIGINT          REFERENCES nav_history(id),
    allotted_units          NUMERIC(12,4),
    idempotency_key         VARCHAR(100)    NOT NULL UNIQUE,
    sip_mandate_id          BIGINT          REFERENCES sip_mandate(id),  -- set only for SIP-originated purchases
    initiated_by_user_id    BIGINT          NOT NULL REFERENCES user_account(id),
    initiated_by_role       VARCHAR(20)     NOT NULL CHECK (initiated_by_role IN ('INVESTOR', 'DISTRIBUTOR', 'ADMIN')),
    requested_at            TIMESTAMP       NOT NULL DEFAULT now(),
    processed_at            TIMESTAMP,
    reversal_of_id          BIGINT          REFERENCES transaction(id),
    CHECK (
        (type = 'PURCHASE'   AND request_amount IS NOT NULL) OR
        (type = 'REDEMPTION' AND request_units IS NOT NULL)
    )
);

CREATE TABLE holding (
    id              BIGSERIAL PRIMARY KEY,
    folio_id        BIGINT          NOT NULL REFERENCES folio(id),
    scheme_id       BIGINT          NOT NULL REFERENCES scheme(id),
    units_held      NUMERIC(12,4)   NOT NULL DEFAULT 0,
    version         BIGINT          NOT NULL DEFAULT 0,  -- optimistic locking column (@Version)
    UNIQUE (folio_id, scheme_id)
);

CREATE TABLE payment (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  BIGINT          NOT NULL REFERENCES transaction(id),
    status          VARCHAR(20)     NOT NULL CHECK (status IN ('INITIATED', 'REALIZED', 'FAILED')),
    realized_at     TIMESTAMP
);

CREATE TABLE notification (
    id              BIGSERIAL PRIMARY KEY,
    investor_id     BIGINT          NOT NULL REFERENCES investor(id),
    transaction_id  BIGINT          REFERENCES transaction(id),
    message         VARCHAR(500)    NOT NULL,
    sent_at         TIMESTAMP       NOT NULL DEFAULT now()
);

-- Indexes for the query patterns we already know we need
CREATE INDEX idx_folio_investor_id ON folio(investor_id);
CREATE INDEX idx_investor_distributor_id ON investor(distributor_id);
CREATE INDEX idx_transaction_folio_id ON transaction(folio_id);
CREATE INDEX idx_transaction_status ON transaction(status);
CREATE INDEX idx_sip_mandate_next_due_date ON sip_mandate(next_due_date) WHERE status = 'ACTIVE';
