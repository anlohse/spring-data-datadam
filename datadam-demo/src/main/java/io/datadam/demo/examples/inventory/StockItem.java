package io.datadam.demo.examples.inventory;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.*;
@DatadamDocument(prefix="demo:examples:stock:")
@DatadamIndex(name="demo:examples:stock:sku",unique=true,fields=@IndexField(path="$.sku",alias="sku"))
public record StockItem(@Id @DatadamGeneratedId String id,String sku,int quantity,@Version Long version) {}
