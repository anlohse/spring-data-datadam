package com.anlohse.datadam.demo.examples.books;
import java.util.List;
import com.anlohse.datadam.spring.repository.DatadamRepository;
import org.springframework.data.repository.NoRepositoryBean;
/** Only passed to diagnostics to explain refusals; Spring must not instantiate this interface. */
@NoRepositoryBean
public interface UnsupportedBookQueries extends DatadamRepository<Book,String> {
    List<Book> findByTitleContaining(String value); List<Book> findByTitleNot(String value);
}
