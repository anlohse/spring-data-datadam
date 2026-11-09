package com.anlohse.datadam.demo.examples.books;
import java.util.*;
import com.anlohse.datadam.spring.repository.DatadamRepository;
import org.springframework.data.domain.*;
public interface BookRepository extends DatadamRepository<Book,String> {
    Page<Book> findByTitleStartingWithOrderByTitleAsc(String prefix,Pageable page);
    List<Book> findByTitleStartingWithIgnoreCaseOrderByPagesAsc(String prefix);
    Page<Book> findByTitleStartingWithAndPagesBetweenOrderByPagesAsc(String prefix,int minimum,int maximum,Pageable page);
    List<Book> findByTitleIn(Collection<String> titles);
    Page<Book> findByTitleStartingWithAndPagesNotInOrderByPagesAsc(String prefix,Collection<Integer> excluded,Pageable page);
    List<Book> findByTitleStartingWithAndPagesNotOrTitle(String prefix,int excluded,String alternative);
}
