package com.anlohse.datadam.demo.examples.migration;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.Id;
/** Read-only comparison target: intentionally not registered for schema creation. */
@DatadamDocument(prefix="demo:examples:product:")
@DatadamIndex(name="demo:examples:products:category-price:v1",fields=@IndexField(path="$.category",alias="category"))
public record ProductSchemaMismatch(@Id String id) {}
