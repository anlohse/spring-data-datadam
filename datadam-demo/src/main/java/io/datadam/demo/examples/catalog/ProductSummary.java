package io.datadam.demo.examples.catalog;
import java.math.BigDecimal;
import io.datadam.spring.annotation.ProjectionProperty;
public record ProductSummary(String name,BigDecimal price,@ProjectionProperty("category") String department) {}
