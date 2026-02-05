-- ─── Users ───────────────────────────────────────────────────────────────────
CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    email         VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Events ──────────────────────────────────────────────────────────────────
CREATE TABLE events
(
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    venue           VARCHAR(200),
    event_date      TIMESTAMP    NOT NULL,
    sale_start_time TIMESTAMP    NOT NULL,
    sale_end_time   TIMESTAMP    NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Ticket Types ─────────────────────────────────────────────────────────────
CREATE TABLE ticket_types
(
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT         NOT NULL REFERENCES events (id),
    name        VARCHAR(100)   NOT NULL,
    price       DECIMAL(10, 2) NOT NULL,
    total_stock INT            NOT NULL CHECK (total_stock > 0),
    created_at  TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- ─── Inventories ─────────────────────────────────────────────────────────────
-- Separated from ticket_types intentionally:
-- ticket_types holds static info (name, price, total)
-- inventories holds dynamic info (available stock) — high-frequency writes
CREATE TABLE inventories
(
    id              BIGSERIAL PRIMARY KEY,
    ticket_type_id  BIGINT    NOT NULL UNIQUE REFERENCES ticket_types (id),
    total_stock     INT       NOT NULL,
    available_stock INT       NOT NULL CHECK (available_stock >= 0),
    version         INT       NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ─── Orders ──────────────────────────────────────────────────────────────────
CREATE TABLE orders
(
    id             BIGSERIAL PRIMARY KEY,
    order_no       VARCHAR(32)    NOT NULL UNIQUE,
    user_id        BIGINT         NOT NULL REFERENCES users (id),
    ticket_type_id BIGINT         NOT NULL REFERENCES ticket_types (id),
    quantity       INT            NOT NULL DEFAULT 1 CHECK (quantity > 0),
    unit_price     DECIMAL(10, 2) NOT NULL,
    total_amount   DECIMAL(10, 2) NOT NULL,
    status         VARCHAR(20)    NOT NULL DEFAULT 'CREATED',
    request_id     VARCHAR(64) UNIQUE,
    expired_at     TIMESTAMP,
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- ─── Order Status History ─────────────────────────────────────────────────────
CREATE TABLE order_status_history
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT       NOT NULL REFERENCES orders (id),
    from_status VARCHAR(20),
    to_status   VARCHAR(20)  NOT NULL,
    reason      VARCHAR(200),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Indexes ──────────────────────────────────────────────────────────────────
-- Queries we know will be frequent based on business logic
CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_expired_at ON orders (expired_at);
CREATE INDEX idx_ticket_types_event_id ON ticket_types (event_id);
CREATE INDEX idx_order_status_history_order_id ON order_status_history (order_id);