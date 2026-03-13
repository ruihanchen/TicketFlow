# ADR-002: Inventory as Separate Domain

**Date**: 2026-03  
**Status**: Accepted  
**Author**: Chen (chendev)

---

## Context

Inventory — the count of available tickets — is conceptually related to ticket types,
which belong to events. The naive design puts inventory inside the Event domain.

This ADR documents why inventory deserves its own domain boundary, and why that decision
is load-bearing for Phase 2 and Phase 3.

---

## The Access Pattern Argument

Event information and inventory information have fundamentally different access patterns:

| Dimension | Event / TicketType | Inventory |
|-----------|-------------------|-----------|
| Read frequency | High (browsing) | High (pre-purchase check) |
| Write frequency | Low (admin only) | **Extremely high** (every purchase) |
| Write contention | None | **Severe under flash sale** |
| Consistency requirement | Eventual OK | **Strong — no overselling** |
| Phase 2 technology | No change | Redis + Lua atomic ops |
| Phase 3 extraction | Event Service | **Inventory Service** |

Two entities with this level of divergence do not belong in the same domain.

---

## Options Considered

### Option A: Inventory as a field on TicketType

```java
@Entity
public class TicketType {
    private String name;
    private BigDecimal price;
    private Integer totalStock;
    private Integer availableStock;  // ← inventory embedded here
}
```

**Pros**: Simple. One less table, one less join.  
**Cons**:
- Every purchase triggers a write lock on the entire `TicketType` row, blocking
  concurrent reads of static data (name, price) that never change.
- Phase 2 requires Redis to cache inventory. If inventory is embedded in `TicketType`,
  the cache invalidation logic bleeds into the Event domain.
- Phase 3 cannot extract Inventory Service without splitting the `ticket_types` table —
  a dangerous migration on a production database.

### Option B: Inventory as a separate table, same domain

Separate table, but inventory repository lives inside the Event domain package.

**Pros**: Separate table allows independent writes.  
**Cons**: The Event domain now has two distinct concerns. The boundary between
"event information" and "stock management" is invisible at the code level.
When a new developer joins, they will not know where inventory logic belongs.

### Option C: Inventory as a fully separate domain

```
app/
├── event/          ← Events, TicketTypes (static data)
├── inventory/      ← Inventory (dynamic, high-frequency writes)
└── order/          ← Consumes InventoryPort interface
```

**Pros**:
- Write contention on inventory does not affect event data reads
- Phase 2 Redis integration is contained entirely within the inventory domain
- Phase 3 extraction creates a clean `inventory-service` with its own database
- The domain boundary is explicit and enforced by package structure

**Cons**: Slightly more boilerplate (extra repository, extra service class).

---

## Decision

**Option C: Inventory as a fully separate domain.**

The `inventories` table has a one-to-one relationship with `ticket_types` but lives in
its own JPA entity, repository, and service. The `version` field enables optimistic
locking without row-level locks.

```java
@Entity
@Table(name = "inventories")
public class Inventory {
    @Column(unique = true)
    private Long ticketTypeId;      // FK by value, not JPA relationship
    private Integer totalStock;
    private Integer availableStock;
    @Version
    private Integer version;        // Optimistic lock
}
```

Note: `ticketTypeId` is stored as a plain `Long`, not a `@ManyToOne` JPA relationship.
This is intentional — it prevents Hibernate from eagerly loading `TicketType` when
querying inventory, and it makes Phase 3 extraction trivial (no ORM relationship to sever).

---

## Consequences

**Positive**:
- Inventory writes are isolated. Flash sale contention does not affect event browsing.
- Phase 2 Redis integration touches only the `inventory` package.
- Phase 3 extraction is a mechanical operation: move `inventory/` to a new Maven module,
  add a datasource, done.

**Negative**:
- Creating an event now requires two write operations: save `TicketType`, save `Inventory`.
  Both are wrapped in a single `@Transactional` to maintain consistency.
- Querying event details requires a join across two domains. Handled in `EventService`
  by explicitly fetching inventory records by `ticketTypeId`.

**Accepted trade-off**: The extra complexity in `createEvent` is a one-time cost.
The benefit — clean Phase 2 and Phase 3 evolution — compounds across every future
sprint that touches inventory.
