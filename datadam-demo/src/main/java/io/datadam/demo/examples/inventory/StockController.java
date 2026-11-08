package io.datadam.demo.examples.inventory;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/examples/stock")
public class StockController {
    private final StockRepository repository;
    private final StockService service;
    public StockController(StockRepository repository, StockService service) { this.repository = repository; this.service = service; }
    public record CreateStock(String sku, int quantity) {}
    public record ChangeQuantity(int quantity, Long expectedVersion) {}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public StockItem create(@RequestBody CreateStock value) {
        if (value.sku() == null || value.sku().isBlank() || value.quantity() < 0) throw new IllegalArgumentException("Supply SKU and non-negative quantity");
        return repository.save(new StockItem(null, value.sku(), value.quantity(), null));
    }
    @GetMapping("/{id}") public StockItem get(@PathVariable String id) { return repository.findById(id).orElseThrow(NoSuchElementException::new); }
    @PutMapping("/{id}") public StockItem update(@PathVariable String id, @RequestBody ChangeQuantity value) {
        return service.update(id, value.quantity(), value.expectedVersion());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam Long expectedVersion) { service.delete(id, expectedVersion); }
}
