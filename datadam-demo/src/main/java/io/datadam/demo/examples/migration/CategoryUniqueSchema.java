package io.datadam.demo.examples.migration;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
@DatadamDocument(prefix="demo:examples:migration:product:")
@DatadamIndex(name="demo:examples:migrations:category-unique:v2",unique=true,fields=@IndexField(path="$.category",alias="category"))
public record CategoryUniqueSchema(@Id String id) {}
