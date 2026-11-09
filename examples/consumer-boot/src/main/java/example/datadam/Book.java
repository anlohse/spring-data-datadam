package example.datadam;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.*;

@DatadamDocument(prefix = "consumer:book:")
@DatadamIndex(name = "consumer:books:sku", unique = true, fields = @IndexField(path = "$.sku", alias = "sku"))
@DatadamIndex(name = "consumer:books:author-pages", fields = {
    @IndexField(path = "$.author", alias = "author"),
    @IndexField(path = "$.pages", alias = "pages", type = IndexField.Type.NUMERIC)})
@DatadamIndex(name = "consumer:books:search", kind = DatadamIndex.Kind.SEARCH, fields = {
    @IndexField(path = "$.author", alias = "writer", caseSensitive = true),
    @IndexField(path = "$.pages", alias = "length", type = IndexField.Type.NUMERIC)})
public record Book(@Id String id, String sku, String title, String author, Integer pages, @Version Long version) {}
