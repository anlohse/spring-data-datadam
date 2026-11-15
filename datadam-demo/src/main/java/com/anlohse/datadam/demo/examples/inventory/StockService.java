package com.anlohse.datadam.demo.examples.inventory;
import org.springframework.stereotype.Service;
@Service
public class StockService {
    private final StockRepository repository;
    public StockService(StockRepository repository){this.repository=repository;}
    public StockItem update(String id,int quantity,Long expectedVersion){
        if(quantity<0||expectedVersion==null||expectedVersion<0)throw new IllegalArgumentException("Nonnegative quantity and expected version are required");
        var current=repository.findById(id).orElseThrow(()->new java.util.NoSuchElementException("Stock item not found"));
        // Send the caller's version to the server CAS, even if a newer value was just read.
        return repository.save(new StockItem(id,current.sku(),quantity,expectedVersion));
    }
    public void delete(String id,Long expectedVersion){
        if(expectedVersion==null||expectedVersion<0)throw new IllegalArgumentException("Expected version is required");
        var current=repository.findById(id).orElseThrow(()->new java.util.NoSuchElementException("Stock item not found"));
        repository.delete(new StockItem(id,current.sku(),current.quantity(),expectedVersion));
    }
}
