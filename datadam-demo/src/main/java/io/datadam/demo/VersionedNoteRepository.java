package io.datadam.demo;
import java.util.List;
import io.datadam.spring.repository.DatadamRepository;
public interface VersionedNoteRepository extends DatadamRepository<VersionedNote, String> {
    List<VersionedNote> findByCategory(String category);
}
