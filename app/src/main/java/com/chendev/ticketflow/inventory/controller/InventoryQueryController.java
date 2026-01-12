package com.chendev.ticketflow.inventory.controller;

import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.inventory.dto.StockView;
import com.chendev.ticketflow.inventory.service.InventoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Public read endpoint for live stock counts. No auth required, stock counts are public information,
// same trust level as GET /api/v1/events/{id}. Rate limiting belongs at the edge layer, not here.
@RestController
@RequestMapping("/api/v1/ticket-types")
@RequiredArgsConstructor
public class InventoryQueryController {

    private final InventoryQueryService inventoryQueryService;

    @GetMapping("/{ticketTypeId}/stock")
    public Result<StockView> getStock(@PathVariable Long ticketTypeId) {
        return Result.ok(inventoryQueryService.getStock(ticketTypeId));
    }
}
