package com.anlohse.datadam.demo.examples.inventory;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

@DatadamDocument(prefix = "demo:examples:order:")
public record PurchaseOrder(@Id String id, String stockId, int quantity, long expectedStockVersion,
                            Status status, Rejection rejection, @Version Long version) {
    public enum Status { COMPLETED, PARTIAL, UNKNOWN }
    public enum Rejection { STOCK_NOT_FOUND, STALE_STOCK_VERSION, INSUFFICIENT_STOCK }
}
