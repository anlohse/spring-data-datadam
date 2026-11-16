package com.anlohse.datadam.demo.examples.inventory;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/examples/orders")
public class CheckoutController {
    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @PutMapping("/{id}")
    public ResponseEntity<CheckoutService.Outcome> checkout(@PathVariable UUID id, @RequestBody Request request) {
        var outcome = checkout.checkout(id, request.stockId(), request.quantity(), request.expectedStockVersion());
        return ResponseEntity.status(outcome.status() == PurchaseOrder.Status.UNKNOWN
            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).body(outcome);
    }

    @GetMapping("/{id}")
    public CheckoutService.Outcome status(@PathVariable UUID id) {
        return checkout.status(id);
    }

    public record Request(UUID stockId, Integer quantity, Long expectedStockVersion) {}
}
