package io.datadam.demo;

import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/hash-customers")
public class HashCustomerController {
    private final HashCustomerRepository repository;
    public HashCustomerController(HashCustomerRepository repository) { this.repository=repository; }
    @PostMapping public HashCustomer save(@RequestBody HashCustomer input) { return repository.save(input); }
    @GetMapping("/{id}") public HashCustomer get(@PathVariable String id) {
        return repository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { repository.deleteById(id); }
    @GetMapping public Page<HashCustomer> query(@RequestParam String city, @RequestParam(defaultValue="0") int minimum,
        @RequestParam(defaultValue="150") int maximum, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return repository.byCityAndAge(city, minimum, maximum, page(page,size));
    }
    @GetMapping("/derived") public Page<HashCustomer> derived(@RequestParam String city, @RequestParam(defaultValue="0") int minimum,
        @RequestParam(defaultValue="150") int maximum, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return repository.findByCityAndAgeBetweenOrderByAgeDesc(city, minimum, maximum, page(page,size));
    }
    @GetMapping("/search") public Page<HashCustomer> search(@RequestParam String city, @RequestParam(defaultValue="0") int minimum,
        @RequestParam(defaultValue="150") int maximum, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return repository.searchCityAndAge(city, minimum, maximum, PageRequest.of(page(page,size).getPageNumber(),size,Sort.by("years")));
    }
    @GetMapping("/labels") public List<HashCustomer> labels(@RequestParam String label) { return repository.byLabel(label, PageRequest.of(0,100)); }
    private Pageable page(int page,int size) {
        if (page<0 || size<1 || size>100) throw new IllegalArgumentException("page >= 0 and 1 <= size <= 100 required");
        return PageRequest.of(page,size);
    }
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> duplicate() { return Map.of("error","Email already belongs to another customer"); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException e) { return Map.of("error",e.getMessage()); }
}
