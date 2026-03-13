package com.chendev.ticketflow.inventory.entity;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.exception.SystemException;
import com.chendev.ticketflow.common.response.ResultCode;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventories")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // One inventory record per ticket type — enforced by UNIQUE constraint in DB
    @Column(nullable = false, unique = true, name = "ticket_type_id")
    private Long ticketTypeId;

    @Column(nullable = false, name = "total_stock")
    private Integer totalStock;

    @Column(nullable = false, name = "available_stock")
    private Integer availableStock;

    // Optimistic lock version — prevents concurrent overwrites without DB row lock
    // JPA automatically increments this on every UPDATE and checks it on save
    @Version
    private Integer version;

    @LastModifiedDate
    @Column(nullable = false, name = "updated_at")
    private LocalDateTime updatedAt;

    public static Inventory initialize(Long ticketTypeId, Integer totalStock) {
        Inventory inventory = new Inventory();
        inventory.ticketTypeId = ticketTypeId;
        inventory.totalStock = totalStock;
        inventory.availableStock = totalStock;
        return inventory;
    }

    // Domain behavior: deduct stock
    // Business rule enforced here, not in Service layer
    public void deduct(int quantity) {
        if (quantity <= 0) {
            throw new BizException(ResultCode.INVENTORY_LOCK_FAILED,
                    "Deduct quantity must be greater than 0");
        }
        if (this.availableStock < quantity) {
            throw new BizException(ResultCode.INVENTORY_INSUFFICIENT,
                    "Available: " + this.availableStock + ", requested: " + quantity);
        }
        this.availableStock -= quantity;
    }

    // Domain behavior: release stock (used when order is cancelled)
    public void release(int quantity) {
        if (quantity <= 0) {
            throw new BizException(ResultCode.INVENTORY_LOCK_FAILED,
                    "Release quantity must be greater than 0");
        }
        // Guard: available stock should never exceed total stock
        if (this.availableStock + quantity > this.totalStock) {
            throw new SystemException(ResultCode.INTERNAL_ERROR,
                    "Release would exceed total stock. ticketTypeId=" + ticketTypeId
                            + ", total=" + totalStock
                            + ", available=" + availableStock
                            + ", releasing=" + quantity);
        }
        this.availableStock += quantity;
    }

    public boolean hasSufficientStock(int quantity) {
        return this.availableStock >= quantity;
    }
}
