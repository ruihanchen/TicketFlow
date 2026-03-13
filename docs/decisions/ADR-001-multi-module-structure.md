# ADR-001: Multi-Module Maven Structure

**Date**: 2026-03  
**Status**: Accepted  
**Author**: Chen (chendev)

---

## Context

TicketFlow is designed with a clear long-term trajectory: start as a single deployable
unit in Phase 1, evolve into microservices in Phase 3. The very first structural decision
— how to organize the codebase — has compounding consequences on every phase that follows.

The question: should we start with a single flat Maven project and restructure later,
or invest in a multi-module structure from day one?

---

## Decision Drivers

- **Phase 3 microservice extraction must be low-risk**: Rewriting business logic during
  extraction is dangerous. The extraction should be a mechanical cut, not a redesign.
- **Domain boundary enforcement**: Without structural enforcement, package-level boundaries
  erode over time. Developers take shortcuts. Cross-domain dependencies accumulate silently.
- **Shared infrastructure must not carry business logic**: JWT utilities, exception classes,
  and response formats need to live somewhere that all future services can depend on without
  circular dependencies.

---

## Options Considered

### Option A: Single flat Maven project

```
ticketflow/
└── src/main/java/com/chendev/ticketflow/
    ├── user/
    ├── event/
    ├── inventory/
    └── order/
```

**Pros**: Simple to set up, no Maven configuration overhead.  
**Cons**: No structural enforcement of boundaries. Any class can import any other class.
Cross-domain dependencies are invisible until they cause pain. Extracting microservices
later requires manually untangling a dependency graph that was never tracked.

### Option B: Multi-module Maven from day one

```
ticketflow/               ← Parent POM (dependency management only)
├── common/               ← Shared infrastructure (Result, exceptions, utils)
└── app/                  ← All business logic (Phase 1: single deployable)
```

**Pros**: Maven enforces module boundaries — `app` can see `common`, but `common`
cannot see `app`. Phase 2/3 adds new modules without restructuring.
**Cons**: Slightly more Maven configuration upfront.

### Option C: Immediately separate modules per domain

```
ticketflow/
├── common/
├── user-service/
├── event-service/
├── inventory-service/
└── order-service/
```

**Pros**: Closest to the final Phase 3 target state.  
**Cons**: Massively premature. Cross-service communication (HTTP, Kafka) adds enormous
complexity before the core business logic is even validated. This is the classic mistake
of optimizing for a scale you don't have yet.

---

## Decision

**Option B: Multi-module Maven with two modules in Phase 1.**

The parent POM manages all dependency versions via `<dependencyManagement>`.
Child modules declare dependencies without versions — all version pins live in one place.

Key rule: `common` contains only infrastructure that every future service will need.
It has zero business logic. The moment business logic appears in `common`, the module
has failed its purpose.

Phase 2/3 evolution path:

```
Phase 1:          Phase 2:              Phase 3:
ticketflow/       ticketflow/           ticketflow/
├── common/       ├── common/           ├── common/
└── app/          ├── app/              ├── inventory-service/
                  └── inventory-svc/    ├── order-service/
                                        └── user-service/
```

Each phase adds modules. No existing module is restructured.

---

## Consequences

**Positive**:
- Accidental cross-domain imports are impossible at the Maven level
- `common` module can be published as an internal library for Phase 3 services
- Version management is centralized — upgrading Spring Boot touches one line

**Negative**:
- Developers must be disciplined about what goes into `common`
- Module-level circular dependencies require explicit Maven configuration to detect

**Accepted trade-off**: The upfront Maven configuration cost (estimated: 30 minutes)
is negligible compared to the cost of untangling a monolithic dependency graph during
microservice extraction (estimated: days to weeks).
