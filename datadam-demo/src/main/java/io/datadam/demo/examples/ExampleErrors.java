package io.datadam.demo.examples;

import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Stable HTTP errors without native command arguments or credential details. */
@RestControllerAdvice(basePackages="io.datadam.demo.examples")
public class ExampleErrors {
    @ExceptionHandler({DuplicateKeyException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String,String>> conflict(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Conflict; reload by ID before deciding how to proceed"));
    }
    @ExceptionHandler({IllegalArgumentException.class, InvalidDataAccessApiUsageException.class})
    public ResponseEntity<Map<String,String>> invalid(RuntimeException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request or unsupported native query"));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String,String>> missing() { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found")); }
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Datadam unavailable; reconcile writes before retrying"));
    }
}
