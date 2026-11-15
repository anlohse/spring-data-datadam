package com.anlohse.datadam.demo;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.Id;

/** Staging schema: deliberately separate from Customer's active repository declarations. */
@DatadamDocument(prefix = "demo:customer:")
@DatadamIndex(name = "demo:customers:search:v2", kind = DatadamIndex.Kind.SEARCH, fields = {
    @IndexField(path = "$.city", alias = "city", caseSensitive = true),
    @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC),
    @IndexField(path = "$.email", alias = "email", caseSensitive = true)})
public record CustomerSearchV2(@Id String id, String name, String email, String city, int age, java.util.List<String> tags) {}
