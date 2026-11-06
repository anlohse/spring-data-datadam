package io.datadam.demo;

import java.util.*;
import io.datadam.spring.annotation.*;
import io.datadam.spring.repository.DatadamRepository;
import org.springframework.data.domain.*;

public interface HashCustomerRepository extends DatadamRepository<HashCustomer, String> {
    Page<HashCustomer> findByEmailStartingWithOrderByEmailAsc(String prefix, Pageable pageable);
    long countByEmailStartingWith(String prefix);
    boolean existsByEmailStartingWith(String prefix);
    Slice<HashCustomer> findByEmailStartingWith(String prefix, Pageable pageable);
    List<HashCustomer> findTop2ByEmailStartingWithOrderByEmailAsc(String prefix);
    Page<HashCustomer> findByAgeNot(int excludedAge, Pageable pageable);
    Page<HashCustomer> findByAgeNotIn(Collection<Integer> excludedAges, Pageable pageable);
    @IdxQuery(index="demo:hash:customers:city-age",value="city = ?0",descending=true)
    Page<CustomerSummary> summariesByCity(String city,Pageable pageable);
    @FtQuery(index="demo:hash:customers:search",value="@home:{?0}")
    Page<CustomerView> viewsByCity(String city,Pageable pageable);
    @IdxQuery(index="demo:hash:customers:city-age",value="city = ?0",descending=true)
    List<CustomerSummary> summaryListByCity(String city,Pageable pageable);
    @FtQuery(index="demo:hash:customers:search",value="@home:{?0}")
    List<CustomerView> viewListByCity(String city,Pageable pageable);
    Optional<HashCustomer> findByEmail(String email);
    boolean existsByEmail(String email);
    long countByCityAndAgeLessThan(String city, int maximum);
    Page<HashCustomer> findByCityAndAgeBetweenOrderByAgeDesc(String city, int minimum, int maximum, Pageable pageable);
    Slice<HashCustomer> findByCityAndAgeGreaterThanEqualOrderByAgeAsc(String city, int minimum, Pageable pageable);
    List<HashCustomer> findTop2ByCityAndAgeGreaterThanOrderByAgeDesc(String city, int minimum);
    Page<HashCustomer> findByAgeGreaterThanEqual(int minimum, Pageable pageable);
    @IdxQuery(index="demo:hash:customers:city-age", value="city = ?0 age >= ?1 age < ?2")
    Page<HashCustomer> byCityAndAge(String city, int minimum, int maximum, Pageable pageable);
    @FtQuery(index="demo:hash:customers:search", value="@home:{?0} @years:[?1 ?2]")
    Page<HashCustomer> searchCityAndAge(String city, int minimum, int maximum, Pageable pageable);
    @FtQuery(index="demo:hash:customers:search", value="@tag:{?0}")
    List<HashCustomer> byLabel(String label, Pageable pageable);
}
