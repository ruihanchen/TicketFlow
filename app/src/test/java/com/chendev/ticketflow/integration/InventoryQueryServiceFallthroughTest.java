package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.infrastructure.metrics.InventoryMetrics;
import com.chendev.ticketflow.inventory.dto.StockView;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryQueryService;
import io.lettuce.core.RedisConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Unit test for the Redis-exception fallthrough path, uses Mockito because killing Testcontainers Redis mid-suite
// would bleed into other tests. InventoryQueryServiceTest covers happy path & CDC propagation with a real container.
@ExtendWith(MockitoExtension.class)
class InventoryQueryServiceFallthroughTest {

    @Mock private RedisInventoryManager redisInventory;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private InventoryMetrics inventoryMetrics;

    @InjectMocks
    private InventoryQueryService inventoryQueryService;

    private static final Long TICKET_TYPE_ID = 42L;
    private static final int STOCK = 77;

    private Inventory dbInventory;

    @BeforeEach
    void setUp() {
        // Inventory.init() is the production factory; no Spring container needed
        dbInventory = Inventory.init(TICKET_TYPE_ID, STOCK);
    }

    @Test
    void redis_connection_failure_falls_through_to_database() {
        // Lettuce wraps connection refused in RedisConnectionFailureException;
        // service must degrade to DB, not propagate a 5xx to the caller
        when(redisInventory.getStock(TICKET_TYPE_ID))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID))
                .thenReturn(Optional.of(dbInventory));

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.getTicketTypeId()).isEqualTo(TICKET_TYPE_ID);
        assertThat(result.getAvailableStock()).isEqualTo(STOCK);
        assertThat(result.getSource())
                .as("Redis exception must surface as a DATABASE-sourced response, not a 5xx")
                .isEqualTo(StockView.StockSource.DATABASE);

        verify(inventoryMetrics, times(1)).recordCacheFallthrough();
        verify(inventoryMetrics, never()).recordCacheHit();
        verify(inventoryMetrics, never()).recordCacheMiss();
    }

    @Test
    void lettuce_low_level_exception_also_falls_through() {
        // not all Redis errors get wrapped by Spring Data; catch must cover raw Lettuce exceptions too
        when(redisInventory.getStock(TICKET_TYPE_ID))
                .thenThrow(new RedisConnectionException("nio handshake failed"));
        when(inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID))
                .thenReturn(Optional.of(dbInventory));

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.getAvailableStock()).isEqualTo(STOCK);
        assertThat(result.getSource()).isEqualTo(StockView.StockSource.DATABASE);
        verify(inventoryMetrics, times(1)).recordCacheFallthrough();
    }

    @Test
    void runtime_exception_during_redis_read_falls_through() {
        // corrupted cache value surfaces as NumberFormatException, same degradation contract
        when(redisInventory.getStock(TICKET_TYPE_ID))
                .thenThrow(new NumberFormatException("for input string: \"corrupted\""));
        when(inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID))
                .thenReturn(Optional.of(dbInventory));

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.getSource()).isEqualTo(StockView.StockSource.DATABASE);
        verify(inventoryMetrics, times(1)).recordCacheFallthrough();
    }

    @Test
    void fallthrough_with_missing_db_row_throws_inventory_not_found() {
        // Redis down AND DB row gone: must surface a clean 404, not an NPE from the fallback path
        when(redisInventory.getStock(TICKET_TYPE_ID))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryQueryService.getStock(TICKET_TYPE_ID))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.INVENTORY_NOT_FOUND);

        verify(inventoryMetrics, times(1)).recordCacheFallthrough();
    }
}