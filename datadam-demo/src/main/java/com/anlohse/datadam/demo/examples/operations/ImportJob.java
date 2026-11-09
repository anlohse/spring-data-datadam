package com.anlohse.datadam.demo.examples.operations;
import java.util.List;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import org.springframework.data.annotation.*;
@DatadamDocument(prefix="demo:examples:import:")
public record ImportJob(@Id @DatadamGeneratedId String id, String status, int processed, int rejected, List<String> productIds, @Version Long version) {}
