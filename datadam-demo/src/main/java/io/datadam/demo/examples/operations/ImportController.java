package io.datadam.demo.examples.operations;

import java.util.*;
import io.datadam.demo.examples.catalog.Product;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/examples/imports")
public class ImportController {
    private final ImportService service;
    private final ImportJobRepository repository;
    public ImportController(ImportService service, ImportJobRepository repository) { this.service = service; this.repository = repository; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ImportJob create() { return service.create(); }
    @GetMapping("/{id}") public ImportJob get(@PathVariable String id) { return repository.findById(id).orElseThrow(NoSuchElementException::new); }
    @PostMapping("/{id}/run") public ImportJob run(@PathVariable String id, @RequestBody List<Product> products) { return service.run(id, products); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) {
        var job = get(id);
        if (!Set.of("COMPLETED", "PARTIAL").contains(job.status())) throw new IllegalArgumentException("Reconcile unfinished imports before removing their status");
        repository.delete(job);
    }
}
