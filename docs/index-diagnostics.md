# Index inspection and diagnostics

The item 6.1 offers schema and backfill inspection and explains the choice of indexes in the derived queries. These APIs do not create, remove or migrate indexes. `ensureIndexes`, which already existed, is still the explicit creation/validation operation.

## List indexes and track backfills

```java
var indexes = new DatadamIndexOperations(commands);
List<String> names = indexes.listIndexes();
IndexStatus status = indexes.status("customers:city-age");
System.out.println(status.state());       // BUILDING, READY or FAILED
System.out.println(status.keysScanned()); // keys visited, not percentage
System.out.println(status.error());       // reason reported by the server in FAILED

indexes.awaitReady("customers:city-age", Duration.ofSeconds(30));
```

`listIndexes()` executes `IDX.LIST`, which lists both IDX and FT. `status()` does only `IDX.INFO`. `awaitReady` consults this same status at intervals of 25 ms, respects interruption and requires positive timeout. Backfill failure produces `DataIntegrityViolationException` with the cause of the server. Timeout produces `QueryTimeoutException` with the last key counter visited. A missing index is not treated as still building.

There is no real percentage or estimated completion time. The count of visited keys does not report how many are missing. A backfill interrupted by restart may restart. Do not suppose that the counter is monotonous between restarts.

During construction, IDX refuses queries; FT can respond with the already indexed subset. To demand completeness, wait READY before using the index. An index UNIQUE that failed will not be reconstructed nor will its duplicates be removed by these APIs.

## Full inspection

```java
IndexInfo info = indexes.inspect("customers:search");
info.fields().forEach(field -> System.out.println(
    field.path() + " AS " + field.alias() + ": " + field.type()
    + ", separator=" + field.separator() + ", caseSensitive=" + field.caseSensitive()));
```

`IndexInfo` contains status, format JSON/HASH, prefixes, UNIQUE and fields in order. Each field has path, alias, type TAG/NUMERIC, effective separator and box sensitivity. IDX TAG is always sensitive to the box; FT uses the attributes returned by the server, including the standard separator `,` in HASH.

For IDX, the inspection uses only `IDX.INFO`. For FT, it also uses `FT.INFO`, necessary for CASESENSITIVE and document/entry statistics. **FT.INFO scrolls through the index to count entries**: prefers `status()` to frequent polling. `searchStatistics` is null in IDX. We do not expose `percent_indexed` as real progress, because the server currently reports only 0 or 1.

The responses are observations of separate commands, without transactional snapshots. If the schema diverges between IDX.INFO and FT.INFO, the inspection refuses the response with `DataRetrievalFailureException`. Repeats after completing the administrative change. Statistics and status may reflect different moments. The returned lists are immutable.

## Compare schemas without DDL

```java
for (IndexSchemaComparison comparison : indexes.compare(Customer.class)) {
    if (!comparison.compatible()) {
        System.out.println(comparison.index() + ": " + comparison.describe());
    }
}
// Or only an index declared in the entity:
var comparison = indexes.compare(Customer.class, "customers:city-age");
```

Each difference contains `property`, `expected` and `actual`. Example:

```text
fields.size: expected=2, actual=1;
fields[0].alias: expected=city, actual=home;
fields[0].caseSensitive: expected=true, actual=false
```

They are compared family IDX/FT, format JSON/HASH, prefixes, UNIQUE and position, path, alias, type, separator and box of fields. The validation of statements uses the same rules of `ensureIndexes`. The format is normalized: a field HASH without AS has the same path as the name. Standard separators and box IDX are compared by its actual effects.

Absent index produces `exists=false`, `actual=null` and difference `exists`. Connection errors, ACL and invalid responses propagate their exceptions. They did not see an absent index. An undeclared name in the entity is refused before I/O.

`compatible()` indicates only schema correspondence. An index BUILDING or FAILED may have a compatible schema. See also `actual().status()` and wait READY when necessary. `ensureIndexes` uses the same comparator and includes the differences in the exception, while maintaining the requirement for explicit migration.

## Explain a derived query

```java
var diagnostics = new DatadamQueryDiagnostics(template);
var method = CustomerRepository.class.getMethod(
    "findByEmailStartingWithOrderByEmailAsc", String.class, Pageable.class);
DerivedQueryPlan plan = diagnostics.explain(CustomerRepository.class, method);

System.out.println(plan.index());      // name of the index declared chosen
System.out.println(plan.kind());       // COMPOUND (IDX) or SEARCH (FT)
System.out.println(plan.commands());   // for example [IDX.QUERY, IDX.COUNT]
System.out.println(plan.reason());
plan.candidates().forEach(System.out::println);
```

Spring Boot provides a replacement bean `DatadamQueryDiagnostics`. It is also possible to build the API manually. To simulate the dynamic order of a method with Sort/Pageable, use `explain(repository, method, Sort.by(...))`. The combination with OrderBy follows the same rules and conflicts of execution. Dynamically non-parameter methods do not accept a dynamic order in the diagnosis.

The report uses **the same compiler and the same selection of execution candidates**. Includes compatibility, score and reason for rejection of each index: absent/uncompatible type field, TAG divided, incompatible box, field IDX not required, OR/IN/negation that requires FT and ordination restrictions. Between compatible plans, the current score of the planner wins. Draw is solved by the index name. This score is a rule of preference, not a cost/latency estimate of the server.

The plan reports IDX.GET for a single result by UNIQUE tuple, IDX.COUNT for count/exists, QUERY+COUNT for an IDX Page, and FT.SEARCH for FT plans. `nativeSort` contains FT aliases. For IDX, the index tuple determines the order and `descending` indicates whether it is reversed. A UNIQUE schema alone does not guarantee that a list query uses IDX.GET.

The explanation does not execute commands or receive predicate values. It does not check whether the index exists, is ready or the user can consult it. It also does not validate call-dependent values, such as empty prefixes, null or characters not ASCII in IgnoreCase. It considers the Jackson mapping of the template and the repository statements. Use `compare` and `status` to validate the server. Annotated methods, CRUD, default and unsupported operators are refused. The lack of a compatible plan also informs the reasons of the candidates in the exception.

## Permissions and local example

`IDX.LIST`, `IDX.INFO` and `FT.INFO` require permissions of their own. They are global metadata, not limited by ACL document key patterns; FT statistics also do not necessarily represent only the keys readable by the user. Do not expose this information on a public route of the application.

The local Docker includes `index-observer`, authorized only the connection and these three commands. It neither reads documents nor executes DDL. The user `schema` won `IDX.LIST`. The credentials `app` remain without access to the inspection. The diagnosis of queries is local and does not require any of these privileges.

`IndexDiagnosticsApplication` uses the administrative connection configured to list, compare customer indexes JSON/HASH and explain a derived method. It does not execute DDL. After installing the application, run:

```powershell
$env:DATADAM_ADMIN_PASSWORD = 'local-schema-password'
java '-Dloader.main=com.anlohse.datadam.demo.IndexDiagnosticsApplication' -cp datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
# Add --wait to wait up to 30 seconds per existing index.
```

After changing the ACL in the Docker file, restart the service to load the configuration: `docker compose restart datadam`. Do not remove the volume. Delivery 6.1 uses existing commands and does not require new C++ patch.

`IndexDiagnosticsTest` covers responses, normalization, differences, states, timeout, interruption and propagation of faults. `QueryDiagnosticsTest` compares plans with execution commands and ordination copper, aliases, UNIQUE, refusals and disengagement. `IndexDiagnosticsIntegrationIT` validates JSON/HASH in Docker, ACL restricted, backfill UNIQUE with failure and equivalence with actual execution. The complete suite is executed by `scripts/run-local.ps1 -Restart -IntegrationTests -Background`.
