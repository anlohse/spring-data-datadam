package com.anlohse.datadam.demo;

import com.anlohse.datadam.spring.callback.BeforeConvertCallback;
import com.anlohse.datadam.spring.callback.BeforeSaveCallback;
import com.anlohse.datadam.spring.callback.DatadamSaveContext;
import org.springframework.stereotype.Component;

@Component
public class AuditedNoteCallbacks implements BeforeConvertCallback<AuditedNote>, BeforeSaveCallback<AuditedNote> {
    @Override public AuditedNote onBeforeConvert(AuditedNote note, DatadamSaveContext context) {
        return note.withContent(note.reference(), note.category(), note.text() == null ? null : note.text().trim());
    }
    @Override public AuditedNote onBeforeSave(AuditedNote note, DatadamSaveContext context) {
        if (note.reference() == null || note.reference().isBlank() || note.category() == null || note.category().isBlank()
            || note.text() == null || note.text().isBlank()) throw new IllegalArgumentException("Reference, category and nonblank text are required");
        return note;
    }
}
