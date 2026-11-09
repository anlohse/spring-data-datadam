package com.anlohse.datadam.demo.examples.publishing;
import com.anlohse.datadam.spring.callback.AfterSaveCallback;
import com.anlohse.datadam.spring.callback.BeforeConvertCallback;
import com.anlohse.datadam.spring.callback.BeforeSaveCallback;
import com.anlohse.datadam.spring.callback.DatadamSaveContext;
import org.springframework.stereotype.Component;
@Component
public class ArticleCallbacks implements BeforeConvertCallback<Article>, BeforeSaveCallback<Article>, AfterSaveCallback<Article> {
    public Article onBeforeConvert(Article article, DatadamSaveContext context){return article.content(article.title()==null?null:article.title().trim(),article.body());}
    public Article onBeforeSave(Article article,DatadamSaveContext context){
        if(article.title()==null||article.title().isBlank()||article.body()==null||article.body().isBlank())throw new IllegalArgumentException("Title and body are required");
        return article;
    }
    /** Explicit demo trigger: the save has already been acknowledged when this callback fails. */
    public Article onAfterSave(Article article,DatadamSaveContext context){
        if("[fail-after-save]".equals(article.body()))throw new IllegalStateException("Example post-save notification failed");
        return article;
    }
}
