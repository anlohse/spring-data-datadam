package io.datadam.demo.examples.devices;
import java.util.*;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.*;
@DatadamDocument(prefix="demo:examples:device:",storage=DatadamStorage.HASH)
@DatadamIndex(name="demo:examples:devices:region-seen",fields={@IndexField(path="region",alias="region"),@IndexField(path="lastSeenEpochMillis",alias="lastSeenEpochMillis",type=IndexField.Type.NUMERIC)})
@DatadamIndex(name="demo:examples:devices:search",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="region",alias="zone",caseSensitive=true),@IndexField(path="lastSeenEpochMillis",alias="seen",type=IndexField.Type.NUMERIC),@IndexField(path="labels",alias="tag",separator=";",caseSensitive=true)})
public record DeviceState(@Id @DatadamGeneratedId String id,String name,String region,long lastSeenEpochMillis,Boolean online,String labels,
    List<String> capabilities,Map<String,Object> configuration,@Version Long version,@DatadamTimeToLive(unit=java.util.concurrent.TimeUnit.MILLISECONDS) Long ttlMillis) {}
