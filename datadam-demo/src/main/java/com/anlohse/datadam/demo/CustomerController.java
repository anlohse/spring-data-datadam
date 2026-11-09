package com.anlohse.datadam.demo;

import java.util.*;

import com.anlohse.datadam.spring.CompoundQuery;
import org.springframework.dao.*;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/customers")
public class CustomerController {
    private final CustomerRepository repository;
    public CustomerController(CustomerRepository repository) { this.repository = repository; }
    @PostMapping public Customer save(@RequestBody Customer customer) { return repository.save(customer); }
    @GetMapping("/{id}") public Customer get(@PathVariable String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { repository.deleteById(id); }
    @GetMapping public Page<Customer> query(@RequestParam String city, @RequestParam(defaultValue = "0") int minimum,
        @RequestParam(defaultValue = "150") int maximum, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "false") boolean descending) {
        Pageable pageable = page(page, size);
        if (descending) return repository.query("demo:customers:city-age", CompoundQuery.all()
            .where("city", "=", city).where("age", ">=", minimum).where("age", "<", maximum).desc(), pageable);
        return repository.byCityAndAge(city, minimum, maximum, pageable);
    }
    @GetMapping("/search") public Page<Customer> search(@RequestParam String city, @RequestParam(defaultValue = "0") int minimum,
        @RequestParam(defaultValue = "150") int maximum, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return repository.searchCityAndAge(city, minimum, maximum, PageRequest.of(page(page, size).getPageNumber(), size, Sort.by("age")));
    }
    @GetMapping("/email") public Optional<Customer> email(@RequestParam String email) {
        return repository.findByEmail(email);
    }
    @GetMapping("/derived") public Page<Customer> derived(@RequestParam String city,
        @RequestParam(defaultValue = "0") int minimum, @RequestParam(defaultValue = "150") int maximum,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return repository.findByCityAndAgeBetweenOrderByAgeDesc(city, minimum, maximum, page(page, size));
    }
    private Pageable page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page >= 0 and 1 <= size <= 100 required");
        return PageRequest.of(page, size);
    }
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicate() { return Map.of("error", "Email already belongs to another customer"); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }
}
