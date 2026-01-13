package com.chendev.ticketflow.inventory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.chendev.ticketflow.infrastructure.metrics.InventoryMetrics;

import java.time.Instant;

@Entity
@Table(name = "inventories")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private Long ticketTypeId;

    @Column(nullable = false)
    private Integer totalStock;

    @Column(nullable = false)
    private Integer availableStock;

    //if another thread changed the version,affected rows = 0  → OptimisticLockingFailureException.
    @Version
    private Integer version;

    private Instant updatedAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static Inventory init(Long ticketTypeId, int totalStock) {
        Inventory inv = new Inventory();
        inv.ticketTypeId = ticketTypeId;
        inv.totalStock = totalStock;
        inv.availableStock = totalStock;
        inv.updatedAt = Instant.now();
        return inv;
    }

    //InventoryService checks availableStock before calling deduct();reaching here means a caller bug.
    //IllegalStateException(not DomainException): this is a programming error, not a business rule violation
    public void deduct(int quantity) {
        if (this.availableStock < quantity) {
            //should never reach here, InventoryService checks stock before calling deduct(),
            //counter increment fires observability alert; exception stops the operation.
            InventoryMetrics.recordOversellAttempt();
            throw new IllegalStateException(
                    "cannot deduct " + quantity + " from " + availableStock);
        }
        this.availableStock -= quantity;
    }

    public void release(int quantity) {
        //cap at totalStock:double release would otherwise push availableStock above the original allocation
        this.availableStock = Math.min(availableStock + quantity, totalStock);
    }
}