package io.datadam.demo;

import java.util.List;
import io.datadam.spring.repository.DatadamRepository;

public interface TemporaryNoteRepository extends DatadamRepository<TemporaryNote, String> {
    List<TemporaryNote> findByCategory(String category);
}
