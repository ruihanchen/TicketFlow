package com.chendev.ticketflow.inventory.service;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.metrics.InventoryMetrics;
import com.chendev.ticketflow.inventory.dto.StockView;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import io.lettuce.core.RedisConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

// Unit test for the Redis exception fallthrough path.
// Uses Mockito because killing the suite-level Redis container would bleed into other tests.
// Happy-path (real Redis + CDC) is covered by InventoryQueryServiceTest.
@ExtendWith(MockitoExtension.class)
class InventoryQueryServiceFallthroughTest {

    @Mock private RedisInventoryManager redisInventory;
    @Mock private InventoryRepository   inventoryRepository;
    @Mock private InventoryMetrics      inventoryMetrics;

    // Explicit construction: if InventoryQueryService gains a new parameter, this fails to compile
    // rather than silently injecting null via @InjectMocks.
    private InventoryQueryService inventoryQueryService;

    private static final Long TICKET_TYPE_ID = 42L;
    private static final int  STOCK          = 77;

    private Inventory dbInventory;

    @BeforeEach
    void setUp() {
        dbInventory = Inventory.init(TICKET_TYPE_ID, STOCK);
        inventoryQueryService = new InventoryQueryService(
                redisInventory, inventoryRepository, inventoryMetrics);
    }

    @Test
    void redis_connection_failure_degrades_to_database() {
        // Spring Data wraps Lettuce connection errors in RedisConnectionFailureException.
        when(redisInventory.getStock(TICKET_TYPE_ID))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID))
                .thenReturn(Optional.of(dbInventory));

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.ticketTypeId()).isEqualTo(TICKET_TYPE_ID);
        assertThat(result.availableStock()).isEqualTo(STOCK);
        assertThat(result.source()).isEqualTo(StockView.StockSource.DATABASE);

        // verify() justified: methods return void, state testing alone can't detect the wrong counter
        verify(inventoryMetrics, times(1)).recordCacheFallthrough();
        verify(inventoryMetrics, never()).recordCacheHit();
        verify(inventoryMetrics, never()).recordCacheMiss();
    }

    @Test
    void raw_lettuce_exception_also_degrades_to_database() {
        // Not all low-level Lettuce errors get wrapped by Spring Data.
        when(redisInventory.getStock(TICKET_TYPE_ID))
                .thenThrow(new RedisConnectionException("nio handshake failed"));
        when(inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID))
                .thenReturn(Optional.of(dbInventory));

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.source()).isEqualTo(StockView.StockSource.DATABASE);
        verify(inventoryMetrics, times(1)).recordCacheFallthrough();
    }

    @Test
    void corrupted_cache_value_degrades_to_database() {
        // RedisInventoryManager.getStock() calls Integer.parseInt(); a corrupted value throws NumberFormatException.
        when(redisInventory.getStock(TICKET_TYPE_ID))
                .thenThrow(new NumberFormatException("for input string: \"corrupted\""));
        when(inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID))
                .thenReturn(Optional.of(dbInventory));

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.source()).isEqualTo(StockView.StockSource.DATABASE);
        verify(inventoryMetrics, times(1)).recordCacheFallthrough();
    }

    @Test
    void redis_down_and_db_row_missing_throws_inventory_not_found() {
        // Both caches fail: must surface a clean 404, not an NPE.
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

    @Test
    void redis_null_is_a_cache_miss_not_a_fallthrough() {
        // null = CDC not yet propagated or key evicted, expected, not an error.
        when(redisInventory.getStock(TICKET_TYPE_ID)).thenReturn(null);
        when(inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID))
                .thenReturn(Optional.of(dbInventory));

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.source()).isEqualTo(StockView.StockSource.DATABASE);
        verify(inventoryMetrics, times(1)).recordCacheMiss();
        verify(inventoryMetrics, never()).recordCacheFallthrough();
    }

    @Test
    void redis_value_present_is_a_cache_hit_and_db_is_never_queried() {
        when(redisInventory.getStock(TICKET_TYPE_ID)).thenReturn(STOCK);

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.source()).isEqualTo(StockView.StockSource.CACHE);
        verify(inventoryMetrics, times(1)).recordCacheHit();
        verify(inventoryRepository, never()).findByTicketTypeId(any());
    }
}