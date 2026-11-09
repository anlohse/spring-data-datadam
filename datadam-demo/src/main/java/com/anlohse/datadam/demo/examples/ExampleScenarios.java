package com.anlohse.datadam.demo.examples;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import com.anlohse.datadam.demo.examples.books.Book;
import com.anlohse.datadam.demo.examples.books.BookRepository;
import com.anlohse.datadam.demo.examples.books.UnsupportedBookQueries;
import com.anlohse.datadam.demo.examples.catalog.Product;
import com.anlohse.datadam.demo.examples.catalog.ProductRepository;
import com.anlohse.datadam.demo.examples.devices.DeviceRepository;
import com.anlohse.datadam.demo.examples.devices.DeviceState;
import com.anlohse.datadam.demo.examples.devices.UnsupportedDeviceQueries;
import com.anlohse.datadam.demo.examples.documents.CartRepository;
import com.anlohse.datadam.demo.examples.documents.PreferencesRepository;
import com.anlohse.datadam.demo.examples.documents.ShoppingCart;
import com.anlohse.datadam.demo.examples.documents.UserPreferences;
import com.anlohse.datadam.demo.examples.inventory.StockItem;
import com.anlohse.datadam.demo.examples.inventory.StockRepository;
import com.anlohse.datadam.demo.examples.inventory.StockService;
import com.anlohse.datadam.demo.examples.lifecycle.*;
import com.anlohse.datadam.demo.examples.operations.ImportJob;
import com.anlohse.datadam.demo.examples.operations.ImportJobRepository;
import com.anlohse.datadam.demo.examples.operations.ImportService;
import com.anlohse.datadam.demo.examples.publishing.Article;
import com.anlohse.datadam.demo.examples.publishing.ArticleRepository;
import com.anlohse.datadam.spring.DatadamCommands;
import com.anlohse.datadam.spring.DatadamTemplate;
import com.anlohse.datadam.spring.EntityModel;
import com.anlohse.datadam.spring.JsonPath;
import com.fasterxml.jackson.core.type.TypeReference;
import com.anlohse.datadam.demo.examples.books.*;
import com.anlohse.datadam.demo.examples.catalog.*;
import com.anlohse.datadam.demo.examples.devices.*;
import com.anlohse.datadam.demo.examples.documents.*;
import com.anlohse.datadam.demo.examples.inventory.*;
import com.anlohse.datadam.demo.examples.lifecycle.*;
import com.anlohse.datadam.demo.examples.operations.*;
import com.anlohse.datadam.demo.examples.publishing.*;
import com.anlohse.datadam.spring.callback.AfterSaveCallbackException;
import com.anlohse.datadam.spring.repository.DatadamQueryDiagnostics;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Executable tutorials. Each invocation owns a UUID namespace and deletes only its own keys. */
@Component
public class ExampleScenarios {
    public static final List<String> SCENARIOS = List.of("0.1", "1.1.1", "1.2.1", "1.3.1", "2.1.1",
        "3.1.1", "3.2.1", "4.1.1", "4.2.1", "5.1.1", "5.2.1", "6.1.1", "6.2.1", "7.1.1", "7.3.1");
    private final ApplicationContext context;
    private final DatadamCommands commands;
    private final DatadamTemplate template;

    public ExampleScenarios(ApplicationContext context, DatadamCommands commands, DatadamTemplate template) {
        this.context = context;
        this.commands = commands;
        this.template = template;
    }

    public ExampleReport run(String scenario, int volume) {
        if (!SCENARIOS.contains(scenario)) throw new IllegalArgumentException("Unknown scenario: " + scenario);
        if (volume < 1 || volume > 1000) throw new IllegalArgumentException("Volume must be between 1 and 1000");
        try (var run = new Run()) {
            switch (scenario) {
                case "0.1" -> catalog(run);
                case "1.1.1" -> reservations(run);
                case "1.2.1" -> identity(run);
                case "1.3.1" -> articles(run);
                case "2.1.1" -> stock(run);
                case "3.1.1" -> preferences(run);
                case "3.2.1" -> cart(run);
                case "4.1.1" -> hash(run);
                case "4.2.1" -> hashQueries(run);
                case "5.1.1" -> projections(run);
                case "5.2.1" -> books(run);
                case "6.1.1" -> ExampleAdministration.inspect(template, run.checks);
                case "6.2.1" -> ExampleAdministration.migrate(context, commands, run.checks);
                case "7.1.1" -> imports(run);
                case "7.3.1" -> workload(run, volume);
                default -> throw new IllegalStateException(scenario);
            }
            return new ExampleReport(scenario, "PASSED", List.copyOf(run.checks));
        }
    }

    private <T> T bean(Class<T> type) { return context.getBean(type); }
    private Product product(Run run, String suffix, int price) {
        return run.keep(bean(ProductRepository.class).save(new Product(null, run.token + suffix, "Product " + suffix,
            run.token, BigDecimal.valueOf(price), List.of("Java", "Spring"))), Product.class);
    }
    private DeviceState device(Run run, String region, Boolean online, String id) {
        return run.keep(bean(DeviceRepository.class).save(new DeviceState(id, "Sensor", region, 1000L, online,
            "Java;Spring", List.of("temperature", "humidity"), Map.of("interval", 10), null, 60000L)), DeviceState.class);
    }

    private void catalog(Run run) {
        var repository = bean(ProductRepository.class);
        var first = product(run, "A", 10); product(run, "B", 20);
        check(repository.findBySku(first.sku()).orElseThrow().id().equals(first.id()), "UNIQUE SKU lookup", run);
        var page = repository.findByCategoryAndPriceBetweenOrderByPriceAsc(run.token, BigDecimal.ZERO,
            BigDecimal.valueOf(30), PageRequest.of(0, 1));
        check(page.getTotalElements() == 2 && page.getContent().get(0).id().equals(first.id()) && page.hasNext(),
            "IDX compound range, native ordering and pagination", run);
        check(repository.search(run.token, BigDecimal.ZERO, BigDecimal.valueOf(30), PageRequest.of(0, 10))
            .getTotalElements() == 2, "FT TAG/NUMERIC", run);
        expect(DuplicateKeyException.class, () -> repository.save(new Product(null, first.sku(), "Duplicate",
            run.token, BigDecimal.ONE, List.of())), "Duplicate SKU refused without replacement", run);
    }

    private void reservations(Run run) {
        var repository = bean(ReservationRepository.class);
        var normal = run.keep(repository.save(new TemporaryReservation(null, run.token + "default", "guest", null)), TemporaryReservation.class);
        check(ttl(TemporaryReservation.class, normal.id()) > 0, "Default 60-second TTL", run);
        var expiring = run.keep(repository.save(new TemporaryReservation(null, run.token, "guest", 1000L)), TemporaryReservation.class);
        check(repository.findByResource(run.token).size() == 1, "Reservation indexed before expiry", run);
        waitUntilMissing(repository, expiring.id());
        check(repository.findByResource(run.token).isEmpty(), "Expired reservation removed from IDX", run);
        var ft = DatadamCommands.array(commands.execute("FT.SEARCH", "demo:examples:reservations:search", "@resource:{" + run.token + "}", "LIMIT", 0, 0));
        check(((Number) ft.get(0)).longValue() == 0, "Expired reservation removed from FT", run);
        check(repository.persist(normal.id()) && ttl(TemporaryReservation.class, normal.id()) == -1,
            "Explicit persist removes expiry", run);
    }
    private void waitUntilMissing(ReservationRepository repository, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (repository.findById(id).isPresent()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Reservation did not expire");
            try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
    }

    private void identity(Run run) {
        var repository = bean(TicketRepository.class);
        var ticket = run.keep(repository.save(new SupportTicket(null, "Cannot connect", "OPEN")), SupportTicket.class);
        check(ticket.id().startsWith("TKT-"), "Named ticket ID generator", run);
        var updated = repository.save(new SupportTicket(ticket.id(), ticket.subject(), "RESOLVED"));
        check(updated.id().equals(ticket.id()) && repository.findById(ticket.id()).orElseThrow().status().equals("RESOLVED"),
            "Returned ID reused for read and update", run);
        var external = new ExternalTicket(run.token, "External immutable import");
        run.keep(bean(ExternalTicketRepository.class).save(external), ExternalTicket.class);
        check(bean(ExternalTicketRepository.class).findById(external.id()).orElseThrow().id().equals(external.id()),
            "Assigned external ID serialized and read", run);
        expect(DuplicateKeyException.class, () -> bean(ExternalTicketRepository.class).save(external),
            "Persistable exclusive import refuses collision", run);
    }

    private void articles(Run run) {
        var repository = bean(ArticleRepository.class);
        var article = run.keep(repository.save(new Article(null, "  Spring Data  ", "Content", null, null, null, null)), Article.class);
        check(article.title().equals("Spring Data") && article.createdAt() != null && article.createdBy() != null,
            "BeforeConvert normalization, auditing and BeforeSave validation", run);
        var updated = repository.save(article.content("Updated", "New content"));
        check(updated.createdAt().equals(article.createdAt()) && updated.createdBy().equals(article.createdBy())
            && !updated.modifiedAt().isBefore(article.modifiedAt()), "Editing preserves creation audit", run);
        expect(IllegalArgumentException.class, () -> repository.save(article.content("", "Content")), "BeforeSave rejects blank title", run);
        check(repository.findById(article.id()).orElseThrow().title().equals("Updated"), "Rejected edit did not replace article", run);
        try {
            repository.save(article.content("Acknowledged", "[fail-after-save]"));
            throw new IllegalStateException("Expected AfterSave failure");
        } catch (AfterSaveCallbackException failure) {
            check(failure.getKey().equals(new EntityModel<>(Article.class).key(article.id()))
                && repository.findById(article.id()).orElseThrow().title().equals("Acknowledged"),
                "AfterSave failure reconciled by ID: write already acknowledged", run);
        }
    }

    private void stock(Run run) {
        var repository = bean(StockRepository.class); var service = bean(StockService.class);
        var original = run.keep(repository.save(new StockItem(null, run.token, 10, null)), StockItem.class);
        var updated = service.update(original.id(), 9, original.version());
        check(updated.version() == original.version() + 1, "Compare-and-set advances version", run);
        expect(OptimisticLockingFailureException.class, () -> service.update(original.id(), 8, original.version()), "Stale update refused", run);
        expect(OptimisticLockingFailureException.class, () -> service.delete(original.id(), original.version()), "Stale delete refused", run);
        check(repository.findById(original.id()).orElseThrow().quantity() == 9, "Reload reconciles current quantity", run);
        service.delete(original.id(), updated.version());
        check(repository.findById(original.id()).isEmpty(), "Delete with current version", run);
    }

    private void preferences(Run run) {
        var value = run.keep(bean(PreferencesRepository.class).save(new UserPreferences(null, "guest",
            Map.of("theme", "light", "notifications", Map.of("email", true)), List.of("Java", "Spring"), 60000L)), UserPreferences.class);
        var json = template.json(UserPreferences.class); var theme = JsonPath.of("$.settings.theme", String.class);
        long expires = expiry(UserPreferences.class, value.id());
        check(json.read(value.id(), theme).values().equals(List.of("light")), "Typed nested JSONPath read", run);
        json.set(value.id(), theme, null);
        check(json.read(value.id(), theme).values().size() == 1 && json.read(value.id(), theme).values().get(0) == null,
            "JSON null is a match", run);
        json.remove(value.id(), theme);
        check(json.read(value.id(), theme).keyExists() && json.read(value.id(), theme).values().isEmpty(), "Absent path differs from null", run);
        check(!json.read(run.token, theme).keyExists(), "Absent document differs from absent path", run);
        check(json.read(value.id(), JsonPath.of("$.favorites[*]", String.class)).values().size() == 2,
            "Array JSONPath matches", run);
        check(expiry(UserPreferences.class, value.id()) == expires, "Partial writes preserve absolute TTL", run);
        check(json.read(value.id(), JsonPath.of("$.settings.notifications.email", Boolean.class)).values().equals(List.of(true)),
            "Unrelated nested preference remains unchanged", run);
        expect(InvalidDataAccessApiUsageException.class, () -> template.json(StockItem.class).set("unused", JsonPath.of("$.quantity", Integer.class), 1),
            "Versioned partial mutation refused", run);
        expect(InvalidDataAccessApiUsageException.class, () -> template.json(Article.class).set("unused", JsonPath.of("$.title", String.class), "x"),
            "Audited partial mutation refused", run);
        expect(InvalidDataAccessApiUsageException.class, () -> template.json(Product.class).set("unused", JsonPath.of("$.name", String.class), "x"),
            "Partial mutation cannot bypass write callback", run);
    }

    private void cart(Run run) {
        var value = run.keep(bean(CartRepository.class).save(new ShoppingCart(null, "guest", List.of(), 1,
            Map.of("city", "Curitiba", "note", "Door"))), ShoppingCart.class);
        var json = template.json(ShoppingCart.class);
        var items = JsonPath.of("$.items", new TypeReference<List<Map<String, Object>>>() {});
        var quantity = JsonPath.of("$.quantity", Integer.class);
        json.increment(value.id(), quantity, 2); json.multiply(value.id(), quantity, 3);
        check(json.read(value.id(), quantity).values().equals(List.of(9)), "Native numeric increment and multiply", run);
        json.arrayAppend(value.id(), items, Arrays.asList(Map.of("sku", "A"), null));
        var popped = json.arrayPop(value.id(), items).get(0);
        check(popped.popped() && popped.value() == null, "Popped JSON null differs from empty array", run);
        json.arrayInsert(value.id(), items, 0, List.of(Map.of("sku", "B")));
        check(json.arrayLength(value.id(), items).equals(List.of(2L)), "Array append, insert and length", run);
        json.arrayTrim(value.id(), items, 0, 0); json.arrayPop(value.id(), items);
        check(!json.arrayPop(value.id(), items).get(0).popped(), "Empty array pop", run);
        var patch = new LinkedHashMap<String, Object>(); patch.put("city", "São Paulo"); patch.put("note", null);
        json.merge(value.id(), JsonPath.of("$.delivery", Map.class), patch);
        check(json.read(value.id(), JsonPath.of("$.delivery.note", String.class)).values().isEmpty(), "Merge removes null-valued object member", run);
        json.arrayAppend(value.id(), items, List.of(Map.of("sku", "C")));
        expect(InvalidDataAccessResourceUsageException.class, () -> json.arrayInsert(value.id(), items, 99, List.of(Map.of("sku", "D"))),
            "Later operation fails on out-of-bounds array index", run);
        check(json.arrayLength(value.id(), items).equals(List.of(1L)), "Earlier append remains: sequence has no rollback", run);
    }

    private void hash(Run run) {
        var repository = bean(DeviceRepository.class); var device = device(run, run.token, true, null);
        var stored = repository.findById(device.id()).orElseThrow();
        check(stored.lastSeenEpochMillis() == 1000L && Boolean.TRUE.equals(stored.online())
            && stored.configuration().equals(Map.of("interval", 10)), "HASH scalar and object values round-trip", run);
        long expires = expiry(DeviceState.class, device.id());
        var updated = repository.save(new DeviceState(device.id(), device.name(), device.region(), 2000L, false,
            device.labels(), device.capabilities(), null, device.version(), null));
        check(updated.version() == device.version() + 1 && expiry(DeviceState.class, device.id()) == expires,
            "HASH optimistic locking and TTL preservation", run);
        check(commands.execute("HGET", new EntityModel<>(DeviceState.class).key(device.id()), "configuration") == null,
            "Null property removes HASH field", run);
        check(repository.findById(device.id()).orElseThrow().capabilities().equals(device.capabilities()),
            "HASH collections decoded with declared types", run);
        expect(OptimisticLockingFailureException.class, () -> repository.save(device), "HASH stale save refused", run);
        var product = run.keep(bean(ProductRepository.class).save(new Product(device.id(), run.token, "JSON sibling",
            run.token, BigDecimal.ONE, List.of())), Product.class);
        check(bean(ProductRepository.class).findById(product.id()).isPresent() && repository.findById(device.id()).isPresent(),
            "JSON and HASH coexist with the same logical ID and different prefixes", run);
    }

    private void hashQueries(Run run) {
        var repository = bean(DeviceRepository.class); var region = "west," + run.token;
        device(run, region, true, null); device(run, region, false, null);
        check(repository.findByRegionAndLastSeenEpochMillisGreaterThanEqualOrderByLastSeenEpochMillisDesc(region, 0, PageRequest.of(0, 10))
            .getTotalElements() == 2, "IDX matches whole HASH region including comma", run);
        var page = repository.findByRegionAndLastSeenEpochMillisGreaterThanEqualOrderByLastSeenEpochMillisDesc(region, 0, PageRequest.of(0, 1));
        check(page.getNumberOfElements() == 1 && page.getTotalElements() == 2 && page.hasNext(), "HASH range pagination and total", run);
        check(repository.byLabelInRegion("Java", run.token, PageRequest.of(0, 10)).size() == 2,
            "FT alias tag and semicolon separator match complete token", run);
        check(repository.byLabelInRegion("ava", run.token, PageRequest.of(0, 10)).isEmpty(),
            "FT TAG does not match token substring", run);
        var summaries = repository.summaries(run.token, 0, PageRequest.of(0, 10));
        check(summaries.stream().anyMatch(d -> d.getRegion().equals(region)), "FT region token differs from IDX whole-field equality", run);
        var diagnostic = new DatadamQueryDiagnostics(template);
        expect(IllegalArgumentException.class, () -> diagnostic.explain(UnsupportedDeviceQueries.class,
            method(UnsupportedDeviceQueries.class, "findByLabels", String.class)), "Derived split TAG equality refused", run);
    }

    private void projections(Run run) {
        var product = product(run, "summary", 12);
        product(run, "summaryB", 20);
        var repository = bean(ProductRepository.class);
        var result = repository.summaries(run.token, BigDecimal.ZERO, BigDecimal.valueOf(30), PageRequest.of(0, 1));
        check(result.getContent().get(0).department().equals(product.category()) && result.getContent().get(0).name().equals(product.name()),
            "JSON record projection maps category to department", run);
        var ft = repository.searchSummaries(run.token, BigDecimal.ZERO, BigDecimal.valueOf(30), PageRequest.of(0, 1));
        check(result.getTotalElements() == 2 && result.hasNext() && ft.getTotalElements() == 2 && ft.getNumberOfElements() == 1,
            "JSON IDX/FT projections preserve pagination and total", run);
        device(run, run.token, null, null);
        device(run, run.token, null, null);
        var device = bean(DeviceRepository.class).exactRegionSummaries(run.token, 0, PageRequest.of(0, 10)).getContent().get(0);
        check(device.getOnline() == null && device.getLastSeen() == 1000L && device.getRegion().equals(run.token),
            "HASH closed interface projection preserves absent nullable property and mapped name", run);
        var hashFt = bean(DeviceRepository.class).summaries(run.token, 0, PageRequest.of(0, 1));
        check(hashFt.getTotalElements() == 2 && hashFt.hasNext() && hashFt.getContent().get(0).getOnline() == null,
            "HASH FT projection preserves aliases, nullable values, pagination and total", run);
    }

    private void books(Run run) {
        var repository = bean(BookRepository.class); String prefix = run.token + "*}|";
        var first = run.keep(repository.save(new Book(null, prefix + " Alpha", "Author", 20)), Book.class);
        var second = run.keep(repository.save(new Book(null, prefix + " Beta", "Author", 30)), Book.class);
        run.keep(repository.save(new Book(null, prefix + " Missing", "Author", null)), Book.class);
        check(repository.findByTitleStartingWithOrderByTitleAsc(prefix, PageRequest.of(0, 10)).getTotalElements() == 3,
            "Literal prefix escapes search metacharacters", run);
        var folded = repository.findByTitleStartingWithIgnoreCaseOrderByPagesAsc(prefix.toUpperCase(Locale.ROOT));
        check(folded.size() == 3 && folded.stream().map(Book::pages).filter(Objects::nonNull).toList().equals(List.of(20, 30)),
            "ASCII IgnoreCase with native numeric ordering; null pages remain eligible", run);
        check(repository.findByTitleStartingWithAndPagesBetweenOrderByPagesAsc(prefix, 10, 25, PageRequest.of(0, 10)).getTotalElements() == 1,
            "Prefix AND numeric range", run);
        check(repository.findByTitleIn(List.of(first.title(), second.title())).size() == 2, "TAG In", run);
        var excluded = repository.findByTitleStartingWithAndPagesNotInOrderByPagesAsc(prefix, List.of(20), PageRequest.of(0, 10));
        check(excluded.getTotalElements() == 1 && excluded.getContent().get(0).id().equals(second.id()),
            "Numeric NotIn requires presence and excludes missing pages", run);
        check(repository.findByTitleStartingWithAndPagesNotOrTitle(prefix, 20, first.title()).size() == 2,
            "AND/OR with numeric Not", run);
        var diagnostics = new DatadamQueryDiagnostics(template);
        expect(IllegalArgumentException.class, () -> diagnostics.explain(UnsupportedBookQueries.class,
            method(UnsupportedBookQueries.class, "findByTitleContaining", String.class)), "Unsupported Contains refused by compiler", run);
        expect(IllegalArgumentException.class, () -> diagnostics.explain(UnsupportedBookQueries.class,
            method(UnsupportedBookQueries.class, "findByTitleNot", String.class)), "String Not refused by compiler", run);
        expect(IllegalArgumentException.class, () -> repository.findByTitleStartingWithOrderByTitleAsc("", PageRequest.of(0, 10)),
            "Empty prefix refused before command", run);
    }

    private void imports(Run run) {
        var service = bean(ImportService.class);
        var job = run.keep(service.create(), ImportJob.class);
        var rows = List.of(new Product(null, run.token, "First", run.token, BigDecimal.ONE, List.of()),
            new Product(null, run.token, "Duplicate", run.token, BigDecimal.ONE, List.of()),
            new Product(null, run.token + "B", "Second", run.token, BigDecimal.TEN, List.of()));
        try {
            var finished = service.run(job.id(), rows);
            check(finished.status().equals("PARTIAL") && finished.processed() == 2 && finished.rejected() == 1,
                "Import records progress and controlled duplicate rejection", run);
            check(bean(ImportJobRepository.class).findById(job.id()).orElseThrow().equals(finished), "Import status can be polled by known ID", run);
            expect(IllegalArgumentException.class, () -> service.run(job.id(), rows), "Completed import cannot be silently replayed", run);
        } finally {
            bean(ImportJobRepository.class).findById(job.id()).ifPresent(current -> current.productIds().forEach(id -> run.track(Product.class, id)));
        }
        var indicator = context.getBean("datadamHealthIndicator", org.springframework.boot.actuate.health.HealthIndicator.class);
        check(indicator.health().getStatus().equals(org.springframework.boot.actuate.health.Status.UP), "Datadam health remains UP", run);
        var registry = bean(io.micrometer.core.instrument.MeterRegistry.class);
        check(registry.find("datadam.command.errors").tag("outcome", "duplicate_key").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum() > 0,
            "Duplicate rejection counted in telemetry", run);
        check(registry.find("datadam.pool.max").gauge() != null, "Configured pool limit exposed without credentials", run);
    }

    private void workload(Run run, int volume) {
        for (int i = 0; i < volume; i++) { product(run, "-" + i, i); device(run, run.token, true, null); }
        int size = Math.min(10, volume); var request = PageRequest.of(0, size);
        var repository = bean(ProductRepository.class); var maximum = BigDecimal.valueOf(volume);
        var page = repository.findByCategoryAndPriceBetweenOrderByPriceAsc(run.token, BigDecimal.ZERO, maximum, request);
        var slice = repository.findSliceByCategoryAndPriceBetweenOrderByPriceAsc(run.token, BigDecimal.ZERO, maximum, request);
        var limited = repository.findAllByCategoryAndPriceBetweenOrderByPriceAsc(run.token, BigDecimal.ZERO, maximum, request);
        check(page.getTotalElements() == volume && page.getNumberOfElements() == size, "Product Page: content plus total=" + volume, run);
        check(slice.getNumberOfElements() == size && slice.hasNext() == (volume > size), "Product Slice: look-ahead without total", run);
        check(limited.size() == size && repository.countByCategoryAndPriceBetween(run.token, BigDecimal.ZERO, maximum) == volume,
            "Bounded List and explicit count", run);
        check(repository.summaries(run.token, BigDecimal.ZERO, maximum, request).getTotalElements() == volume,
            "Product projection returns same total with selected fields", run);
        var devices = bean(DeviceRepository.class);
        check(devices.findByRegionAndLastSeenEpochMillisGreaterThanEqualOrderByLastSeenEpochMillisDesc(run.token, 0, request).getTotalElements() == volume
            && devices.exactRegionSummaries(run.token, 0, request).getTotalElements() == volume,
            "HASH Page and projection agree; documents per format=" + volume, run);
    }

    private long ttl(Class<?> type, String id) { return ((Number) commands.execute("PTTL", new EntityModel<>(type).key(id))).longValue(); }
    private long expiry(Class<?> type, String id) { return ((Number) commands.execute("PEXPIRETIME", new EntityModel<>(type).key(id))).longValue(); }
    static java.lang.reflect.Method method(Class<?> type, String name, Class<?>... arguments) {
        try { return type.getMethod(name, arguments); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    static void check(boolean condition, String message, List<String> checks) {
        if (!condition) throw new IllegalStateException("Example check failed: " + message);
        checks.add(message);
    }
    private static void check(boolean condition, String message, Run run) { check(condition, message, run.checks); }
    static void expect(Class<? extends Throwable> type, Runnable action, String message, List<String> checks) {
        try { action.run(); } catch (RuntimeException e) {
            if (type.isInstance(e)) { checks.add(message); return; }
            throw e;
        }
        throw new IllegalStateException("Expected " + type.getSimpleName() + ": " + message);
    }
    private static void expect(Class<? extends Throwable> type, Runnable action, String message, Run run) { expect(type, action, message, run.checks); }

    private final class Run implements AutoCloseable {
        final String token = "ex" + UUID.randomUUID().toString().replace("-", "");
        final Set<String> keys = new LinkedHashSet<>();
        final List<String> checks = new ArrayList<>();
        <T> T keep(T value, Class<T> type) { track(type, new EntityModel<>(type).id(value).toString()); return value; }
        void track(Class<?> type, String id) {
            String key = new EntityModel<>(type).key(id);
            if (!key.startsWith("demo:examples:")) throw new IllegalArgumentException("Not an example key");
            keys.add(key);
        }
        @Override public void close() { for (String key : keys) commands.execute("DEL", key); }
    }
}
