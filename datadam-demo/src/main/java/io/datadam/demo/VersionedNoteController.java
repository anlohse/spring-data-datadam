package io.datadam.demo;

import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/versioned-notes")
public class VersionedNoteController {
    private final VersionedNoteRepository repository;
    public VersionedNoteController(VersionedNoteRepository repository) { this.repository = repository; }
    public record NoteInput(String reference, String category, String text, Long version) {}
    @PostMapping public VersionedNote create(@RequestBody NoteInput input) {
        if (input.version() != null) throw new IllegalArgumentException("Creation requires a null version");
        return repository.save(new VersionedNote(null, input.reference(), input.category(), input.text(), null, null, null, null, null));
    }
    @PutMapping("/{id}") public VersionedNote update(@PathVariable String id, @RequestBody NoteInput input) {
        if (input.version() == null) throw new IllegalArgumentException("Expected version is required");
        // Preserve creation metadata, but use the client's expected version rather than replacing it with the loaded one.
        var existing = get(id);
        return repository.save(existing.withContent(input.reference(), input.category(), input.text()).withVersion(input.version()));
    }
    @GetMapping("/{id}") public VersionedNote get(@PathVariable String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @GetMapping public List<VersionedNote> find(@RequestParam String category) { return repository.findByCategory(category); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam Long version) { repository.delete(get(id).withVersion(version)); }
    @ExceptionHandler(OptimisticLockingFailureException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> stale() { return Map.of("error", "The expected version is no longer current"); }
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicate() { return Map.of("error", "Reference already belongs to another note"); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("error", error.getMessage()); }
}
