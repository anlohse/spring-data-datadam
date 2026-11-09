package com.anlohse.datadam.demo;

import java.util.Map;
import java.util.List;

import com.anlohse.datadam.spring.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Partial document operations: no entity load/save round trip. */
@RestController
@RequestMapping("/customers/{id}/json")
public class CustomerJsonController {
    private final DatadamJsonOperations<Customer> json;
    public CustomerJsonController(DatadamTemplate template) { json = template.json(Customer.class); }
    public record CityInput(String value) {}
    public record NumberInput(Integer amount) {}
    public record TagsInput(List<String> values) {}
    private static final JsonPath<List<String>> TAGS = JsonPath.of("$.tags", new TypeReference<List<String>>() {});
    @PostMapping("/age/increment") public List<Number> increment(@PathVariable String id, @RequestBody NumberInput input) {
        if (input.amount() == null) throw new IllegalArgumentException("Amount is required");
        return json.increment(id, JsonPath.of("$.age", Integer.class), input.amount());
    }
    @PostMapping("/tags/append") public List<Long> append(@PathVariable String id, @RequestBody TagsInput input) {
        return json.arrayAppend(id, TAGS, input.values());
    }
    @PostMapping("/tags/insert") public List<Long> insert(@PathVariable String id, @RequestParam long index, @RequestBody TagsInput input) {
        return json.arrayInsert(id, TAGS, index, input.values());
    }
    @DeleteMapping("/tags/pop") public List<JsonPopResult<String>> pop(@PathVariable String id, @RequestParam(defaultValue = "-1") long index) {
        return json.arrayPop(id, TAGS, index);
    }
    @PutMapping("/tags/trim") public List<Long> trim(@PathVariable String id, @RequestParam long start, @RequestParam long stop) {
        return json.arrayTrim(id, TAGS, start, stop);
    }
    // Demonstrates an additional JSON object, available through JSON reads without extending the Customer DTO.
    @PutMapping("/details") public Map<String, Boolean> details(@PathVariable String id, @RequestBody Map<String, Object> details) {
        return Map.of("written", json.set(id, JsonPath.of("$.details", new TypeReference<Map<String, Object>>() {}), details));
    }
    @PatchMapping("/details") public Map<String, Boolean> mergeDetails(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return Map.of("acknowledged", json.merge(id, JsonPath.of("$.details", Map.class), patch));
    }
    @GetMapping public JsonReadResult<JsonNode> read(@PathVariable String id, @RequestParam String path) {
        return json.read(id, JsonPath.of(path, JsonNode.class));
    }
    @PutMapping("/city") public Map<String, Boolean> city(@PathVariable String id, @RequestBody CityInput input) {
        return Map.of("written", json.set(id, JsonPath.of("$.city", String.class), input.value()));
    }
    @DeleteMapping("/name") public Map<String, Long> removeName(@PathVariable String id) {
        return Map.of("removed", json.remove(id, JsonPath.of("$.name", String.class)));
    }
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicate() { return Map.of("error", "A unique index rejected the change"); }
    @ExceptionHandler({ IllegalArgumentException.class, InvalidDataAccessApiUsageException.class, InvalidDataAccessResourceUsageException.class })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(RuntimeException error) { return Map.of("error", error.getMessage()); }
}
