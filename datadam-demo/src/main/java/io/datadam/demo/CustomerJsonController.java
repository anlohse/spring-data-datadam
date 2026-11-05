package io.datadam.demo;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import io.datadam.spring.*;
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
