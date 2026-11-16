package com.anlohse.datadam.demo.examples.inventory;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import static com.anlohse.datadam.demo.examples.inventory.PurchaseOrder.Rejection.*;
import static com.anlohse.datadam.demo.examples.inventory.PurchaseOrder.Status.*;

@Service
public class CheckoutService {
    private final PurchaseOrderRepository orders;
    private final StockRepository stock;

    public CheckoutService(PurchaseOrderRepository orders, StockRepository stock) {
        this.orders = orders;
        this.stock = stock;
    }

    public Outcome checkout(UUID orderId, UUID stockId, Integer quantity, Long expectedStockVersion) {
        if (stockId == null || quantity == null || quantity <= 0
                || expectedStockVersion == null || expectedStockVersion < 0) {
            throw new IllegalArgumentException("Stock ID, positive quantity and nonnegative stock version are required");
        }
        String id = orderId.toString();
        var intent = new PurchaseOrder(id, stockId.toString(), quantity, expectedStockVersion, UNKNOWN, null, null);
        try {
            PurchaseOrder order;
            try {
                // Null @Version makes this a create-only write. Only its acknowledged owner attempts the debit.
                order = orders.save(intent);
            } catch (OptimisticLockingFailureException duplicate) {
                var existing = orders.findById(id).orElseThrow(() -> duplicate);
                if (!existing.stockId().equals(intent.stockId()) || existing.quantity() != intent.quantity()
                        || existing.expectedStockVersion() != intent.expectedStockVersion()) {
                    throw duplicate;
                }
                return observe(existing);
            }
            var rejection = debit(order);
            return outcome(orders.save(new PurchaseOrder(id, order.stockId(), order.quantity(),
                order.expectedStockVersion(), rejection == null ? COMPLETED : PARTIAL, rejection, order.version())));
        } catch (DataAccessResourceFailureException lostAcknowledgment) {
            // The issued command may have applied. Leave the durable evidence for an explicit read.
            return new Outcome(id, UNKNOWN, null);
        }
    }

    public Outcome status(UUID orderId) {
        return observe(orders.findById(orderId.toString()).orElseThrow(NoSuchElementException::new));
    }

    private PurchaseOrder.Rejection debit(PurchaseOrder order) {
        var item = stock.findById(order.stockId()).orElse(null);
        if (item == null) return STOCK_NOT_FOUND;
        if (item.version() != order.expectedStockVersion()) return STALE_STOCK_VERSION;
        if (item.quantity() < order.quantity()) return INSUFFICIENT_STOCK;

        try {
            // The decrement and its receipt are part of the same versioned document write.
            stock.save(new StockItem(item.id(), item.sku(), item.quantity() - order.quantity(),
                item.version(), order.id()));
            return null;
        } catch (OptimisticLockingFailureException stale) {
            return STALE_STOCK_VERSION;
        }
    }

    private Outcome observe(PurchaseOrder order) {
        if (order.status() == UNKNOWN && stock.findById(order.stockId())
                .filter(item -> order.id().equals(item.lastCheckoutId())).isPresent()) {
            return new Outcome(order.id(), COMPLETED, null);
        }
        return outcome(order);
    }

    private static Outcome outcome(PurchaseOrder order) {
        return new Outcome(order.id(), order.status(), order.rejection());
    }

    public record Outcome(String orderId, PurchaseOrder.Status status, PurchaseOrder.Rejection rejection) {}
}
