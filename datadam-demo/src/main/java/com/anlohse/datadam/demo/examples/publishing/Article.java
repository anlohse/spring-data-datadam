package com.anlohse.datadam.demo.examples.publishing;
import java.time.Instant;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import org.springframework.data.annotation.*;
@DatadamDocument(prefix="demo:examples:article:")
public record Article(@Id @DatadamGeneratedId String id, String title, String body,
                      @CreatedDate Instant createdAt, @LastModifiedDate Instant modifiedAt, @CreatedBy String createdBy, @LastModifiedBy String modifiedBy) {
    public Article content(String title,String body){return new Article(id,title,body,createdAt,modifiedAt,createdBy,modifiedBy);}
}
