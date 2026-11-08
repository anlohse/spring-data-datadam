package io.datadam.demo.examples.books;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
@DatadamDocument(prefix="demo:examples:book:")
@DatadamIndex(name="demo:examples:books:title",fields=@IndexField(path="$.title",alias="title"))
@DatadamIndex(name="demo:examples:books:exact",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="$.title",alias="title",caseSensitive=true),@IndexField(path="$.pages",alias="length",type=IndexField.Type.NUMERIC)})
@DatadamIndex(name="demo:examples:books:folded",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="$.title",alias="title"),@IndexField(path="$.pages",alias="length",type=IndexField.Type.NUMERIC)})
public record Book(@Id @DatadamGeneratedId String id,String title,String author,Integer pages) {}
