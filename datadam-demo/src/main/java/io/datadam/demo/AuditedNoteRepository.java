package io.datadam.demo;

import java.util.List;
import io.datadam.spring.repository.DatadamRepository;

public interface AuditedNoteRepository extends DatadamRepository<AuditedNote, String> {
    List<AuditedNote> findByCategory(String category);
}
