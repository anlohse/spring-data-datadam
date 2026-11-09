package com.anlohse.datadam.demo.examples.lifecycle;
import com.anlohse.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
import java.util.concurrent.TimeUnit;
@DatadamDocument(prefix="demo:examples:reservation:",timeToLive=60)
@DatadamIndex(name="demo:examples:reservations:resource",fields=@IndexField(path="$.resource",alias="resource"))
@DatadamIndex(name="demo:examples:reservations:search",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="$.resource",alias="resource",caseSensitive=true))
public record TemporaryReservation(@Id @DatadamGeneratedId String id, String resource, String owner, @DatadamTimeToLive(unit=TimeUnit.MILLISECONDS) Long ttlMillis) {}
