package io.datadam.demo.examples.inventory;
import io.datadam.spring.repository.DatadamRepository;
public interface StockRepository extends DatadamRepository<StockItem,String> {}
