# Spring Data Datadam

This Java library provides Spring Data repositories for JSON and HASH entities and queries Datadam’s native indexes. The project includes the `spring-data-datadam` core, `datadam-spring-boot-autoconfigure`, the `datadam-spring-boot-starter`, and the `datadam-demo` HTTP application. English is the official language of this project. Use it for documentation, Javadoc, issues, pull requests, and commit messages.

The [roadmap](ROADMAP.md) tracks completed features, upcoming work, and completion criteria. The [CI and release guide](docs/ci-and-release.md) covers automated verification, signed candidates, smoke tests, and the Maven Central publishing checklist. Preparing a candidate is a local operation.

The [runnable examples for stages 0–7](docs/demo-examples.md) cover products, reservations, support tickets, articles, inventory, preferences, shopping carts, books, devices, and imports. Start the environment and run `.\scripts\run-demo-examples.ps1`, or select a scenario with `.\scripts\run-demo-examples.ps1 -Scenario 3.2.1`. Each script checks its results and cleans up its test data. The demo exposes `/examples/products`, `/examples/stock`, and `/examples/imports`.

## Run on Windows

The project has been validated on Java 17, 21, and 25 with Spring Boot 3.5.16 and Spring Data Commons 3.5.13. The recorded build uses JDK 25.0.2 and Maven 3.8.4 with Java release 17. Datadam runs in Linux x86_64 containers. The [compatibility and performance matrix](docs/compatibility-and-performance.md) lists the pinned images, test coverage, and published measurements.

Run these commands from the repository root.

```powershell
# Downloads the pinned official image if needed.
.\scripts\run-local.ps1 -IntegrationTests -Background

# Rebuilds and replaces the instance started by the script.
.\scripts\run-local.ps1 -Restart -IntegrationTests -Background

# Builds the pinned C++ revision from committed sources.
.\scripts\run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background

# Seeds three customers and runs HTTP queries. Safe to run again.
.\scripts\sample-data.ps1

Invoke-RestMethod http://localhost:8080/
Invoke-RestMethod 'http://localhost:8080/customers?city=SP&minimum=18&maximum=40&descending=true'
Invoke-RestMethod 'http://localhost:8080/customers/search?city=sp&minimum=18&maximum=40'
Invoke-RestMethod 'http://localhost:8080/customers/derived?city=SP&minimum=18&maximum=40'
Invoke-RestMethod 'http://localhost:8080/customers/email?email=ana@example.test'
```

The API listens on `127.0.0.1:8080` and Datadam listens on `127.0.0.1:6380`. `POST /customers` saves or replaces a customer, `GET /customers/{id}` reads one, and `DELETE /customers/{id}` deletes one. Reusing an email for a different ID returns HTTP 409. The compound query includes the lower bound and excludes the upper bound. The FT query includes both bounds. HTTP pagination accepts a zero-based `page` and a `size` between 1 and 100. The page response has a stable shape with `content` and `page.totalElements`.

The background process writes its PID to `datadam-demo/target/demo.pid` and logs to `datadam-demo/target/demo.log`. Stop it with the following commands.

```powershell
Stop-Process -Id ([int](Get-Content .\datadam-demo\target\demo.pid))
docker compose down
```

The `datadam-spring-data_datadam-data` volume keeps documents and indexes after `docker compose down`. To rebuild the C++ image with `-BuildDatadam`, use a checkout containing the pinned revision. Set `DATADAM_SOURCE` to its path (default `D:/cpp/workspaces/datadam`). Configure the HTTP port with `PORT` and the Datadam port through the Compose mapping and `DATADAM_PORT`.

## Use the library in another application

`mvn install` installs the parent POM and all three artifacts in the local Maven repository. The commands below install only the library and validate a separate consumer.

```powershell
mvn -B -ntp -pl datadam-spring-boot-starter -am install
.\scripts\verify-library-artifacts.ps1
.\scripts\verify-consumer.ps1
```

Spring Boot applications should use the starter dependency shown below. It replaces the direct dependency on the core module used by earlier SNAPSHOT examples.

Since November 9, 2026, the Maven `groupId` is `com.anlohse.datadam` and the Java packages are under `com.anlohse.datadam.*`. When upgrading an earlier checkout, update `io.datadam:*` dependencies and `io.datadam.*` imports or class names. The artifact IDs are unchanged.

```xml
<dependency>
  <groupId>com.anlohse.datadam</groupId>
  <artifactId>datadam-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The consuming application manages its dependencies through Spring Boot 3.5.x. This integration was validated with 3.5.16. Auto-configuration registers the connection pool, `DatadamCommands`, and `DatadamTemplate`. Enable repository discovery as shown below.

```java
@SpringBootApplication
@EnableDatadamRepositories(basePackageClasses = CustomerRepository.class)
public class Application { /* Standard SpringApplication.run entry point */ }
```

The [standalone consumer](examples/consumer-boot/README.md) has its own POM outside the reactor. It runs against Datadam using a separate schema and credentials. For Spring without Boot, use `spring-data-datadam` and configure the beans manually. The [public API and compatibility policy](docs/public-api-and-compatibility.md) covers the module split, migration, sources, Javadoc, and limitations. The current version is `0.1.0-SNAPSHOT`. Roadmap item 8.2 covers releases and remote publication.

```yaml
datadam:
  host: localhost
  port: 6380
  username: app
  password: ${DATADAM_PASSWORD}
  timeout: 5s
  ssl: false
```

Each command borrows one RESP2 connection from the Jedis pool. By default, `datadam.validate-connection=true` sends PING when borrowing a connection to discard stale connections after a server restart. This adds a round trip per command. `datadam.timeout` supplies the default of 5 seconds for connecting, reading, and waiting for the pool. Use `connect-timeout`, `read-timeout`, and `pool.max-wait` to override them. The library uses database 0 and sends no cluster commands, server configuration commands, or requests to enable keyspace notifications.

Roadmap item 7.1 covers pool sizing and limits, optional health checks, and optional Micrometer metrics. Telemetry omits arguments and credentials. Both features are off by default. The demo enables them at `/actuator/health` and `/actuator/metrics` and hides health details. See the [diagnostics guide](docs/library-diagnostics.md) for configuration, costs, error categories, optional dependencies, and examples.

Roadmap item 7.2 covers restarts, pool saturation, connection drops and timeouts that leave write outcomes uncertain, and a JSON/HASH ACL matrix. `datadam.ssl=true` verifies the endpoint host name or IP in addition to certificate trust and validity. Adjust the host or certificate SAN in existing deployments where needed. The tests generate short-lived certificates and run the TLS client and server in Docker. See [failures and security](docs/failures-and-security.md) for reproduction, limitations, and how to reconcile uncertain outcomes before retrying.

## Model entities and indexes

The full example is in `datadam-demo/src/main/java/com/anlohse/datadam/demo/Customer.java`. Every entity, including a record, needs `@DatadamDocument(prefix = "...")` and exactly one Spring Data `@Id` field. The application can assign the ID or generate it on save with `@DatadamGeneratedId`. Keys are `prefix + id`. Use distinct, non-overlapping prefixes for each type.

```java
@DatadamDocument(prefix = "demo:customer:")
@DatadamIndex(name = "demo:customers:city-age", fields = {
    @IndexField(path = "$.city", alias = "city"),
    @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC)})
@DatadamIndex(name = "demo:customers:email", unique = true,
    fields = @IndexField(path = "$.email", alias = "email"))
public record Customer(@Id String id, String email, String city, int age) {}
```

`@DatadamIndex` is repeatable. The standard type is `COMPOUND` (`IDX.CREATE ... ON JSON`); `kind = SEARCH` creates `FT.CREATE ... ON JSON`. Fields accept JSONPath, aliases and types TAG/NUMERIC. Only TAG of accepted FT indexes `caseSensitive = true`. TAG of IDX always difference capitals; TAG of FT ignore capitals ASCII by standard. Arrays JSON can produce several indexed values.

The order of the fields in a composite index is part of the contract: equality in the initial fields and band in the following select a contiguous slice. Invalid missing or numerical fields exclude the document of IDX. FT maintains documents that do not have all the fields. UNIQUE is imposed atomically by the server and converted into `DuplicateKeyException`.

## HASH storage

Use `@DatadamDocument(prefix = "...", storage = DatadamStorage.HASH)` to persist the entity as HASH, with the same API CRUD, TTL, generation of IDs, audit, callbacks and version. JSON continues to be the default. Null fields are omitted and nested collections/objects remain as JSON within each field HASH.

CRUD HASH requires Datadam extension HREPLACE and rollback correction included in [server patch](server-patches/README.md). The local image has been updated. Other servers need this evolution. See [entities HASH](docs/hash-entities.md) for conversions, atomity and `/hash-notes`.

Scheme ON HASH and queries IDX/FT declared, derived and programmatic are available with `/hash-customers`. `IndexField.separator` explicit the division of TAGs; IDX HASH requires aliases equal to the field and FT allows own aliases. See [indexes and queries HASH](docs/hash-indexes-and-queries.md) for differences of TAG/NUMERIC and limits of equality in derivation.

## Native projections

Annotated queries IDX/FT can return records and closed interfaces to List/Page, selecting only the requested properties. The API programmatic uses `ProjectionSpec` with `projectQuery`/`projectSearch`. `@ProjectionProperty` maps names of DTO for the entity's Java properties. There is no additional complete reading or entity callbacks on the projections.

Projections JSON require Datadam RETURNJSON, included in [server patch](server-patches/README.md), to distinguish JSON null from string `"null"`; HASH uses RETURN existing. See [projections](docs/projections.md) for supported set, conversions, aliases and endpoints `/customer-projections`.

## Optimistic locking

Add `@Version Long version` to require the current version when updating or deleting. A new entity starts with null version and is persistent with zero. Each update increases the counter. Comparison and recording happens on the server: an obsolete version generates `OptimisticLockingFailureException`, preserving document, TTL and indexes.

The example `/versioned-notes` combines version, generated ID, audit, callbacks and TTL. PUT receives the expected version in the body and DELETE in `?version=...`. Conflicts return HTTP 409. See [optimistic locking with @Version](docs/optimistic-locking.md) for types, deletions, migration of existing documents and limits of native commands.

## JSONPath operations

`template.json(Customer.class)` offers read, set and remove with `JsonPath<T>`, including TypeReference for collections. Reading distinguishes missing key, no-game path and JSON null and normalizes JSONPath/associated responses. Simple changes use native JSON commands, preserve expiration and update IDX/FT.

The API protects the ID and TTL fields and rejects partial mutations on entities that use versioning, auditing, or write callbacks. Use save for those entities. See [JSONPath operations](docs/json-path-operations.md) for examples, responses, limits, and the `/customers/{id}/json` endpoint.

The same object supports increment/multiply, arrayAppend/arrayInsert/arrayPop/arrayTrim, arrayLength/arrayIndex, and object merges. Each mutation uses a native command and preserves TTL. A sequence of calls can partially succeed. See [increments, arrays, and merges](docs/json-native-mutations.md) for return values, concurrency, UNIQUE constraints, and HTTP examples.

## IDs and new entities

Add `@DatadamGeneratedId` to the `@Id` field to generate a UUID when the ID is missing. The default generator supports String and UUID. Provide a `DatadamIdGenerator` bean for a custom strategy. Supplied IDs are preserved. Always use the value returned by `save` or `saveAll`. Records are returned as new instances with the generated ID. Mutable classes receive the ID after a successful save.

The example `/temporary-notes` accepts POST without ID. See [IDs and identification of new entities](docs/entity-identity.md) for types, status detection, `Persistable` collisions, integration with TTL and failure behavior.

## Entity TTL

Use `@DatadamDocument(timeToLive = 60, prefix = "note:")` to set a default TTL in seconds. Use `@DatadamTimeToLive(unit = TimeUnit.MILLISECONDS)` on an integral or Duration field to override the TTL for each entity. Each `save` renews a positive TTL. With no TTL policy, the existing expiration is preserved. `repository.persist(id)` explicitly removes the expiration.

The full example is in API `/temporary-notes`. See [TTL of entities](docs/entity-ttl.md) for rules, permissions, examples and limits of atomicity and recovery after server crash.

## Auditing and callbacks

Enable `@EnableDatadamAuditing` to populate Spring Data's `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, and `@LastModifiedBy`. `AuditorAware` supplies the actor. A `DateTimeProvider` or Clock supplies the time. Auditing is optional and supports mutable classes and records.

Callbacks BeforeConvert, BeforeSave, AfterSave and AfterConvert allow normalization, validation and observation. The example `/audited-notes` receives only business fields and preserves creation data in updates. See [audit and callbacks](docs/auditing-and-callbacks.md) for order, configuration and fault semantics.

## Repositories and queries

```java
public interface CustomerRepository extends DatadamRepository<Customer, String> {
    @IdxQuery(index = "demo:customers:city-age",
              value = "city = ?0 age >= ?1 age < ?2")
    Page<Customer> byCityAndAge(String city, int minimum, int maximum, Pageable page);

    @FtQuery(index = "demo:customers:search", value = "@city:{?0} @age:[?1 ?2]")
    Page<Customer> search(String city, int minimum, int maximum, Pageable page);
}
```

The module uses the Spring Data Commons infrastructure in `RepositoryFactoryBeanSupport` and `RepositoryFactorySupport`. Repositories extend `ListCrudRepository` and add `query`, `count`, `lookup`, and `search`. Annotated methods must have exactly one query annotation and return `Page<T>` or `List<T>`. For an annotated query without `Pageable`, the limit is 100. `List<T>` returns the requested page. Positions such as `?N` refer to zero-based Java argument indexes. FT binds values using `PARAMS` and `$pN`. IDX sends each value as a separate RESP argument. The library keeps values out of the query expression. Methods without query annotations are derived from their names using the rules below.

```java
var query = CompoundQuery.all()
    .where("city", "=", "SP")
    .where("age", ">=", 18)
    .where("age", "<", 40)
    .desc();
Page<Customer> page = repository.query("demo:customers:city-age", query, PageRequest.of(0, 20));
long count = repository.count("demo:customers:city-age", query);
List<Customer> byEmail = repository.lookup("demo:customers:email", "ana@example.test");

var search = new SearchQuery(
    "@city:{$city} (@age:[18 25] | @tag:{java}) -@tag:{legacy}",
    Map.of("city", "SP"), Sort.by(Sort.Direction.DESC, "age"));
Page<Customer> results = repository.search("demo:customers:search", search, PageRequest.of(0, 20));
```

`query` returns in order of index. Use `.desc()` or `@IdxQuery(descending = true)` to reverse. `Pageable.sort` in IDX is rejected in order not to promise an arbitrary sorting. FT accepts an sorting field, in `SearchQuery.sort` or in `Pageable`. Conflicting criteria are rejected. Consultations only accept declared indexes for the entity and the correct type (IDX/FT).

## Derived queries

Without `@IdxQuery`/`@FtQuery`, the library uses Spring Data parser `PartTree` to compile methods in IDX or FT. The real example is in `CustomerRepository`:

```java
Optional<Customer> findByEmail(String email);
long countByCityAndAgeLessThan(String city, int maximum);
boolean existsByEmail(String email);
Page<Customer> findByCityAndAgeBetweenOrderByAgeDesc(
    String city, int minimum, int maximum, Pageable page);
Slice<Customer> findByCityAndAgeGreaterThanEqualOrderByAgeAsc(
    String city, int minimum, Pageable page);
List<Customer> findTop2ByCityAndAgeGreaterThanOrderByAgeDesc(String city, int minimum);
List<Customer> findByCityInOrderByAgeAsc(Collection<String> cities);
Optional<Customer> findFirstByCityOrderByAgeDesc(String city);
```

The API example exposes `GET /customers/derived?city=SP&minimum=18&maximum=40`: the query is derived, `Between` includes the two limits and the order is decreasing age. The email endpoint also uses `findByEmail` without annotation.

| Appeal | Behaviour |
|---|---|
| Equality (`Is`, `Equals` or no suffix) | TAG exact or equal NUMERIC |
| `Between` | Numerical range inclusive of both boundaries |
| `GreaterThan`, `GreaterThanEqual`, `LessThan`, `LessThanEqual` | Numerical bands with corresponding limits |
| `And`, `Or` | Intersection/union; Spring parser groups are preserved with FT parentheses |
| `In` | Collection or array of scalar values. Use FT union. Empty collection is rejected |
| `StartingWith` | Literal string prefix. Band TAG in IDX or parameterized FT prefix |
| `Not`, `NotIn` | Numerical denial in FT, requiring field presence. Excludes absent/null/invalid |
| `True`, `False` | Equality in a Boolean property |
| `IgnoreCase`, `AllIgnoreCase` | Requires TAG FT without CASESENSITIVE. Accepts parameters ASCII, according to Datadam's native folding |
| `OrderBy`, `Sort`, `Pageable.sort` | Native ordering compatible with the index. Conflicting orders are rejected |
| `TopN`, `FirstN` | Native limit for List; `Top1`/`First1` also accept entity/Optional |
| `Page<T>` | Content and total; IDX makes a separate count |
| `Slice<T>` | Search page size +1; IDX does not count |
| `List<T>` | Without Pageable/Top/First, returns all results, with no implicit limit of 100 |
| Entity or `Optional<T>` | Maximum one result. Double pitch `IncorrectResultSizeDataAccessException` |
| `countBy...` / `existsBy...` | Return long/Long and boolean/Boolean; IDX uses COUNT or QUERY LIMIT 0 1 NOCONTENT |

The library prefers compatible IDX indexes, scoring equality predicates on leading fields and a range on the next field. It breaks ties by index name. Equality on every field of a UNIQUE index uses `IDX.GET`. OR/IN and other combinations that require FT compile to `FT.SEARCH` with `PARAMS`. These methods run without SCAN or Java-side filtering.

The choice of index preserves the meaning of the query:

- All fields of a composite index need to be required by the predicates. Using `(city, age)` for only `findByCity` would lose documents without an age. In this case a IDX is required only by city or a compatible FT.
- Equilibrium of String difference capitals. FT indices used for exact equality or ordination TAG must declare `caseSensitive = true`; TAG FT standard serves for `IgnoreCase`. The demonstration maintains the original FT for flexible search and adds `demo:customers:derived` with exact TAGs. The alias `home` of this index corresponds to the Java property `city`.
- Java property names are solved by Jackson's JSON names, including `@JsonProperty` and naming strategy. The derivation accepts scalar property paths with simple JSON members. Collections/maps are not derived. JSONPaths declared in the indexes must correspond to the serialized path (e.g. `$.city`).
- Sorting IDX must follow the variable fields of the index. Fields fixed by equality can be omitted from the order. FT accepts a single sorting field. The requested sorting is never applied after paginar.
- Without compatible index for the predicates and static order, the creation of the repository fails. An incompatible dynamic order fails before sending the query. There is no creation of automatic schema as the effect of derivation.

Parameters must be non-null. The library rejects empty TAG values and empty IN/NOT_IN collections. `Not` and `NotIn` support numeric fields only. Repository initialization rejects `IsNull`, `Containing`, `Like`, TAG negation, derived deletes, derived projections, Limit and scrolling parameters, and text range comparisons. `Top` and `First` cannot be combined with Pageable, count, or exists. Entity and Optional results cannot take Pageable. Annotated queries can express additional native operations.

See [operator matrix and derivation semantics](docs/derived-queries.md) for prefixes, denial, absent fields, limitations TAG/IgnoreCase and examples HTTP in `/customer-queries/json` and `/customer-queries/hash`.

The syntax follows the [Spring Data Query Names](https://docs.spring.io/spring-data/commons/reference/repositories/query-keywords-reference.html), respecting the subset supported by this module.

## Schema, backfill, and ACLs

`IndexMigration` and `DatadamIndexMigrations` prepare a versioned target, resume interrupted preparation where possible, and validate the target before deployment. Switching consumers and dropping the old index are separate steps. A UNIQUE conflict requires resolving the duplicate data and explicitly discarding the failed target. See [index migrations](docs/index-migrations.md) for the `IndexMigrationApplication` CLI and credentials for preparation and retirement.

The item 6.1 adds `DatadamIndexOperations.listIndexes/status/inspect/compare` and `DatadamQueryDiagnostics.explain` to follow backfill, compare schema with differences by field and explain the index chosen in the derivation. The inspection does not perform DDL; FT.INFO has proportional cost to the index, while `status` uses only IDX.INFO. See [inspection and diagnosis](docs/index-diagnostics.md) for API, permissions and local administrative example.

Indexing is an explicit migration step:

```java
// commandsAdmin should use a connection with the schema user.
new DatadamIndexOperations(commandsAdmin)
    .ensureIndexes(Customer.class, Duration.ofSeconds(30));
```

`ensureIndexes` creates the missing indexes, waits `IDX.INFO state=ready`, detects `failed` and validates the existing definition (prefix, type JSON, fields/order, UNIQUE and case mode of the FT TAGs). Incompatible definition fails without discarding indexes or data. A backfill UNIQUE with duplicates fails. Corrects the data and migrates explicitly. The creation is identical. The wait also applies to FT so that the initialization does not accept partial results during backfill.

The script runs `SchemaApplication` before API, with one user `schema`. The process of API uses only `app`. The development configuration is in `docker/datadam.conf`:

- `default off` requires authentication; `protected-mode yes` remains active.
- `schema` can create, inspect and remove indexes; API does not receive these commands.
- `app` can read/engrave documents only in `demo:customer:*`, `demo:temporary:*` and `demo:audited:*`, consult indexes and execute the necessary commands to TTL.
- `reader` read only keys `demo:customer:visible*`, to test filters ACL for results and totals.
- The memcache is disabled and only the host loopback receives the published door.

The passwords included are exclusively for local development. The configuration is mounted as read only and the rules ACL are in it. It is not used `ACL SAVE`, `aclfile` or `CONFIG REWRITE`. The C++ documentation has a residual mention of `protected-mode` in the list of unsupported items, but the dedicated section, configuration and implementation support it. The local execution keeps the option enabled.

## Tests and limitations

```powershell
./scripts/build-test-image.ps1
docker compose --env-file config/test-images.properties up -d --no-build
# run-local.ps1 -IntegrationTests also prepares the fixed JVMs and executes the entire matrix.
mvn verify -Pintegration
mvn install
```

`mvn test` executes contract testing and validation of the derivative without depending in Docker. `mvn verify -Pintegration` performs additionally real integration tests, which fail if Datadam is not accessible. No exchange for Common Redis or mock is made. The suite uses users of the local configuration and creates/removes only its own keys `it-*`/`visible-it-*`, in addition to a temporary test index. It does not use FLUSHALL. The tests cover CRUD JSON, tracks, order/pages, UNIQUE and competition, backfill with duplicates, incompatible scheme, automatic maintenance after JSON.NUMINCRBY, FT with AND/OR/NOT, literal parameters, TTL, ACL and HTTP. For derivation, also check used commands (lookup, count and Slice), inclusive/exclusive limits, OR/IN, case, missing fields, dynamic sorting, unique results and lists above 100 records.

If Maven fails with PKIX on this machine, the script already uses the Windows trusted certificate repository. For manual execution, configure `MAVEN_OPTS` with `-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`, maintaining validation TLS.

The optional reactive API provides native JSON operations and bounded IDX/FT queries through `ReactiveDatadamOperations`. See [reactive native operations](docs/reactive-transport.md) for dependencies, connection configuration, demand, and cancellation.

Scope of 0.1:

- Repository mapping in JSON and HASH, additional operations JSON and native projections IDX/FT, according to the specific pages of each resource.
- Annotated queries, derivation of names in the above documented and API programmatic subset, with assigned IDs or optional generation, TTL, audit/callbacks, and optimistic positioning. Step 9 recorded the protocol limits and postponed the Spring transaction manager. [Contract and transaction testing](docs/spring-transactions.md).
- `Page` of IDX makes `IDX.COUNT` and `IDX.QUERY` separate. Under competition, content and total can reflect different moments. For counting only, use `count(index, query)`; FT already returns the total in the same command.
- `findAll`, `count()` and `deleteAll()` travel the prefix using SCAN by storage type (ReJSON-RL or hash). For business queries and large collections, prefer indexes; Datadam's `DBSIZE` is a global estimate.
- `saveAll` and batch deletions are sequential and may have partial success. Without a policy of TTL, `save` does JSON.SET and maintains the existing deadline. With TTL, use an isolated script to record and expire, with the durability limits described in the TTL documentation. There is no emulation of indexes with Redis sets.
- FT offers TAG/NUMERIC. Does not offer TEXT, GEO, VECTOR, aggregations or scoring. Unsupported sorting options are rejected.
- The API example has local blind. Authentication HTTP and external deployment are outside the scope of this test application.

The protocol reference is in `D:/cpp/workspaces/datadam/docs/secondary-indexes.md`, `search.md`, `json.md`, the differences section of `commands.md`, and the ACL and protected-mode sections of `security.md`. The dedicated repository factory follows the [Spring Data repository infrastructure](https://docs.spring.io/spring-data/jpa/reference/repositories/custom-implementations.html).
