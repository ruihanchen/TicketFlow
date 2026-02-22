# ADR-003: Port & Adapter Pattern for Cross-Domain Calls

**Date**: 2026-03  
**Status**: Accepted  
**Author**: Chen (chendev)

---

## Context

`OrderService` needs to deduct inventory when a user places an order, and release
inventory when an order is cancelled or expires.

In Phase 1, inventory lives in the same JVM. In Phase 3, inventory lives in a
separate service accessed over the network. The question is: how do we write
`OrderService` today so that Phase 3 requires zero changes to business logic?

---

## The Problem with Direct Injection

The instinctive solution is to inject `InventoryService` directly into `OrderService`:

```java
@Service
public class OrderService {
    @Autowired
    private InventoryService inventoryService;  // Direct dependency

    public OrderResponse createOrder(...) {
        inventoryService.deductStock(ticketTypeId, quantity);
        // ...
    }
}
```

This works in Phase 1. In Phase 3, `InventoryService` no longer exists in the
same JVM. You now need to:

1. Find every call to `inventoryService.*` across the codebase
2. Replace each one with a Feign client or HTTP call
3. Handle network timeouts, retries, and circuit breakers in business logic methods
4. Test that you didn't break anything in the process

This is the rewrite problem disguised as a refactor. It happens because the
business logic is coupled to the implementation technology.

---

## Options Considered

### Option A: Direct injection (described above)

**Rejected**: Creates an invisible coupling that becomes a Phase 3 migration tax.

### Option B: Interface in the Inventory domain

```java
// inventory/service/InventoryPort.java
public interface InventoryPort {
    void deductStock(Long ticketTypeId, int quantity);
}

// OrderService depends on inventory.service.InventoryPort
```

**Pros**: Adds an interface layer.  
**Cons**: The interface lives in the `inventory` package. `OrderService` must still
import from the `inventory` domain. The dependency direction is:

```
order → inventory
```

This is structurally identical to direct injection. The interface adds indirection
but not decoupling. When Inventory becomes a separate service, the `order` domain
still has a compile-time dependency on the `inventory` domain.

### Option C: Port interface owned by the Order domain

```java
// order/port/InventoryPort.java  ← Lives in Order domain
public interface InventoryPort {
    void deductStock(Long ticketTypeId, int quantity);
    void releaseStock(Long ticketTypeId, int quantity);
    boolean hasSufficientStock(Long ticketTypeId, int quantity);
}
```

The interface is defined by the *consumer* (Order), not the *provider* (Inventory).
Dependency direction:

```
order/service/OrderService
    → order/port/InventoryPort          (interface, same domain)
        ← infrastructure/adapter/InventoryAdapter   (implementation)
            → inventory/service/InventoryService    (local in Phase 1)
```

`OrderService` has zero import from the `inventory` package.
Phase 3 migration: replace `InventoryAdapter` implementation. `OrderService` is untouched.

**Pros**: True decoupling. Business logic in `OrderService` is completely isolated
from how inventory is implemented or where it lives.  
**Cons**: More files. Requires discipline to not let the adapter leak into business logic.

---

## Decision

**Option C: Port interface owned by the Order domain.**

The port interface is defined by what the Order domain *needs*, not by what the
Inventory domain *provides*. This is the Dependency Inversion Principle applied
at the domain boundary level.

```
order/
└── port/
    └── InventoryPort.java        ← Order domain's contract

infrastructure/
└── adapter/
    └── InventoryAdapter.java     ← Phase 1: calls InventoryService locally
                                     Phase 3: calls inventory-service via HTTP/Feign
```

Phase 3 migration is a single-file replacement:

```java
// Phase 1 — delete this
@Component
public class InventoryAdapter implements InventoryPort {
    private final InventoryService inventoryService;  // local
    // ...
}

// Phase 3 — add this
@Component
public class RemoteInventoryAdapter implements InventoryPort {
    private final InventoryFeignClient feignClient;  // remote
    // ...
}
```

`OrderService`, `OrderStateMachine`, `OrderTimeoutService` — none of them change.

---

## The SAGA Connection

`InventoryPort` includes `releaseStock()` — not just `deductStock()`.

This is a deliberate SAGA-awareness decision. In Phase 1, order creation uses
`@Transactional`:

```java
@Transactional
public OrderResponse createOrder(...) {
    inventoryPort.deductStock(...);
    orderRepository.save(order);
    // If save fails, @Transactional rolls back both operations
}
```

In Phase 3, there is no distributed transaction. The compensation pattern replaces it:

```java
public OrderResponse createOrder(...) {
    inventoryPort.deductStock(...);         // Remote call, may succeed
    try {
        orderRepository.save(order);        // Local, may fail
    } catch (Exception e) {
        inventoryPort.releaseStock(...);    // Compensate
        throw e;
    }
}
```

By defining `releaseStock()` in the port today, Phase 3 adds compensation logic
without changing the interface contract.

---

## Consequences

**Positive**:
- `OrderService` is fully independent of Inventory implementation details
- Phase 3 extraction is a single adapter replacement with no business logic changes
- SAGA compensation is structurally possible from day one

**Negative**:
- Three layers (Port, Adapter, Service) instead of two (Service, Service) adds indirection
- New developers must understand the pattern before contributing to cross-domain features

**Accepted trade-off**: The indirection cost is a one-time learning investment.
The decoupling benefit pays dividends at every phase boundary.
