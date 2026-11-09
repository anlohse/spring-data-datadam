package com.anlohse.datadam.demo.examples.migration;
import java.math.BigDecimal;
import java.util.List;
import com.anlohse.datadam.spring.repository.DatadamRepository;
public interface MigrationProductRepository extends DatadamRepository<MigrationProduct,String> {
    List<MigrationProduct> findByCategoryAndPriceGreaterThanEqualOrderByPriceAsc(String category,BigDecimal minimum);
}
