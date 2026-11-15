package com.anlohse.datadam.demo;

import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Input excludes audit fields; updates load the current entity to carry its creation metadata forward. */
@RestController
@RequestMapping("/audited-notes")
public class AuditedNoteController {
    private final AuditedNoteRepository repository;
    public AuditedNoteController(AuditedNoteRepository repository) { this.repository = repository; }
    public record NoteInput(String reference, String category, String text) {}
    @PostMapping public AuditedNote create(@RequestBody NoteInput input) {
        return repository.save(new AuditedNote(null, input.reference(), input.category(), input.text(), null, null, null, null));
    }
    @PutMapping("/{id}") public AuditedNote update(@PathVariable String id, @RequestBody NoteInput input) {
        return repository.save(get(id).withContent(input.reference(), input.category(), input.text()));
    }
    @GetMapping("/{id}") public AuditedNote get(@PathVariable String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @GetMapping public List<AuditedNote> find(@RequestParam String category) { return repository.findByCategory(category); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { repository.deleteById(id); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicate() { return Map.of("error", "Reference already belongs to another note"); }
}
