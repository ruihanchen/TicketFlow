package com.chendev.ticketflow.infrastructure.reconciliation;

import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Detects and corrects drift between Redis inventory values and the DB.
 *
 * Redis is a projection of the DB, not an independent source of truth.
 * Under normal operation Redis and DB stay in sync via the guard writes in
 * RedisInventoryAdapter. This job catches the cases that slip through:
 * compensation failures, Redis restarts, or any unexpected inconsistency.
 *
 * Correction strategy: DB wins. Always.
 * The DB has ACID guarantees and a CHECK (available_stock >= 0) constraint.
 * Redis has neither. When they disagree, DB is right.
 */
@Slf4j
@Component
@RequiredArgsConstructor

public class InventoryReconciler {

    private final InventoryRepository   inventoryRepository;
    private final RedisInventoryManager redisInventoryManager;

    /**
     * Runs every 5 minutes. Scans all inventory records and corrects any
     * Redis values that differ from DB.
     *
     * Keys absent in Redis are skipped — absence is valid (lazy-load not yet
     * triggered, or expected post-restart state). The next deductStock call
     * will populate the key via lazy-load. Fabricating a key here before any
     * request has arrived would add unnecessary write load with no benefit.
     */
    @Scheduled(fixedDelayString = "${ticketflow.reconciliation.fixed-delay:300000}")
    public void reconcile() {
        List<Inventory> allInventories = inventoryRepository.findAll();

        if (allInventories.isEmpty()) {
            return;
        }

        int checkedCount  = 0;
        int correctedCount = 0;
        int absentCount   = 0;

        for (Inventory inventory : allInventories) {
            Long ticketTypeId = inventory.getTicketTypeId();
            Optional<Integer> redisStock = redisInventoryManager.getStock(ticketTypeId);

            if (redisStock.isEmpty()) {
                // Key absent — valid state, skip silently.
                absentCount++;
                continue;
            }

            checkedCount++;
            int redisValue = redisStock.get();
            int dbValue    = inventory.getAvailableStock();

            if (redisValue != dbValue) {
                // Drift detected — overwrite Redis with the DB value.
                redisInventoryManager.setStock(ticketTypeId, dbValue);
                correctedCount++;

                log.warn("[Reconciliation] Drift corrected: ticketTypeId={}, "
                                + "redis={}, db={} — Redis overwritten with DB value.",
                        ticketTypeId, redisValue, dbValue);
            }
        }

        if (correctedCount > 0) {
            log.warn("[Reconciliation] Run complete: checked={}, corrected={}, absent={}. "
                            + "Non-zero corrections indicate compensation failures — "
                            + "review recent ERROR logs.",
                    checkedCount, correctedCount, absentCount);
        } else {
            log.info("[Reconciliation] Run complete: checked={}, corrected=0, absent={}. "
                            + "Redis and DB are in sync.",
                    checkedCount, absentCount);
        }
    }
}
