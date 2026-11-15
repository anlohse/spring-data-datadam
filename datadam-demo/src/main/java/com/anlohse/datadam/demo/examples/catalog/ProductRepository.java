package com.anlohse.datadam.demo.examples.catalog;
import java.math.BigDecimal;
import java.util.*;

import com.anlohse.datadam.spring.annotation.FtQuery;
import com.anlohse.datadam.spring.annotation.IdxQuery;
import com.anlohse.datadam.spring.repository.DatadamRepository;
import org.springframework.data.domain.*;
public interface ProductRepository extends DatadamRepository<Product,String> {
    Optional<Product> findBySku(String sku);
    Page<Product> findByCategoryAndPriceBetweenOrderByPriceAsc(String category,BigDecimal minimum,BigDecimal maximum,Pageable page);
    Slice<Product> findSliceByCategoryAndPriceBetweenOrderByPriceAsc(String category,BigDecimal minimum,BigDecimal maximum,Pageable page);
    List<Product> findAllByCategoryAndPriceBetweenOrderByPriceAsc(String category,BigDecimal minimum,BigDecimal maximum,Pageable page);
    long countByCategoryAndPriceBetween(String category,BigDecimal minimum,BigDecimal maximum);
    @IdxQuery(index="demo:examples:products:category-price:v1",value="category = ?0 price >= ?1 price <= ?2")
    Page<ProductSummary> summaries(String category,BigDecimal minimum,BigDecimal maximum,Pageable page);
    @FtQuery(index="demo:examples:products:search",value="@category:{?0} @price:[?1 ?2]")
    Page<Product> search(String category,BigDecimal minimum,BigDecimal maximum,Pageable page);
    @FtQuery(index="demo:examples:products:search",value="@category:{?0} @price:[?1 ?2]")
    Page<ProductSummary> searchSummaries(String category,BigDecimal minimum,BigDecimal maximum,Pageable page);
}
