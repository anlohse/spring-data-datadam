package example.datadam;

import java.util.UUID;
import com.anlohse.datadam.spring.DatadamCommands;
import org.springframework.dao.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Uses installed artifacts only; input IDs are known before sending writes to allow fixture cleanup. */
@Component
public class ConsumerFlow {
    private final BookRepository books;
    private final DatadamCommands commands;
    public ConsumerFlow(BookRepository books, DatadamCommands commands) { this.books = books; this.commands = commands; }

    public void run() {
        String token = UUID.randomUUID().toString();
        String firstId = token + "A", secondId = token + "B", rejectedId = token + "C";
        try {
            var first = books.save(new Book(firstId, token + "skuA", "Native queries", token, 120, null));
            books.save(new Book(secondId, token + "skuB", "Spring repositories", token, 240, null));
            require(books.findById(first.id()).orElseThrow().equals(first), "CRUD read");
            require(books.findBySku(first.sku()).orElseThrow().id().equals(first.id()), "UNIQUE lookup");
            var page = books.findByAuthorAndPagesBetweenOrderByPagesAsc(token, 100, 300, PageRequest.of(0, 1));
            require(page.getTotalElements() == 2 && page.hasNext() && page.getContent().get(0).id().equals(first.id()), "Native IDX pagination");
            var summaries = books.summaries(token, 100, 300, PageRequest.of(0, 1));
            require(summaries.getTotalElements() == 2 && summaries.getNumberOfElements() == 1, "Native FT projection");
            try {
                books.save(new Book(rejectedId, first.sku(), "Duplicate", token, 100, null));
                throw new IllegalStateException("Expected UNIQUE rejection");
            } catch (DuplicateKeyException expected) { /* Native constraint, no replacement. */ }
            var updated = books.save(new Book(first.id(), first.sku(), "Updated", token, 150, first.version()));
            require(updated.version() == first.version() + 1, "Versioned update");
            try { books.save(first); throw new IllegalStateException("Expected stale version rejection"); }
            catch (OptimisticLockingFailureException expected) { /* Caller must reload and decide. */ }
            books.delete(updated);
            require(books.findById(first.id()).isEmpty(), "Versioned delete");
            System.out.println("CONSUMER_OK crud=true idx=true ftProjection=true unique=true version=true");
        } finally {
            // Fixture cleanup only; business mutations above use repository version checks.
            commands.execute("DEL", "consumer:book:" + firstId, "consumer:book:" + secondId, "consumer:book:" + rejectedId);
        }
    }
    private static void require(boolean condition, String operation) {
        if (!condition) throw new IllegalStateException("Consumer validation failed: " + operation);
    }
}
