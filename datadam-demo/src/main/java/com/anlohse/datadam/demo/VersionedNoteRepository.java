package com.anlohse.datadam.demo;
import java.util.List;
import com.anlohse.datadam.spring.repository.DatadamRepository;
public interface VersionedNoteRepository extends DatadamRepository<VersionedNote, String> {
    List<VersionedNote> findByCategory(String category);
}
