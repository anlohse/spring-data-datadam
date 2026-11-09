package com.anlohse.datadam.demo;

import java.util.List;
import com.anlohse.datadam.spring.repository.DatadamRepository;

public interface TemporaryNoteRepository extends DatadamRepository<TemporaryNote, String> {
    List<TemporaryNote> findByCategory(String category);
}
