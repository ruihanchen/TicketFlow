package com.chendev.ticketflow.inventory.repository;

import com.chendev.ticketflow.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByTicketTypeId(Long ticketTypeId);

    // Phase 1: pessimistic-lock read used by InventoryAdapter
    @Query("SELECT i FROM Inventory i WHERE i.ticketTypeId = :ticketTypeId")
    Optional<Inventory> findByTicketTypeIdForUpdate(Long ticketTypeId);

    /**
     * Phase 2 guard write: deduct stock only if sufficient stock exists.
     *
     * Called after a successful Redis Lua deduction to keep DB in sync.
     * The AND available_stock >= :qty condition is the DB-layer safety net:
     * if Redis and DB drift apart, this prevents the DB from going negative
     * even when the DB CHECK constraint is the absolute last resort.
     *
     * Returns the number of rows updated (1 = success, 0 = stock insufficient
     * in DB — signals a Redis/DB drift that requires Redis compensation).
     */
    @Modifying
    @Query("UPDATE Inventory i " +
            "SET i.availableStock = i.availableStock - :qty " +
            "WHERE i.ticketTypeId = :ticketTypeId " +
            "AND i.availableStock >= :qty")
    int guardDeductStock(@Param("ticketTypeId") Long ticketTypeId,
                         @Param("qty") int qty);

    /**
     * Phase 2 guard write: restore stock when an order is cancelled.
     *
     * Unconditional increment — the caller (RedisInventoryAdapter) already
     * verified via releaseStock.lua that the key existed in Redis.
     * No upper-bound check here; the DB CHECK (available_stock >= 0) and
     * the entity-level guard in Inventory.release() provide that protection
     * when going through the Phase 1 path.
     *
     * Returns the number of rows updated (1 = success, 0 = record missing).
     */
    @Modifying
    @Query("UPDATE Inventory i " +
            "SET i.availableStock = i.availableStock + :qty " +
            "WHERE i.ticketTypeId = :ticketTypeId")
    int guardReleaseStock(@Param("ticketTypeId") Long ticketTypeId,
                          @Param("qty") int qty);
}
