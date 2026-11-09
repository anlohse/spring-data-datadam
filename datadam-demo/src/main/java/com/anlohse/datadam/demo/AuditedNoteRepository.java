package com.anlohse.datadam.demo;

import java.util.List;
import com.anlohse.datadam.spring.repository.DatadamRepository;

public interface AuditedNoteRepository extends DatadamRepository<AuditedNote, String> {
    List<AuditedNote> findByCategory(String category);
}
