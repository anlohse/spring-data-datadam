package io.datadam.demo.examples.operations;
import java.util.List;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.*;
@DatadamDocument(prefix="demo:examples:import:")
public record ImportJob(@Id @DatadamGeneratedId String id,String status,int processed,int rejected,List<String> productIds,@Version Long version) {}
