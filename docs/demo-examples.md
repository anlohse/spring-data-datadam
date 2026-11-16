# Runnable demo examples — stages 0 to 7

The 16 subtopics of roadmap have scripts that create data, perform real operations and verify results. `ExampleScenarios` contains business steps. Entities, repositories, callbacks and services are in `datadam-demo/src/main/java/com/anlohse/datadam/demo/examples`. The failure scenario uses existing security tests and the new `StockItem`.

## Setup and execution

Requirements: Java/Maven and Docker Desktop, according to README main. At the root of the project:

```powershell
# If the server is already running from an earlier version, reload the new ACL:
docker compose restart datadam
.\scripts\run-local.ps1 -Restart -IntegrationTests -Background

# All examples up to 7.3.1, including the fault-insulated runner:
.\scripts\run-demo-examples.ps1

# It is also possible to choose a subtopic:
.\scripts\run-demo-examples.ps1 -Scenario 2.1.1
.\scripts\run-demo-examples.ps1 -Scenario 6.2.1
.\scripts\run-demo-examples.ps1 -Scenario 7.2.1
.\scripts\run-demo-workload.ps1 -Count 100
```

`-Restart` replaces only the Java process initiated by the script. The `docker compose restart datadam` above is required once to reload the ACL modified. The volume remains. The build checks the Datadam image fixed in `config/test-images.properties`. Tests do not use Redis nor a simulated database.

The runner CLI starts a doorless Spring context HTTP and prints a line `EXAMPLE_RESULT` per script. A normal scenario returns `{"scenario":"2.1.1","status":"PASSED","checks":[...]}`. Any unexpected result closes the process with error. The runner 7.2.1 shows the JUnit results and a confirmation at the end. It is not necessary to stop the application HTTP to run the examples.

The scripts explicitly use the local Docker development credentials. For other credentials, configure `DATADAM_USERNAME`, `DATADAM_PASSWORD`, `DATADAM_ADMIN_PASSWORD` (bootstrap) and, when necessary, `DATADAM_SCHEMA_PASSWORD`, `DATADAM_INDEX_OBSERVER_PASSWORD`, `DATADAM_INDEX_MIGRATOR_PASSWORD` and `DATADAM_INDEX_RETIRER_PASSWORD`. The failure of the administrative functions is only enabled by the local runner and only for `localhost`/`127.0.0.1`.

## Example catalog

Each command below uses `run-demo-examples.ps1 -Scenario <number>`. The checks described appear on the output and are also executed in `DemoExamplesIntegrationIT`.

| Item | Main classes | Inputs and expected result |
|---|---|---|
| 0.1 | `Product`, `ProductRepository`, `ProductController` | Two products of own category, prices 10/20. Search for SKU, range 0–30, Page size 1 ordered by price and FT TAG/NUMERIC return the expected records. Another record with the same SKU is refused. |
| 1.1.1 | `TemporaryReservation`, `ReservationRepository` | TTL 60-second standard, 1-second override and `persist`. Query before expiration finds the reservation. Then entity, IDX and FT do not return it. `persist` leaves PTTL = -1. |
| 1.2.1 | `SupportTicket`, `TicketConfiguration`, `ExternalTicket` | The named generator returns `TKT-<UUID>`. The returned ID is used to read/update without generating another. An import with external ID uses `Persistable.isNew()` and refuses the second creation with the same ID. |
| 1.3.1 | `Article`, `ArticleCallbacks` | The title `  Spring Data  ` is normalized before validation. Audit receives dates/authors. Edit preserves creation. Empty title is refused without replacing the record. Body `[fail-after-save]` triggers failure after confirmation: reading by ID proves writing. |
| 2.1.1 | `StockItem`, `StockService`, `StockController` | Quantity 10 goes to 9 with the original version. Another edition and an deletion with old version fail. The rereading shows 9. Deletion with the current version works. |
| 3.1.1 | `UserPreferences`, `PreferencesRepository` | Typed readings of `$.settings.theme`, nested object and array. JSON null, missing path and missing document have different results. SET/DEL preserve the absolute expiration date. Partial writes in entities with version, audit or callbacks are refused. |
| 3.2.1 | `ShoppingCart`, `CartRepository` | Increment/multiplication, append/insert/pop/trim/length and merge delivery. Pop of JSON null informs `popped=true`. Empty array informs `false`. An outside insert fails after an append already applied, which remains. |
| 4.1.1 | `DeviceState`, `DeviceRepository` | HASH with booleans, numbers, list and map. Save with null field removes the physical field; TTL absolute is preserved when the update does not report another TTL. Old version is refused. JSON and HASH coexist with the same ID in different prefixes. |
| 4.2.1 | `DeviceState`, `UnsupportedDeviceQueries` | IDX compares the entire region, including comma. FT uses aliases `zone`, `seen`, `tag`, TAG per token and separator `;` for labels. `Java` finds `Java;Spring`, `ava` no. Deriving field equality divided into tokens is denied by the diagnosis. |
| 5.1.1 | `ProductSummary`, `DeviceSummary` | Record projection in JSON map category for `department`. Interface closed in HASH map lastSeen and preserves Boolean absent as null. Does not require to materialize the entire entity. |
| 5.2.1 | `Book`, `BookRepository`, `UnsupportedBookQueries` | Prefixes with `*}|` are literal. There is IgnoreCase ASCII, Between, In, Not/NotIn and AND/OR. Numerical denial requires presence and excludes null pages. Sorting by number alone is not a presence filter. Empty prefix, Contains and String Not are refused. |
| 6.1.1 | `ExampleAdministration.inspect`, `ProductSchemaMismatch` | Observer lists indexes and compares schema/status of products, books and devices. Displays intentional difference of fields and index/motive chosen by local planes. An uncovered ordination is refused. It does not read documents or execute DDL. |
| 6.2.1 | `MigrationProduct`, `ProductSchemaV2`, `CategoryUniqueSchema` | Prepare v2, wait READY and consult your data without changing v1. UNIQUE with duplicates failure. Fix data, explicitly discard target FAILED and prepare again works. An explicit consumer goes to v2 before removing v1. |
| 7.1.1 | `ImportJob`, `ImportService`, `ImportController` | Job known before execution, batch of three lines with a SKU duplicate: state PARTIAL, 2 processed and 1 rejected. Consultable status, rejected re-execution, health UP and present error/pool metrics. |
| 7.2.1 | `TransportFailuresIT`, `StockItem`, `AclSecurityIT`, `TlsSecurityIT` | Loss before/after shipment, timeout, restart, saturated pool, loss of versioned save confirmation, ACL and TLS. Isolated containers: rejected cases are expected results, not script failures. |
| 7.3.1 | `Product`, `DeviceState`, `ExampleScenarios.workload` | configurable volume of 1 to 1.000 documents per format. Page, Slice, limited list, count and projection agree on applicable content/totals. The same volume HASH is conferred by Page and projection. |

## Explore the HTTP API

The catalogue has API own. Existing customer routes, notes and HASH remain available.

```powershell
$base = 'http://localhost:8080'
$sku = 'DEMO-' + [guid]::NewGuid().ToString('N')
$inputProduct = @{ sku=$sku; name='Teclado'; category='peripherals'; price=150; tags=@('Java','Spring') }
$product = Invoke-RestMethod "$base/examples/products" -Method Post -ContentType application/json -Body ($inputProduct | ConvertTo-Json)
try {
    Invoke-RestMethod "$base/examples/products/sku/$sku"
    Invoke-RestMethod "$base/examples/products?category=peripherals&minimum=100&maximum=200&page=0&size=10"
    Invoke-RestMethod "$base/examples/products/search?category=peripherals&minimum=100&maximum=200"
    Invoke-RestMethod "$base/examples/products/summaries?category=peripherals&minimum=100&maximum=200"
    # Repeat POST with the same SKU results in HTTP 409.
} finally { Invoke-RestMethod "$base/examples/products/$($product.id)" -Method Delete }
```

The tracks of this catalog are inclusive on both ends. Page uses the structure `content` and `page.totalElements` of Spring Data. `ProductSummary` returns only name, price and department. The server represents NUMERIC as a floating point number: the BigDecimal scale is not preserved (for example, 10 can return as 10.0). This example does not promise financial decimal accuracy.

```powershell
$item = Invoke-RestMethod "$base/examples/stock" -Method Post -ContentType application/json -Body (@{sku=$sku; quantity=10} | ConvertTo-Json)
$change = @{quantity=9; expectedVersion=$item.version} | ConvertTo-Json
$updated = Invoke-RestMethod "$base/examples/stock/$($item.id)" -Method Put -ContentType application/json -Body $change
# Repeat PUT with $change or delete using $item.version returns 409.
Invoke-RestMethod "$base/examples/stock/$($item.id)"
Invoke-RestMethod "$base/examples/stock/$($item.id)?expectedVersion=$($updated.version)" -Method Delete
```

The client sends the expected version. The service passes this version to CAS on the server, even if you have just read a newer version. It does not repeat or resolve conflicts automatically.

```powershell
$job = Invoke-RestMethod "$base/examples/imports" -Method Post
Invoke-RestMethod "$base/examples/imports/$($job.id)" # PENDING
$rows = @($inputProduct, $inputProduct) | ConvertTo-Json -Depth 5
$finished = Invoke-RestMethod "$base/examples/imports/$($job.id)/run" -Method Post -ContentType application/json -Body $rows
Invoke-RestMethod "$base/examples/imports/$($job.id)" # PARTIAL: one accepted line and another duplicate
Invoke-RestMethod "$base/actuator/health"
Invoke-RestMethod "$base/actuator/metrics/datadam.command.errors"
Invoke-RestMethod "$base/actuator/metrics/datadam.pool.max"
# After exploring, remove only the products from this job:
$finished.productIds | ForEach-Object { Invoke-RestMethod "$base/examples/products/$_" -Method Delete }
Invoke-RestMethod "$base/examples/imports/$($job.id)" -Method Delete
```

The import accepts 1 to 100 new products. Uses PENDING → RUNNING → COMPLETED/PARTIAL, with progress update per line. The execution HTTP is synchronous. Another connection can consult the known ID while the POST is running. A transport failure attempts to record UNCERTAIN and spreads the error. This status update can also fail, leaving RUNNING. It is necessary to reconcile by the entry SKUs. Neither of the two writes receives automatic repetition or joint transaction. Products and job status are persisted separately.

To try another pool limit only in this process CLI, preserving the previous configuration:

```powershell
$previousConfig = $env:SPRING_APPLICATION_JSON
try {
    $env:SPRING_APPLICATION_JSON = '{"datadam":{"pool":{"max-total":4,"max-wait":"1s"},"read-timeout":"2s"}}'
    .\scripts\run-demo-examples.ps1 -Scenario 7.1.1
} finally { $env:SPRING_APPLICATION_JSON = $previousConfig }
```

The value of `datadam.pool.max` in the process will be 4. Actuator HTTP belongs to the process initiated by `run-local.ps1` and maintains the configuration with which it was initiated. Metrics use operation names and result categories. Do not include SKU, job ID, arguments or passwords.

## Administration, isolation, and cleanup

The user `app` received the additional prefix `~demo:examples:*` without DDL. `SchemaApplication` prepares the indexes with `schema`. The observer uses only IDX.LIST/IDX.INFO/FT.INFO, the preparer uses CREATE/INFO and the remover uses DROP/INFO. Its pools are opened only in CLI administratively and closed afterwards. No route HTTP creates/removes indexes or injects flaws.

Each normal execution creates its own IDs/categories/SKUs and removes only its keys by ID, even in known faults. Direct cleaning with DEL is fixture infrastructure. Updates/stock deletions use version control in the service. Common indices remain for the application. Runner does not use FLUSHDB or prefix exclusion.

The script 6.2.1 requires the empty prefix `demo:examples:migration:product:` and absence of its v2 targets. Run one instance at a time, as exclusive maintenance of this space. It never migrates the normal catalog. Upon completion, it removes its documents/targets and restores the empty v1 scheme. The consumer exchange and withdrawal are called separate in the code: there are no aliases, atomic exchange or automatic deployment. If the process is killed outside the finally, review the data/indexes of this space before another execution. Do not discard targets automatically.

Expiration of the reservation does not implement stock transaction. The partial operations of the cart are separate commands and do not make transactional checkout. AfterSave fail informs confirmation already received. Failure of transport may leave result uncertain. The decisions of steps 9 and 10 remain pending.

## Failures and TLS — 7.2.1

```powershell
.\scripts\run-example-failures.ps1
```

The script performs 15 checks with the native image identified by revision/ID and the JVM TLS fixed by digest. `StockItem` starts with quantity 10 and version 0. The proxy lets the server apply quantity 9/version 1, but loses the response. The local copy continues in version 0. A direct reading reconciles the status. Repeats the obsolete copy causes conflict. The library does not repeat the transport writing automatically.

Other cases check reader/writer/scheme, refusal of commands/DDL, reliable certificates, unknown CA, incorrect hostname and expired certificate. Client and server TLS stay in Docker, preserving the chain even with inspection HTTPS on Windows. Reports are in `datadam-demo/target/example-failures/failsafe-reports`, separated from the complete suite. See also [faults and safety](failures-and-security.md).

## Query work and performance measurements — 7.3.1

```powershell
.\scripts\run-demo-workload.ps1 -Count 100
# Isolated measurement with warmup/samples, fixed images and CSV/environment:
.\scripts\run-demo-workload.ps1 -Count 100 -Benchmark
# Adjusting volumes and sampling of the benchmark directly:
.\scripts\run-performance.ps1 -Documents '1000,2000' -Samples 10 -Warmups 3
```

The first script compares without making a promise of latency. It sows the same number of products JSON and devices HASH, with Page of up to 10 records. The optional benchmark measures isolated native operations, with comparable formats/operations. Its default documents are 1.000/10.000, independent of `Count` didactic. Do not confuse an application query with a server capacity test.

| Educational alternative | Logical work on the server/application |
|---|---|
| Product/Device Full page | IDX.QUERY with limit + IDX.COUNT, reading/materialization of returned documents |
| Product Slice | IDX.QUERY with size + 1 to detect next page. Without COUNT |
| Product list with Pageable | IDX.QUERY with limit. Without COUNT or total |
| Product count | IDX.COUNT. No documents returned |
| Product/Device projection Page | IDX.QUERY with property selection + IDX.COUNT. Without complete entity |
| Catalogue FT | FT.SEARCH TAG/NUMERIC. Total provided by the server, with content limit |

Each command can add validation PING to the acquisition of the connection, according to pool configuration. The benchmark includes acquisition and validation in measured latency and publishes environment/volume in `datadam-demo/target/performance/environment.json` and samples in `results.csv`. Compares only equivalent scenarios. Warmup, JIT, host, Docker and connection inspection affect numbers. The API global `findAll`/`count()` repository makes SCAN prefix. The script uses indexed queries and limited volume. See [compatibility and performance](compatibility-and-performance.md) for native revision, digests, costs and previous measurements.

## Automated validation

`DemoExamplesIntegrationIT` executes 15 business/administration scripts and tests the three new APIs HTTP. The scenario 7.2.1 corresponds to the 15 cases of faults/ACL/TLS of the existing suite, now using `StockItem` in the lost response save. The entire suite continues to be executed by `run-local.ps1 -IntegrationTests`. It also covers the Java matrix 17/21/25 and recovery of the backfill after restarting.

Execution of **November 8, 2026**: 394 approved tests (187 units + 207 integration), including the 18 new demo tests, and plus 561 unit reruns in the matrix. The 16 examples passed through the runners. The additional workload used 100 documents per format. The API local answered UP, the registration/reading HTTP was conferred and the cleaning ended with zero keys in `demo:examples:*`. Evidence: [results of the examples](validation/2026-11-08-demo-examples.json).

The short reproduction of the benchmark used 1.000 documents by format, 52 combinations, 3 warmups and 10 samples. The [results CSV](performance/2026-11-08-examples-results.csv) and [environment JSON](performance/2026-11-08-examples-environment.json) record this execution. They are used to check the reproduction and the executed commands, without supporting a statistical conclusion on latency or capacity.
