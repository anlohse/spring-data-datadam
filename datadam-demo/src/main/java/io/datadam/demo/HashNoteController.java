package io.datadam.demo;

import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/hash-notes")
public class HashNoteController {
    private final HashNoteRepository repository;
    public HashNoteController(HashNoteRepository repository) { this.repository = repository; }
    public record Input(String reference, String text, List<String> tags, Map<String, Object> details, Long version) {}
    @PostMapping public HashNote create(@RequestBody Input input) {
        if (input.version() != null) throw new IllegalArgumentException("Creation requires a null version");
        return repository.save(new HashNote(null, input.reference(), input.text(), input.tags(), input.details(), null, null, null, null, null));
    }
    @GetMapping("/{id}") public HashNote get(@PathVariable String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @PutMapping("/{id}") public HashNote update(@PathVariable String id, @RequestBody Input input) {
        if (input.version() == null) throw new IllegalArgumentException("Expected version is required");
        return repository.save(get(id).withContent(input.reference(), input.text(), input.tags(), input.details(), input.version()));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam Long version) {
        var existing = get(id); repository.delete(existing.withContent(existing.reference(), existing.text(), existing.tags(), existing.details(), version));
    }
    @ExceptionHandler({ OptimisticLockingFailureException.class, DuplicateKeyException.class }) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict() { return Map.of("error", "Version or unique reference conflict"); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("error", error.getMessage()); }
}
