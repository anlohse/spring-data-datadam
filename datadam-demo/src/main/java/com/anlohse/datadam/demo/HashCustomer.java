package com.anlohse.datadam.demo;

import java.util.*;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.DatadamStorage;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.Id;

@DatadamDocument(prefix="demo:hash:customer:", storage= DatadamStorage.HASH)
@DatadamIndex(name="demo:hash:customers:city-age", fields={
    @IndexField(path="city", alias="city"), @IndexField(path="age", alias="age", type=IndexField.Type.NUMERIC)})
@DatadamIndex(name="demo:hash:customers:email", unique=true, fields=@IndexField(path="email", alias="email"))
@DatadamIndex(name="demo:hash:customers:search", kind=DatadamIndex.Kind.SEARCH, fields={
    @IndexField(path="city", alias="home"), @IndexField(path="age", alias="years", type=IndexField.Type.NUMERIC),
    @IndexField(path="labels", alias="tag", separator=";", caseSensitive=true),
    @IndexField(path="tags", alias="storedTags", separator="|", caseSensitive=true)})
public record HashCustomer(@Id String id, String name, String email, String city, Integer age, String labels,
    List<String> tags, Map<String, Object> details) {}
