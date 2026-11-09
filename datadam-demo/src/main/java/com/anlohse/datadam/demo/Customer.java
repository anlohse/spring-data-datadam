package com.anlohse.datadam.demo;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.Id;

@DatadamDocument(prefix = "demo:customer:")
@DatadamIndex(name = "demo:customers:city-age", fields = {
    @IndexField(path = "$.city", alias = "city"),
    @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC)})
@DatadamIndex(name = "demo:customers:email", unique = true, fields = @IndexField(path = "$.email", alias = "email"))
@DatadamIndex(name = "demo:customers:search", kind = DatadamIndex.Kind.SEARCH, fields = {
    @IndexField(path = "$.city", alias = "city"),
    @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC),
    @IndexField(path = "$.tags[*]", alias = "tag")})
// Exact equality for derived methods needs CASESENSITIVE TAG fields.
@DatadamIndex(name = "demo:customers:derived", kind = DatadamIndex.Kind.SEARCH, fields = {
    @IndexField(path = "$.city", alias = "home", caseSensitive = true),
    @IndexField(path = "$.email", alias = "email", caseSensitive = true),
    @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC)})
public record Customer(@Id String id, String name, String email, String city, int age, java.util.List<String> tags) {}
