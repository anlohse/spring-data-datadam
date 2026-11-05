package io.datadam.demo;
import io.datadam.spring.callback.*;
import org.springframework.stereotype.Component;

@Component
public class VersionedNoteCallbacks implements BeforeConvertCallback<VersionedNote>, BeforeSaveCallback<VersionedNote> {
    public VersionedNote onBeforeConvert(VersionedNote note, DatadamSaveContext context) {
        return note.withContent(note.reference(), note.category(), note.text() == null ? null : note.text().trim());
    }
    public VersionedNote onBeforeSave(VersionedNote note, DatadamSaveContext context) {
        if (note.reference() == null || note.reference().isBlank() || note.category() == null || note.category().isBlank()
            || note.text() == null || note.text().isBlank()) throw new IllegalArgumentException("Reference, category and nonblank text are required");
        return note;
    }
}
