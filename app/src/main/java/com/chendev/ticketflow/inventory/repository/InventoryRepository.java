package com.chendev.ticketflow.inventory.repository;

import com.chendev.ticketflow.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByTicketTypeId(Long ticketTypeId);
}