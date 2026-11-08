package io.datadam.demo.examples.migration;
import java.math.BigDecimal;
import java.util.List;
import io.datadam.spring.repository.DatadamRepository;
public interface MigrationProductRepository extends DatadamRepository<MigrationProduct,String> {
    List<MigrationProduct> findByCategoryAndPriceGreaterThanEqualOrderByPriceAsc(String category,BigDecimal minimum);
}
