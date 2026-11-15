package com.anlohse.datadam.demo.examples.migration;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.Id;
@DatadamDocument(prefix="demo:examples:migration:product:")
@DatadamIndex(name="demo:examples:migrations:category-unique:v2",unique=true,fields=@IndexField(path="$.category",alias="category"))
public record CategoryUniqueSchema(@Id String id) {}
