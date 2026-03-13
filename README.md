# TicketFlow

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)
![Flyway](https://img.shields.io/badge/Flyway-10-red)
![Phase](https://img.shields.io/badge/Phase-1%20MVP-yellow)

A production-grade ticketing system designed for high-concurrency flash sales —
think Taylor Swift tour tickets selling out in seconds.

Built with a deliberate architecture evolution strategy:

- **Phase 1 (current)**: Single deployable unit with clean domain boundaries
- **Phase 2**: Redis-based inventory, Kafka async processing, distributed idempotency
- **Phase 3**: Full microservice extraction with SAGA orchestration

> This project demonstrates not just implementation, but the *engineering reasoning*
> behind every major decision.
> See [Architecture Decision Records](docs/decisions/).

---

## The Core Challenge

When 50,000 users simultaneously attempt to purchase from 1,000 available tickets:

- **Overselling**: How do you guarantee exactly 1,000 tickets sold?
- **Idempotency**: User double-clicks "Buy" — do they get charged twice?
- **Timeout**: User grabs a ticket but never pays — how do you reclaim it?
- **Fairness**: How do you prevent bots from sweeping all inventory in milliseconds?

Each of these has a specific solution in this codebase, with documented reasoning.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                       TicketFlow MVP                         │
│                                                              │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────────┐   │
│  │    User    │  │   Event    │  │     Order Domain     │   │
│  │   Domain   │  │   Domain   │  │                      │   │
│  │            │  │            │  │  ┌────────────────┐  │   │
│  │  Auth      │  │  Events    │  │  │  OrderService  │  │   │
│  │  JWT       │  │  Tickets   │  │  │  StateMachine  │  │   │
│  └────────────┘  └────────────┘  │  └───────┬────────┘  │   │
│                                  │          │           │   │
│  ┌───────────────────────────┐   │  ┌───────▼────────┐  │   │
│  │    Inventory Domain       │   │  │ InventoryPort  │  │   │
│  │  (Phase 2: own service)   │◄──┼──│  (interface)   │  │   │
│  └───────────────────────────┘   │  └────────────────┘  │   │
│                                  └──────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              infrastructure / adapter                 │   │
│  │          InventoryAdapter (local impl)                │   │
│  │          → Phase 2: RemoteInventoryAdapter            │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

**Why this structure?**
Domain boundaries are established now so Phase 2 microservice extraction
requires *cutting code apart*, not rewriting business logic.
See [ADR-002](docs/decisions/ADR-002-inventory-as-separate-domain.md) and
[ADR-003](docs/decisions/ADR-003-port-adapter-pattern.md).

---

## Key Engineering Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Module structure | Multi-module Maven | Enforce domain boundaries before microservice split |
| Inventory domain | Separate from Event | Write-heavy inventory has different access patterns; isolated for Phase 2 extraction |
| Cross-domain calls | Port & Adapter pattern | OrderService has zero knowledge of Inventory implementation; Phase 2 swaps adapter only |
| Concurrency control | Optimistic locking (Phase 1) | No DB row locks; contention surfaces as retryable error; Phase 2 upgrades to Redis atomic ops via Lua script |
| Order state | Event-driven state machine | Single source of truth for all transitions; illegal state changes are structurally impossible |
| Idempotency | Two-layer defense: App-level check + DB UNIQUE constraint | DB constraint survives cache failure — correctness guaranteed even when Redis is unavailable |
| Transaction boundary | Local `@Transactional` + manual compensation | SAGA-aware from day one; Phase 2 replaces compensation with Kafka-driven rollback |
| Database migrations | Flyway 10 + `ddl-auto: validate` | Schema changes are versioned and reviewed; Hibernate never modifies production schema |
| Purchase fairness | Per-order limit (max 4 tickets) | Prevents bulk purchasing; Phase 2 adds token bucket rate limiting at API Gateway to prevent bot-driven flash purchases |

Full reasoning in [Architecture Decision Records](docs/decisions/).

---

## Tech Stack

| Layer | Technology | Version |
|-------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.3.5 |
| Security | Spring Security + JWT (jjwt) | 6.3 / 0.12.6 |
| Database | PostgreSQL | 16 |
| Database Migration | Flyway | 10 |
| ORM | Spring Data JPA / Hibernate | 6.5 |
| Connection Pool | HikariCP | 5.1 |
| Build | Maven Multi-Module | 3.9 |
| Infrastructure | Docker Compose | — |

**Phase 2 additions** (planned): Redis, Kafka, Docker multi-service compose

---

## Getting Started

**Prerequisites**: Java 21, Docker

```bash
# 1. Clone the repository
git clone https://github.com/chendev/ticketflow.git
cd ticketflow

# 2. Start infrastructure
cd docker && docker-compose up -d

# 3. Verify PostgreSQL is healthy
docker ps  # Status should show "healthy"

# 4. Run the application
./mvnw spring-boot:run -pl app

# 5. Verify startup
curl http://localhost:8080/api/v1/events
```

### Create an Admin User

```bash
# Step 1: Register
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "email": "admin@example.com",
    "password": "admin123456"
  }'

# Step 2: Promote to ADMIN in PostgreSQL
docker exec -it ticketflow-postgres psql -U postgres -d ticketflow \
  -c "UPDATE users SET role = 'ADMIN' WHERE username = 'admin';"
```

### Complete Purchase Flow

```bash
# 1. Login as admin → get JWT token
POST /api/v1/auth/login

# 2. Create event (requires ADMIN token)
POST /api/v1/admin/events

# 3. Publish event (requires ADMIN token)
PUT /api/v1/admin/events/{eventId}/publish

# 4. Login as regular user → get JWT token
POST /api/v1/auth/login

# 5. Place order with idempotency key
POST /api/v1/orders
{ "ticketTypeId": 1, "quantity": 2, "requestId": "unique-uuid-here" }

# 6. Initiate payment
POST /api/v1/orders/{orderNo}/pay

# 7. Confirm payment (simulates payment gateway callback in MVP)
POST /api/v1/orders/{orderNo}/confirm-payment
```

See [API Examples](docs/architecture/api-examples.md) for full request/response samples.

---

## Testing Strategy

### Phase 1 (Planned)

- [ ] Unit tests: `OrderStateMachine` — all legal and illegal transition paths
- [ ] Unit tests: `Inventory.deduct()` / `release()` boundary conditions
- [ ] Unit tests: `Result` / `BizException` / `GlobalExceptionHandler` contract
- [ ] Integration tests: Full order lifecycle with [Testcontainers](https://testcontainers.com/)
- [ ] Integration tests: Idempotency — duplicate `requestId` handling

### Phase 2 (Planned)

- [ ] Load testing: Concurrent order creation under flash sale conditions (target: 5,000 concurrent users)
- [ ] Chaos testing: System behavior under Redis node failure and network partition
- [ ] Idempotency verification: Duplicate request handling under high concurrency
- [ ] Inventory accuracy: Zero overselling guarantee after 10,000 concurrent purchase attempts

---

## Project Roadmap

### ✅ Phase 1 — MVP (Current)

- [x] JWT authentication (register, login, token validation)
- [x] Event & ticket type management with admin controls
- [x] Idempotent order creation (application-level + DB constraint)
- [x] Optimistic locking for concurrent inventory control
- [x] Event-driven order state machine (CREATED → PAYING → PAID → CONFIRMED)
- [x] Order timeout cancellation with inventory recovery (scheduled job)
- [x] Domain isolation with Port & Adapter pattern
- [x] Flyway database migration management
- [x] Unified response format and exception handling

### 🔄 Phase 2 — High Concurrency

- [ ] Redis-based inventory with Lua scripts (atomic deduction, zero overselling)
- [ ] Distributed idempotency check via Redis (fast path before DB)
- [ ] Kafka for async order processing (decouple inventory deduction from order creation)
- [ ] Order timeout via Redis delayed queue (replace polling with event-driven cancellation)
- [ ] Token bucket rate limiting at API Gateway (fairness under flash sale load)

### 📋 Phase 3 — Microservices

- [ ] Extract Inventory Service (independent deployable, own database)
- [ ] Extract Order Service (independent deployable, own database)
- [ ] API Gateway with authentication and rate limiting
- [ ] SAGA orchestration for distributed transactions (replace manual compensation)
- [ ] Distributed tracing with correlation IDs across services

---

## Documentation

### Architecture

- [System Overview](docs/architecture/system-overview.md)
- [Database Schema](docs/architecture/database-schema.md)
- [API Examples](docs/architecture/api-examples.md)

### Architecture Decision Records

ADRs document the *reasoning* behind major technical decisions —
not just what was decided, but what alternatives were considered and why they were rejected.

- [ADR-001: Multi-Module Maven Structure](docs/decisions/ADR-001-multi-module-structure.md)
- [ADR-002: Inventory as Separate Domain](docs/decisions/ADR-002-inventory-as-separate-domain.md)
- [ADR-003: Port & Adapter Pattern for Cross-Domain Calls](docs/decisions/ADR-003-port-adapter-pattern.md)
- [ADR-004: Optimistic Locking in MVP](docs/decisions/ADR-004-optimistic-locking-mvp.md)
- [ADR-005: Event-Driven Order State Machine](docs/decisions/ADR-005-event-driven-state-machine.md)

### Benchmarks

- [MVP Load Test Results](docs/benchmarks/mvp-load-test-results.md)

---

## Why This Project

Most tutorials show you *how* to build a system.
This project documents *why* each decision was made —
the trade-offs considered, the alternatives rejected, and the evolution path planned.

The architecture is intentionally designed to answer the question every senior engineer
asks in a system design interview:

> *"If this needed to handle 10x the load tomorrow, what would you change and why?"*

The answer is already documented in the roadmap and ADRs above.
