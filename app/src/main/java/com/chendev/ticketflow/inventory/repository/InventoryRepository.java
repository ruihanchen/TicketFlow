package com.chendev.ticketflow.inventory.repository;

import com.chendev.ticketflow.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByTicketTypeId(Long ticketTypeId);

    //both use native SQL; guardRelease needs LEAST() which isn't in JPQL
    //atomic conditional UPDATE: zero retry, zero version conflict, one SQL per request.
    @Modifying
    @Query(value = "UPDATE inventories SET available_stock = available_stock - :quantity, " +
            "version = version + 1, updated_at = NOW() " +
            "WHERE ticket_type_id = :ticketTypeId AND available_stock >= :quantity",
            nativeQuery = true)
    int guardDeduct(Long ticketTypeId, int quantity);

    // LEAST() caps at total_stock: defensive against double-release overflow
    @Modifying
    @Query(value = "UPDATE inventories SET available_stock = LEAST(available_stock + :quantity, total_stock), " +
            "version = version + 1, updated_at = NOW() " +
            "WHERE ticket_type_id = :ticketTypeId",
            nativeQuery = true)
    int guardRelease(Long ticketTypeId, int quantity);
}