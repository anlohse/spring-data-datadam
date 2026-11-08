package io.datadam.demo.examples.catalog;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/examples/products")
public class ProductController {
    private final ProductRepository repository;
    public ProductController(ProductRepository repository) { this.repository = repository; }
    public record CreateProduct(String sku, String name, String category, BigDecimal price, List<String> tags) {}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Product create(@RequestBody CreateProduct value) {
        return repository.save(new Product(null, value.sku(), value.name(), value.category(), value.price(), value.tags()));
    }
    @GetMapping("/{id}") public Product get(@PathVariable String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @GetMapping("/sku/{sku}") public Product sku(@PathVariable String sku) {
        return repository.findBySku(sku).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { repository.deleteById(id); }
    @GetMapping public Page<Product> range(@RequestParam String category, @RequestParam BigDecimal minimum,
        @RequestParam BigDecimal maximum, Pageable pageable) {
        return repository.findByCategoryAndPriceBetweenOrderByPriceAsc(category, minimum, maximum, pageable);
    }
    @GetMapping("/search") public Page<Product> search(@RequestParam String category, @RequestParam BigDecimal minimum,
        @RequestParam BigDecimal maximum, Pageable pageable) { return repository.search(category, minimum, maximum, pageable); }
    @GetMapping("/summaries") public Page<ProductSummary> summaries(@RequestParam String category, @RequestParam BigDecimal minimum,
        @RequestParam BigDecimal maximum, Pageable pageable) { return repository.summaries(category, minimum, maximum, pageable); }
}
