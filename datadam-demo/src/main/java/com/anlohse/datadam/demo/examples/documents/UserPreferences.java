package com.anlohse.datadam.demo.examples.documents;
import java.util.*;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import com.anlohse.datadam.spring.annotation.DatadamTimeToLive;
import org.springframework.data.annotation.Id;
@DatadamDocument(prefix="demo:examples:preferences:")
public record UserPreferences(@Id @DatadamGeneratedId String id, String owner, Map<String,Object> settings, List<String> favorites,
                              @DatadamTimeToLive(unit=java.util.concurrent.TimeUnit.MILLISECONDS) Long ttlMillis) {}
