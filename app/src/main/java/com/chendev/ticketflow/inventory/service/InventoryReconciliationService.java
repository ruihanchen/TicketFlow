package com.chendev.ticketflow.inventory.service;

import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

// repairs Redis/DB desyncs from crashes between Redis DECRBY and DB order commit.
// redis < db → auto-fix (lost deduction); redis > db → alert only.
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryReconciliationService {

    private final InventoryRepository inventoryRepository;
    private final RedisInventoryManager redisInventoryManager;

    @Scheduled(fixedDelayString = "${ticketflow.reconciliation.fixed-delay:300000}")
    public void reconcile() {
        List<Inventory> inventories = inventoryRepository.findAll();
        int fixed = 0;
        int alerts = 0;

        for (Inventory inv : inventories) {
            try {
                fixed += reconcileOne(inv);
            } catch (Exception e) {
                //per ticketType isolation:one Redis failure doesn't block the rest; next run retries.
                alerts++;
                log.error("[Reconciliation] failed for ticketTypeId={}: {}",
                        inv.getTicketTypeId(), e.getMessage());
            }
        }

        if (fixed > 0 || alerts > 0) {
            log.info("[Reconciliation] completed: checked={}, fixed={}, alerts={}",
                    inventories.size(), fixed, alerts);
        }
    }

    private int reconcileOne(Inventory inv) {
        Long ticketTypeId = inv.getTicketTypeId();
        int dbStock = inv.getAvailableStock();
        Integer redisStock = redisInventoryManager.getStock(ticketTypeId);

        // key missing: same as redis < db (restore it)
        if (redisStock == null) {
            redisInventoryManager.warmUp(ticketTypeId, dbStock);
            log.warn("[Reconciliation] missing Redis key restored: ticketTypeId={}, stock={}",
                    ticketTypeId, dbStock);
            return 1;
        }

        if (redisStock == dbStock) {
            return 0;
        }

        if (redisStock < dbStock) {
            //redis deducted but order didn't persist (crash/rollback); DB is source of truth.
            redisInventoryManager.warmUp(ticketTypeId, dbStock);
            log.warn("[Reconciliation] redis < db, auto-fixed: ticketTypeId={}, " +
                    "redis={}, db={}", ticketTypeId, redisStock, dbStock);
            return 1;
        }

        //redis > db should not happen; indicates a release bug or manual DB edit.
        //do NOT auto-fix: reducing Redis could hide real inventory from buyers.
        log.error("[Reconciliation] redis > db, requires manual investigation: " +
                "ticketTypeId={}, redis={}, db={}", ticketTypeId, redisStock, dbStock);
        return 0;
    }
}