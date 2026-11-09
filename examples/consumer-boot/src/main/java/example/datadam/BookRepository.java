package example.datadam;

import java.util.Optional;

import com.anlohse.datadam.spring.annotation.FtQuery;
import com.anlohse.datadam.spring.repository.DatadamRepository;
import org.springframework.data.domain.*;

public interface BookRepository extends DatadamRepository<Book, String> {
    Optional<Book> findBySku(String sku);
    Page<Book> findByAuthorAndPagesBetweenOrderByPagesAsc(String author, int minimum, int maximum, Pageable page);
    @FtQuery(index = "consumer:books:search", value = "@writer:{?0} @length:[?1 ?2]")
    Page<BookSummary> summaries(String author, int minimum, int maximum, Pageable page);
}
