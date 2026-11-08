package io.datadam.demo.examples.migration;
import java.math.BigDecimal;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
@DatadamDocument(prefix="demo:examples:migration:product:")
@DatadamIndex(name="demo:examples:migrations:products:v2",fields={@IndexField(path="$.category",alias="category"),@IndexField(path="$.price",alias="price",type=IndexField.Type.NUMERIC),@IndexField(path="$.sku",alias="sku")})
public record ProductSchemaV2(@Id String id,String sku,String name,String category,BigDecimal price) {}
