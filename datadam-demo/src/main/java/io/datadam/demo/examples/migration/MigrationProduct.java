package io.datadam.demo.examples.migration;
import java.math.BigDecimal;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
/** Dedicated namespace for the administrative migration scenario, separate from live catalog data. */
@DatadamDocument(prefix="demo:examples:migration:product:")
@DatadamIndex(name="demo:examples:migrations:products:v1",fields={@IndexField(path="$.category",alias="category"),@IndexField(path="$.price",alias="price",type=IndexField.Type.NUMERIC)})
public record MigrationProduct(@Id String id,String sku,String name,String category,BigDecimal price) {}
