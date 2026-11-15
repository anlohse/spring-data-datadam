package com.anlohse.datadam.demo;
import com.anlohse.datadam.spring.callback.BeforeConvertCallback;
import com.anlohse.datadam.spring.callback.BeforeSaveCallback;
import com.anlohse.datadam.spring.callback.DatadamSaveContext;
import org.springframework.stereotype.Component;
@Component
public class HashNoteCallbacks implements BeforeConvertCallback<HashNote>, BeforeSaveCallback<HashNote> {
    public HashNote onBeforeConvert(HashNote note, DatadamSaveContext context) {
        return note.withContent(note.reference(), note.text() == null ? null : note.text().trim(), note.tags(), note.details(), note.version());
    }
    public HashNote onBeforeSave(HashNote note, DatadamSaveContext context) {
        if (note.reference() == null || note.reference().isBlank()) throw new IllegalArgumentException("Reference is required");
        return note;
    }
}
