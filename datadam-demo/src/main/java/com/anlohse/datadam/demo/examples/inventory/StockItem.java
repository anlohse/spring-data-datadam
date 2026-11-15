package com.anlohse.datadam.demo.examples.inventory;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.*;
@DatadamDocument(prefix="demo:examples:stock:")
@DatadamIndex(name="demo:examples:stock:sku",unique=true,fields=@IndexField(path="$.sku",alias="sku"))
public record StockItem(@Id @DatadamGeneratedId String id, String sku, int quantity, @Version Long version) {}
