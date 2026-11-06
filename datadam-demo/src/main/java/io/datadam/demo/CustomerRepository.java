package io.datadam.demo;

import io.datadam.spring.annotation.*;
import io.datadam.spring.repository.DatadamRepository;
import org.springframework.data.domain.*;

public interface CustomerRepository extends DatadamRepository<Customer, String> {
    Page<Customer> findByEmailStartingWithOrderByEmailAsc(String prefix, Pageable pageable);
    long countByEmailStartingWith(String prefix);
    boolean existsByEmailStartingWith(String prefix);
    Slice<Customer> findByEmailStartingWith(String prefix, Pageable pageable);
    java.util.List<Customer> findTop2ByEmailStartingWithOrderByEmailAsc(String prefix);
    Page<Customer> findByCityStartingWithAndAgeNotOrderByAgeAsc(String prefix, int excludedAge, Pageable pageable);
    java.util.List<Customer> findByCityStartingWithIgnoreCaseOrderByAgeAsc(String prefix);
    Page<Customer> findByAgeNot(int excludedAge, Pageable pageable);
    Page<Customer> findByAgeNotIn(java.util.Collection<Integer> excludedAges, Pageable pageable);
    java.util.List<Customer> findByCityStartingWithAndAgeNotInOrEmail(String prefix, java.util.Collection<Integer> excludedAges, String email);
    @IdxQuery(index="demo:customers:city-age",value="city = ?0",descending=true)
    Page<CustomerSummary> summariesByCity(String city,Pageable pageable);
    @FtQuery(index="demo:customers:search",value="@city:{?0}")
    Page<CustomerView> viewsByCity(String city,Pageable pageable);
    @IdxQuery(index="demo:customers:city-age",value="city = ?0",descending=true)
    java.util.List<CustomerSummary> summaryListByCity(String city,Pageable pageable);
    @FtQuery(index="demo:customers:search",value="@city:{?0}")
    java.util.List<CustomerView> viewListByCity(String city,Pageable pageable);
    Page<Customer> findByCityAndAgeBetweenOrderByAgeDesc(String city, int minimum, int maximum, Pageable pageable);

    Slice<Customer> findByCityAndAgeGreaterThanEqualOrderByAgeAsc(String city, int minimum, Pageable pageable);

    java.util.Optional<Customer> findByEmail(String email);

    long countByCityAndAgeLessThan(String city, int maximum);

    boolean existsByEmail(String email);

    java.util.List<Customer> findTop2ByCityAndAgeGreaterThanOrderByAgeDesc(String city, int minimum);

    java.util.List<Customer> findByCityOrderByAgeAsc(String city);

    java.util.List<Customer> findByCityAndAgeGreaterThanEqual(String city, int minimum);

    java.util.Optional<Customer> findFirstByCityOrderByAgeDesc(String city);

    java.util.Optional<Customer> findOneByCity(String city);

    java.util.List<Customer> findByCityIgnoreCaseOrderByAgeDesc(String city);

    Page<Customer> findByCityAndAgeLessThanOrEmail(String city, int maximum, String email, Pageable pageable);

    java.util.List<Customer> findByCityInOrderByAgeAsc(java.util.Collection<String> cities);

    Page<Customer> findByAgeGreaterThanEqual(int minimum, Pageable pageable);

    @IdxQuery(index = "demo:customers:city-age", value = "city = ?0 age >= ?1 age < ?2")
    Page<Customer> byCityAndAge(String city, int minimum, int maximum, Pageable pageable);

    @IdxQuery(index = "demo:customers:city-age", value = "city = ?0", descending = true)
    Page<Customer> byCityDescending(String city, Pageable pageable);

    @FtQuery(index = "demo:customers:search", value = "@city:{?0} @age:[?1 ?2]")
    Page<Customer> searchCityAndAge(String city, int minimum, int maximum, Pageable pageable);
}
