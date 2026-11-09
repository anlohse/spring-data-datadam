package com.anlohse.datadam.demo;

import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

/** Examples backed entirely by native indexes, for both entity storage formats. */
@RestController
@RequestMapping("/customer-queries")
public class DerivedOperatorsController {
    private final CustomerRepository json;
    private final HashCustomerRepository hash;

    public DerivedOperatorsController(CustomerRepository json, HashCustomerRepository hash) {
        this.json = json;
        this.hash = hash;
    }

    @GetMapping("/json/prefix")
    public Page<Customer> jsonPrefix(@RequestParam String prefix, Pageable pageable) {
        return json.findByEmailStartingWithOrderByEmailAsc(prefix, pageable);
    }

    @GetMapping("/hash/prefix")
    public Page<HashCustomer> hashPrefix(@RequestParam String prefix, Pageable pageable) {
        return hash.findByEmailStartingWithOrderByEmailAsc(prefix, pageable);
    }

    @GetMapping("/json/age-not-in")
    public Page<Customer> jsonNotIn(@RequestParam List<Integer> excluded, Pageable pageable) {
        return json.findByAgeNotIn(excluded, pageable);
    }

    @GetMapping("/hash/age-not-in")
    public Page<HashCustomer> hashNotIn(@RequestParam List<Integer> excluded, Pageable pageable) {
        return hash.findByAgeNotIn(excluded, pageable);
    }
}
