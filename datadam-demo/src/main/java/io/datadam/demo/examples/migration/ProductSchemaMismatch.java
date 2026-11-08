package io.datadam.demo.examples.migration;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
/** Read-only comparison target: intentionally not registered for schema creation. */
@DatadamDocument(prefix="demo:examples:product:")
@DatadamIndex(name="demo:examples:products:category-price:v1",fields=@IndexField(path="$.category",alias="category"))
public record ProductSchemaMismatch(@Id String id) {}
