package io.datadam.demo.examples.documents;
import java.util.*;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
@DatadamDocument(prefix="demo:examples:preferences:")
public record UserPreferences(@Id @DatadamGeneratedId String id,String owner,Map<String,Object> settings,List<String> favorites,
    @DatadamTimeToLive(unit=java.util.concurrent.TimeUnit.MILLISECONDS) Long ttlMillis) {}
