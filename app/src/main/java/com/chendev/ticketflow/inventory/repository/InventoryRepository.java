package com.chendev.ticketflow.inventory.repository;

import com.chendev.ticketflow.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByTicketTypeId(Long ticketTypeId);

    // Pessimistic write lock — used during stock deduction
    // Prevents concurrent reads from seeing stale stock before update
    // Phase 2: this will be replaced by Redis atomic operations
    @Query("SELECT i FROM Inventory i WHERE i.ticketTypeId = :ticketTypeId")
    Optional<Inventory> findByTicketTypeIdForUpdate(Long ticketTypeId);
}
