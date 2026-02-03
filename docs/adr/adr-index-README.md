# Architecture Decision Records

Three ADRs for the decisions that shaped TicketFlow's core architecture. Worth reading in order. ADR-001 sets up the write path. ADR-002 adds the read cache that makes the write path work at scale. ADR-003 cleans up a domain modeling problem in the order lifecycle.

| ADR | Decision | Type |
|---|---|---|
| [001](adr-001-conditional-update.md) | Conditional UPDATE over @Version | Concurrency / write path |
| [002](adr-002-cdc-over-dual-write.md) | CDC over dual-write | Consistency / read cache |
| [003](adr-003-two-tier-order-expiry.md) | Two independent deadlines over single timeout | Domain modeling / order lifecycle |
