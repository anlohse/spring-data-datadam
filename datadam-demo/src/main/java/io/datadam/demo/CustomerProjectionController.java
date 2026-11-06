package io.datadam.demo;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/customer-projections")
public class CustomerProjectionController {
    private final CustomerRepository json;private final HashCustomerRepository hash;
    public CustomerProjectionController(CustomerRepository json,HashCustomerRepository hash){this.json=json;this.hash=hash;}
    @GetMapping("/json") public Page<CustomerSummary> json(@RequestParam String city,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return json.summariesByCity(city,page(page,size));}
    @GetMapping("/hash") public Page<CustomerSummary> hash(@RequestParam String city,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return hash.summariesByCity(city,page(page,size));}
    @GetMapping("/json/search") public Page<CustomerView> jsonSearch(@RequestParam String city,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return json.viewsByCity(city,page(page,size));}
    @GetMapping("/hash/search") public Page<CustomerView> hashSearch(@RequestParam String city,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return hash.viewsByCity(city,page(page,size));}
    private Pageable page(int page,int size){if(page<0||size<1||size>100)throw new IllegalArgumentException("page >= 0 and 1 <= size <= 100 required");return PageRequest.of(page,size);}
}
