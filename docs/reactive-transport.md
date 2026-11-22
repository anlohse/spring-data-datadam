# Reactive native operations

Milestone 10 uses **Lettuce 6.6.0.RELEASE with RESP2**, managed by Spring Boot 3.5.16. `ReactiveDatadamOperations` exposes native JSON, IDX, and FT commands. `ReactiveDatadamTemplate` and `ReactiveDatadamRepository` add entity mapping, versioned writes, TTL, auditing, and projections. [Lettuce reactive API](https://redis.github.io/lettuce/user-guide/reactive-api/) and [custom command dispatch](https://github.com/redis/lettuce/wiki/Custom-commands%2C-outputs-and-command-mechanics).

## Dependencies and connection ownership

Add `io.lettuce:lettuce-core` and `io.projectreactor:reactor-core` alongside `com.anlohse.datadam:spring-data-datadam`. Both reactive dependencies are optional in the library POM. A consumer using Boot 3.5.16 dependency management can declare them without explicit versions.

Configure the client before opening a connection:

```java
RedisClient client = RedisClient.create();
client.setOptions(ClientOptions.builder()
    .protocolVersion(ProtocolVersion.RESP2)
    .autoReconnect(false)
    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
    .build());
```

Open a database-zero connection with `client.connectAsync(StringCodec.UTF8, uri)`. Set credentials, TLS, and a positive command timeout on the URI for your installation. Pass the resulting `StatefulRedisConnection<String, String>` to `ReactiveDatadamOperations`. The constructor verifies the protocol and reconnect settings. Keep auto-flushing enabled and dedicate the connection to ordinary commands. The application owns the connection and client and closes them through `closeAsync()` and `shutdownAsync()`.

For an established connection, the operations compose with Reactor:

```java
ReactiveDatadamOperations operations = new ReactiveDatadamOperations(connection);
Mono<Boolean> saved = operations.jsonSet("telemetry:1", "$", "{\"device\":\"sensor-a\",\"value\":20}");
Mono<String> json = saved.then(operations.jsonGet("telemetry:1", "$.value"));

CompoundQuery range = CompoundQuery.all().where("value", ">=", 10).where("value", "<", 30);
Mono<List<ReactiveDatadamOperations.Row>> page =
    operations.query("telemetry:by-value", range, 0, 20);

SearchQuery filter = new SearchQuery("@value:[$min $max]",
    Map.of("min", 10, "max", 30), Sort.by("value"));
Mono<ReactiveDatadamOperations.SearchPage> search =
    operations.search("telemetry:search", filter, 0, 20, List.of("$.device", "$.value"));
```

Prepare the indexes using the existing administrative operations and separate schema credentials. The example assumes compatible indexes already exist.

## Native API contract

`jsonGet(key)` returns complete JSON text. `jsonGet(key, path)` preserves native JSONPath match arrays and legacy-path results. A missing key completes without a value. Explicit JSON null remains text. `jsonSet` accepts serialized JSON and returns true for an acknowledged write or false when the server returns nil. `jsonDelete` returns the native deletion count.

`query` and `search` require a nonnegative offset and positive limit. Each subscription requests one page. `query` returns a list of native rows and preserves the index tuple order, including `CompoundQuery.desc()`. Use `count` separately when you need an IDX total. `search` returns a total and rows from one response, with parameters and sorting from `SearchQuery`.

Rows expose an immutable field map. Full JSON content uses the key `$`. HASH content uses stored field names and strings. Supplying fields selects a native RETURNJSON projection, preserving JSON strings, null tokens, numbers, objects, and arrays as JSON text. HASH projections keep stored strings. An omitted path stays absent from the map. The decoder checks the page structure and rejects replies exceeding the requested row limit.

Native writes operate directly on keys. Applications using entity versioning, TTL policies, auditing, or callbacks must apply those policies through the corresponding entity APIs. Reactive entity lifecycle support is part of the next item.

## Validated reply formats

The integration test runs against the published Datadam 0.2.0 image pinned by digest and revision in [the test image contract](../config/test-images.properties). Index setup uses the existing imperative fixture. The commands under test use Lettuce's asynchronous connection API and reactive dispatch.

| Command | Verified behavior |
|---|---|
| `JSON.SET`, `JSON.GET` | Status and UTF-8 text arrive as strings. A missing key completes without an item. An explicit JSON null selected via JSONPath arrives as text `[null]`. |
| `IDX.COUNT` | The count arrives as a `Long`. |
| `IDX.GET`, `IDX.QUERY` | A match preserves the key and nested field/value array. A UNIQUE lookup miss completes without an item. An empty query emits one empty list. |
| `IDX.QUERY … RETURNJSON` | The nested projection retains JSON strings, the literal JSON token `null`, and array text. |
| `FT.SEARCH … PARAMS … RETURNJSON` | A single reply contains the numeric total, document key, and nested fields. The projection preserves aliases and omits missing paths. Zero matches return the total `0`. |
| Failed UNIQUE write | The native command error reaches the subscriber. The rejected document stays absent and subsequent commands on the same connection succeed. |

Use an output decoder for each expected RESP type and allocate it per subscription. For list-shaped replies, `NestedMultiOutput` keeps nested arrays and integer values. The test adds a small adapter for top-level nil on `IDX.GET`. Stock `NestedMultiOutput` would represent it as a list containing null. An empty array must still produce an actual empty list. [Lettuce 6.6 nested decoder](https://github.com/redis/lettuce/blob/6.6.0.RELEASE/src/main/java/io/lettuce/core/output/NestedMultiOutput.java).

## Demand and write outcomes

Publishers are cold. Creating or subscribing to a publisher without requesting its value sends no command. Each subscription that requests data executes a fresh command, so subscribing again to a write can apply it again. The proxy tests verify zero requests when a subscriber cancels with zero demand, for both writes and query pages.

After dispatch, cancellation stops delivery to the subscriber. The server may already have applied a mutation. Lettuce drains its response so the next command on that connection receives the correct reply. The test delays a write reply, cancels, and then reads the applied value through the same socket. After a dropped reply, the write runs once and the disconnected connection rejects later commands. Reconcile the outcome before retrying.

Demand is controlled per page. The selected page is buffered as a complete RESP response, and the page limit bounds rows rather than document bytes or the server's scanning cost. Applications fetch another page explicitly. Concurrent changes can affect offset pagination between requests.

Server UNIQUE errors map to `DuplicateKeyException`, ACL errors to `PermissionDeniedDataAccessException`, and other command refusals to `InvalidDataAccessResourceUsageException`. Lettuce resource errors map to `DataAccessResourceFailureException`. The operations add no retry operator.

## Reactive repositories and entity lifecycle

Declare a repository interface extending `ReactiveDatadamRepository<Entity, ID>`. Register it with `@EnableReactiveDatadamRepositories(basePackageClasses = YourRepository.class)` and supply a `ReactiveDatadamTemplate` bean. Its constructor takes the native operations and an ObjectMapper. Connection establishment happens asynchronously before the demo initializes Spring. A plain Spring application can supply an established connection through its own lifecycle.

The repository extends Spring Data Commons `ReactiveCrudRepository`. Its native query methods return `Mono<Page<T>>` and require a bounded Pageable. Interface default methods can compose `CompoundQuery`, `SearchQuery`, and `ProjectionSpec`. The [telemetry repository](../datadam-demo/src/main/java/com/anlohse/datadam/demo/reactive/TelemetryRepository.java) demonstrates this arrangement. Query derivation by method name and `@IdxQuery`/`@FtQuery` declarations are supported by the imperative repository factory. The reactive factory rejects additional abstract query methods at startup.

| Feature | Reactive behavior |
|---|---|
| Storage | JSON and HASH use the same entity models and serialization rules as imperative repositories. |
| Generated IDs | The default UUID generator and explicitly supplied generators are supported. Mutable IDs are populated after acknowledgment. Custom generators must execute without blocking. |
| Version | The existing Lua scripts enforce create-only writes, compare-and-set updates, and conditional deletes. A stale version emits `OptimisticLockingFailureException`. Mutable versions advance only after acknowledgment. |
| TTL | Default and per-entity TTL values share the imperative conversion and validation rules. Content, version, and TTL use the same scripts. `persist(id)` removes expiration. |
| Auditing | Supply `ReactiveAuditorAware<?>` and a Clock to enable audit dates and actors. The actor is resolved for each save in the subscriber's Reactor context. An empty actor still allows dates. |
| Projections | Records and closed interfaces share ProjectionPlan with imperative repositories. Selected fields are returned natively, including type-preserving JSON and HASH decoding. |
| Callbacks | Each template has its own lifecycle configuration. Imperative callbacks and `@EnableDatadamAuditing` are configured on the imperative template. The reactive template currently exposes auditing and ID generators as its lifecycle extension points. |
| Bulk writes | `saveAll` requests and writes entities sequentially according to downstream demand. Cancellation stops the remaining input. A dispatched write can still complete. |
| Global enumeration | `findAll`, global `count`, and global deletion enumerate the entity prefix with SCAN. SCAN uses batches of approximately 256 keys and deduplicates keys in memory. Concurrent enumeration has no global snapshot. Prefer indexed pages for large datasets. |

Save preparation and acknowledgment processing live in a transport-free helper shared by the two templates. The reactive path uses native asynchronous command dispatch throughout. A batch can partially succeed, and a lost write acknowledgment requires reconciliation through a known ID or business key.

The demo's [WebFlux application](../datadam-demo/src/main/java/com/anlohse/datadam/demo/reactive/ReactiveTelemetryApplication.java) uses Netty, a separately configured connection, and asynchronous connection shutdown. The profile `reactive-telemetry` activates its router. Follow the [telemetry walkthrough](demo-examples.md#101-reactive-telemetry).

## Runtime checks

`ReactiveRepositoriesIT` checks generated IDs, versions, concurrent updates, TTL and expiration, context auditing, JSON/HASH mapping, projections with restricted ACLs, demand-limited batches, ambiguous write results, and HTTP streaming and shutdown.

A separate JVM installs BlockHound for Reactor workers and Lettuce event-loop threads. Positive controls exercise Thread.sleep and the imperative DatadamCommands transport, and the probe then runs concurrent reactive saves, reads, and deletes. Thread.sleep is explicitly marked because the JDK 25 implementation differs from the detector's default instrumentation. This check covers the exercised operations and the supplied configuration. Applications must also keep their auditors, generators, and other extensions non-blocking. [BlockHound](https://github.com/reactor/BlockHound).

## Run the validation

With Docker running, execute from the repository root:

```powershell
mvn -B -ntp -pl datadam-demo -am '-Dit.test=ReactiveTransportIT,ReactiveOperationsIT,ReactiveRepositoriesIT' '-Dfailsafe.failIfNoSpecifiedTests=false' test failsafe:integration-test failsafe:verify
```

The suite starts and removes a disposable server and its storage. Results are in `datadam-demo/target/failsafe-reports`.
