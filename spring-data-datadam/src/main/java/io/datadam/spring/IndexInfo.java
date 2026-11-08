package io.datadam.spring;

import java.util.List;
import io.datadam.spring.annotation.IndexField;

/** Schema snapshot; search statistics require FT.INFO, which scans the index. */
public record IndexInfo(IndexStatus status, List<String> prefixes, boolean unique,
                        List<Field> fields, SearchStatistics searchStatistics) {
    public IndexInfo {
        prefixes = List.copyOf(prefixes);
        fields = List.copyOf(fields);
    }
    public record Field(String path, String alias, IndexField.Type type, String separator, boolean caseSensitive) {}
    public record SearchStatistics(long documents, long records) {}
}
