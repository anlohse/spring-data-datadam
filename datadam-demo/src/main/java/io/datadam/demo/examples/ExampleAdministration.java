package io.datadam.demo.examples;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import io.datadam.demo.examples.books.*;
import io.datadam.demo.examples.catalog.*;
import io.datadam.demo.examples.devices.*;
import io.datadam.demo.examples.migration.*;
import io.datadam.spring.*;
import io.datadam.spring.repository.DatadamQueryDiagnostics;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.*;
import org.springframework.data.domain.*;
import redis.clients.jedis.*;
import static io.datadam.demo.examples.ExampleScenarios.*;

/** CLI-only administration. No administrator pool is registered in the HTTP application. */
public final class ExampleAdministration {
    private ExampleAdministration() {}

    public static JedisPool pool(String user) {
        String variable = "DATADAM_" + user.toUpperCase(Locale.ROOT).replace('-', '_') + "_PASSWORD";
        String password = System.getenv(variable);
        if ((password == null || password.isBlank()) && user.equals("schema")) password = System.getenv("DATADAM_ADMIN_PASSWORD");
        String host = System.getenv().getOrDefault("DATADAM_HOST", "localhost");
        if ((password == null || password.isBlank()) && Boolean.getBoolean("examples.local-credentials")
            && Set.of("localhost", "127.0.0.1").contains(host)) password = "local-" + user + "-password";
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Set " + variable + " for this administrative example");
        return new JedisPool(new HostAndPort(host,
            Integer.parseInt(System.getenv().getOrDefault("DATADAM_PORT", "6380"))),
            DefaultJedisClientConfig.builder().user(user).password(password).connectionTimeoutMillis(5000).socketTimeoutMillis(10000).build());
    }

    static void inspect(DatadamTemplate template, List<String> checks) {
        try (var pool = pool("index-observer")) {
            var commands = new DatadamCommands(pool); var indexes = new DatadamIndexOperations(commands);
            check(indexes.listIndexes().contains("demo:examples:products:sku"), "Observer lists example index names", checks);
            for (var type : List.of(Product.class, Book.class, DeviceState.class)) {
                check(indexes.compare(type).stream().allMatch(c -> c.compatible() && c.actual().status().ready()),
                    type.getSimpleName() + " schema matches and is READY", checks);
            }
            var mismatch = indexes.compare(ProductSchemaMismatch.class).get(0);
            check(!mismatch.compatible(), "Read-only schema drift: " + mismatch.describe(), checks);
        }
        var diagnostics = new DatadamQueryDiagnostics(template);
        var product = diagnostics.explain(ProductRepository.class, method(ProductRepository.class,
            "findByCategoryAndPriceBetweenOrderByPriceAsc", String.class, BigDecimal.class, BigDecimal.class, Pageable.class));
        check(product.index().equals("demo:examples:products:category-price:v1"), "Product plan: " + product.index() + "; " + product.reason(), checks);
        product.candidates().forEach(candidate -> checks.add("Product candidate: " + candidate.index()
            + "; compatible=" + candidate.compatible() + "; " + candidate.reason()));
        var book = diagnostics.explain(BookRepository.class, method(BookRepository.class,
            "findByTitleStartingWithAndPagesNotInOrderByPagesAsc", String.class, Collection.class, Pageable.class));
        check(book.kind() == io.datadam.spring.annotation.DatadamIndex.Kind.SEARCH, "Book plan: " + book.index() + "; " + book.reason(), checks);
        var device = diagnostics.explain(DeviceRepository.class, method(DeviceRepository.class,
            "findByRegionAndLastSeenEpochMillisGreaterThanEqualOrderByLastSeenEpochMillisDesc", String.class, long.class, Pageable.class));
        check(device.index().equals("demo:examples:devices:region-seen"), "Device plan: " + device.index() + "; " + device.reason(), checks);
        expect(IllegalArgumentException.class, () -> diagnostics.explain(ProductRepository.class,
            method(ProductRepository.class, "findByCategoryAndPriceBetweenOrderByPriceAsc", String.class, BigDecimal.class, BigDecimal.class, Pageable.class),
            Sort.by("name")), "Unsupported native sort reports incompatibility", checks);
    }

    /** Exclusive maintenance exercise on an empty, dedicated prefix. Never touches the catalog index. */
    static synchronized void migrate(ApplicationContext context, DatadamCommands app, List<String> checks) {
        var template = context.getBean(DatadamTemplate.class);
        var repository = context.getBean(MigrationProductRepository.class);
        var model = new EntityModel<>(MigrationProduct.class);
        if (!template.keys(model).isEmpty()) throw new IllegalStateException("Migration example prefix must be empty; reconcile previous data first");
        String token = "migration" + UUID.randomUUID().toString().replace("-", "");
        String firstId = token + "A", secondId = token + "B";
        var migration = new IndexMigration(ProductSchemaV2.class, "demo:examples:migrations:products:v1", "demo:examples:migrations:products:v2");
        var unique = new IndexMigration(CategoryUniqueSchema.class, migration.previousIndex(), "demo:examples:migrations:category-unique:v2");
        try (var preparePool = pool("index-migrator"); var retirePool = pool("index-retirer")) {
            var prepareCommands = new DatadamCommands(preparePool); var retireCommands = new DatadamCommands(retirePool);
            var preparer = new DatadamIndexMigrations(prepareCommands); var retirer = new DatadamIndexMigrations(retireCommands);
            if (preparer.inspect(migration).target().exists() || preparer.inspect(unique).target().exists())
                throw new IllegalStateException("Migration example targets already exist; reconcile before running");
            boolean targetOwned = false, uniqueOwned = false, sourceRetired = false;
            try {
                repository.save(new MigrationProduct(firstId, token + "skuA", "First", token, BigDecimal.TEN));
                repository.save(new MigrationProduct(secondId, token + "skuB", "Second", token, BigDecimal.valueOf(20)));
                targetOwned = true;
                check(preparer.prepare(migration, Duration.ofSeconds(30)).readyForUse(), "Prepare v2 and validate backfill while v1 stays READY", checks);
                check(repository.findByCategoryAndPriceGreaterThanEqualOrderByPriceAsc(token, BigDecimal.ZERO).size() == 2,
                    "Original repository still routes explicitly to v1", checks);
                var targetModel = new EntityModel<>(ProductSchemaV2.class);
                var predicate = CompoundQuery.all().where("category", "=", token).where("price", ">=", 0);
                check(template.query(migration.targetIndex(), predicate, PageRequest.of(0, 10), targetModel).getTotalElements() == 2,
                    "Explicit v2 consumer sees backfilled documents", checks);
                uniqueOwned = true;
                expect(DataIntegrityViolationException.class, () -> preparer.prepare(unique, Duration.ofSeconds(30)),
                    "UNIQUE backfill reports FAILED for duplicate categories", checks);
                check(new DatadamIndexOperations(prepareCommands).status(migration.previousIndex()).ready(), "Failed target preserves source READY", checks);
                repository.save(new MigrationProduct(secondId, token + "skuB", "Second", token + "fixed", BigDecimal.valueOf(20)));
                retirer.discardFailedTarget(unique);
                check(preparer.prepare(unique, Duration.ofSeconds(30)).readyForUse(), "Correct data, explicitly discard failed target and resume", checks);
                preparer.validateReady(migration);
                // All consumers in this isolated exercise have now switched to the explicit v2 name.
                retirer.retirePrevious(migration); sourceRetired = true;
                expect(InvalidDataAccessResourceUsageException.class,
                    () -> repository.findByCategoryAndPriceGreaterThanEqualOrderByPriceAsc(token, BigDecimal.ZERO),
                    "Old consumer fails after explicit v1 retirement", checks);
                check(template.query(migration.targetIndex(), predicate, PageRequest.of(0, 10), targetModel).getTotalElements() == 1,
                    "v2 consumer remains usable after retirement", checks);
            } finally {
                // Remove fixture documents before restoring the original empty schema.
                app.execute("DEL", model.key(firstId), model.key(secondId));
                if (uniqueOwned) dropIfPresent(retireCommands, unique.targetIndex());
                if (targetOwned) dropIfPresent(retireCommands, migration.targetIndex());
                if (sourceRetired) {
                    try (var schemaPool = pool("schema")) {
                        new DatadamIndexOperations(new DatadamCommands(schemaPool)).ensureIndexes(MigrationProduct.class, Duration.ofSeconds(30));
                    }
                }
            }
        }
    }
    private static void dropIfPresent(DatadamCommands commands, String index) {
        var indexes = new DatadamIndexOperations(commands);
        try { indexes.status(index); }
        catch (InvalidDataAccessResourceUsageException missing) {
            if (missing.getMessage().equals("ERR no such index '" + index + "'")) return;
            throw missing;
        }
        commands.execute("IDX.DROP", index);
    }
}
