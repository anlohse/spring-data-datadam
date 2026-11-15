package com.anlohse.datadam.demo.examples.catalog;
import java.math.BigDecimal;
import com.anlohse.datadam.spring.annotation.ProjectionProperty;
public record ProductSummary(String name,BigDecimal price,@ProjectionProperty("category") String department) {}
