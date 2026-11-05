package io.datadam.demo;

import java.util.*;
import io.datadam.spring.DatadamCommands;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/temporary-notes")
public class TemporaryNoteController {
    private final TemporaryNoteRepository repository;
    private final DatadamCommands commands;
    public TemporaryNoteController(TemporaryNoteRepository repository, DatadamCommands commands) {
        this.repository = repository;
        this.commands = commands;
    }
    @PostMapping public TemporaryNote save(@RequestBody TemporaryNote note) { return repository.save(note); }
    @GetMapping("/{id}") public TemporaryNote get(@PathVariable String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @GetMapping public List<TemporaryNote> find(@RequestParam String category) { return repository.findByCategory(category); }
    @GetMapping("/{id}/ttl") public Map<String, Object> ttl(@PathVariable String id) {
        return Map.of("remainingMilliseconds", commands.execute("PTTL", "demo:temporary:" + id));
    }
    @PostMapping("/{id}/persist") public Map<String, Boolean> persist(@PathVariable String id) {
        return Map.of("expirationRemoved", repository.persist(id));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { repository.deleteById(id); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicate() { return Map.of("error", "Reference already belongs to another note"); }
}
