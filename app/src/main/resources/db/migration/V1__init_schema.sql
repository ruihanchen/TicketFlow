-- Cross-boundary FKs (orders→users, orders→ticket_types, inventories→ticket_types) are intentionally absent.
-- when inventory or order splits into its own service, there are no FK constraints to drop.

-- TIMESTAMPTZ throughout: TIMESTAMP causes silent drift when JVM timezone != DB timezone.

CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50) UNIQUE  NOT NULL,
    email         VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255)        NOT NULL,
    role          VARCHAR(20)         NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ         NOT NULL DEFAULT NOW()
);


CREATE TABLE events
(
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    venue           VARCHAR(200),
    event_date      TIMESTAMPTZ  NOT NULL,
    sale_start_time TIMESTAMPTZ  NOT NULL,
    sale_end_time   TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE ticket_types
(
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT         NOT NULL REFERENCES events (id),
    name        VARCHAR(100)   NOT NULL,
    price       DECIMAL(10, 2) NOT NULL CHECK (price > 0),
    --static snapshot; live counter is inventories.available_stock
    total_stock INT            NOT NULL CHECK (total_stock > 0),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

--hot write counter isolated from static metadata to avoid lock contention on reads.
CREATE TABLE inventories
(
    id              BIGSERIAL PRIMARY KEY,
    ticket_type_id  BIGINT UNIQUE NOT NULL,
    total_stock     INT           NOT NULL CHECK (total_stock > 0),

    --available_stock: live counter, decremented on purchase, restored on cancel/timeout.
    --total_stock: immutable ceiling;used in release() to guard against double-release overflow
    --and as the sold-count basis (total_stock - available_stock = sold).
    available_stock INT           NOT NULL,

    --@Version target for optimistic locking; kept for ConcurrentInventoryTest to benchmark against conditional UPDATE.
    version         INT           NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    --DB-level oversell guard; app layer applies this too,
    --but constraint survives a bug that bypasses service logic.
    CONSTRAINT chk_available_stock_non_negative CHECK (available_stock >= 0)
);

CREATE TABLE orders
(
    id             BIGSERIAL PRIMARY KEY,
    order_no       VARCHAR(64) UNIQUE NOT NULL,
    user_id        BIGINT             NOT NULL,
    ticket_type_id BIGINT             NOT NULL,
    quantity       INT                NOT NULL DEFAULT 1 CHECK (quantity > 0),

    --snapshot; ticket_types.price can change post-order.
    unit_price     DECIMAL(10, 2)     NOT NULL,
    total_amount   DECIMAL(10, 2)     NOT NULL,
    status         VARCHAR(20)        NOT NULL DEFAULT 'CREATED',

    --NOT NULL required: PostgreSQL UNIQUE allows multiple NULLs, it would silently break idempotency
    --if a null request_id ever slipped through.
    request_id     VARCHAR(64) UNIQUE NOT NULL,

    --NOT NULL: Order.create() always sets this; reaper query relies on expired_at < :now;
    --NULL rows would be invisible to the reaper permanently
    expired_at     TIMESTAMPTZ        NOT NULL,
    created_at     TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

CREATE TABLE order_status_history
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders (id),

    --nullable: allows a null→CREATED entry if we ever record order creation as a history event
    --current code starts history at the first transition, not at creation.
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,

    --PAYING → CANCELLED ha s 3 causes; from/to alone can't distinguish
    event       VARCHAR(50) NOT NULL,
    reason      VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_status ON events (status);
CREATE INDEX idx_ticket_types_event_id ON ticket_types (event_id);
CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_order_status_history_order_id ON order_status_history (order_id);

--partial:excludes terminal orders (99%+ at steady state), stays small regardless of table size
CREATE INDEX idx_orders_active_expiry
    ON orders (expired_at)
    WHERE status IN ('CREATED', 'PAYING');