package com.anlohse.datadam.demo.examples.catalog;
import java.math.BigDecimal;
import java.util.List;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.Id;

@DatadamDocument(prefix="demo:examples:product:")
@DatadamIndex(name="demo:examples:products:category-price:v1",fields={@IndexField(path="$.category",alias="category"),@IndexField(path="$.price",alias="price",type=IndexField.Type.NUMERIC)})
@DatadamIndex(name="demo:examples:products:sku",unique=true,fields=@IndexField(path="$.sku",alias="sku"))
@DatadamIndex(name="demo:examples:products:search",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="$.category",alias="category",caseSensitive=true),@IndexField(path="$.price",alias="price",type=IndexField.Type.NUMERIC),@IndexField(path="$.tags[*]",alias="tag")})
public record Product(@Id @DatadamGeneratedId String id, String sku, String name, String category, BigDecimal price, List<String> tags) {}
