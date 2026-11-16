# Compatibility and performance — 7.3

This delivery defines the effectively validated set, fixes the image inputs of the tests and publishes a reproducible measurement. It does not alter the semantics of queries or promises constant performance.

## Supported matrix

The current contract is Spring Boot **3.5.16**, Spring Data Commons **3.5.13**, Jedis **6.0.0**, Jackson Databind **2.21.4** and Micrometer **1.15.12**, with the versions managed by POM of the project. We do not replace Spring Data Redis: the repositories use Commons and the Datadam protocol directly.

| Validated runtime | Platform | Unit | Consumer Spring Boot + Datadam |
|---|---|---|---|
| Temurin 17.0.21 | Linux x86_64 in Docker | 188 approved | Approved |
| Temurin 21.0.13 | Linux x86_64 in Docker | 188 approved | Approved |
| Temurin 25.0.5 | Linux x86_64 in Docker | 188 approved | Approved |
| OpenJDK 25.0.2 | Windows 11 x64 | 188 approved | Approved full integration suite |

The recorded build uses Maven 3.8.4, JDK 25.0.2, and `--release 17`. Each JVM runs the same compiled artifacts, with one compilation for the entire matrix. The validated set excludes Boot 4, other dependency versions, intermediate Java releases, Java 26+, ARM, and a native Windows server. Boot itself supports a wider range than this library has tested.

`CompatibilityMatrixIT` copy only compiled classpaths/dependencies for isolated clients. In each image JDK executes the entire library suite by JUnit Console and then starts a Boot consumer. The consumer validates IDs, version, CRUD JSON/HASH, UNIQUE, IDX derived with Page, FT projections, versioned update/exclusion, obsolete write refusal, TTL, health and metrics. The server is new in each line of the matrix.

The three lines are three cases of the Maven suite, but they perform additionally **564 unit tests inside containers**. Since 8.1, the classpath gathers core and auto-configuration tests. They are 140 + 48 cases. The verification of Boot/telemetry absence is done in the Maven classpath isolated from the core, and the same manual configuration is re-executed in the matrix's combined classpath. These repeated executions are not added to the number of unique tests of the project. The TLS tests of delivery 7.2 continue to be a separate set and use the JVM JRE fixed in the image contract.

Historical evidence of 7.3: [compatibility in JSON](validation/2026-11-07-compatibility.json). Validation of 8.1 is in [artifacts and independent consumption](validation/2026-11-08-consumption.json). The complete logs and reports JUnit are in `datadam-demo/target/compatibility/java17`, `java21` and `java25`.

## Datadam revision and images

Current tests use the official image **Datadam 0.2.0**, which contains HREPLACE and RETURNJSON. The performance measurements below are historical and used the development commit `e2e2c0ae4e9282dd4b1d815e9d893931bd41bd3e`. [Release Datadam 0.2.0](https://github.com/anlohse/datadam/releases/tag/v0.2.0).

[config/test-images.properties](../config/test-images.properties) is the contract shared between scripts and tests. It fixes:

- Digest of the image published in GHCR and complete C++ revision.
- Digests of images JDK 17/21/25 and JRE used in TLS.

The runner re-uses the local image when available and makes `docker pull` per digest on a new machine. Before starting disposable servers, the scripts and `TestImages` verify the OCI revision label and resolve the reference to the local ID. The `DATADAM_IMAGE` option allows you to select another image from the same revision. The Compose uses the digest from the same release.

```powershell
./scripts/run-local.ps1 -Restart -IntegrationTests -Background
```

To rebuild the test version from C++, use `-BuildDatadam` and point `DATADAM_SOURCE` to a clone with the revision fixed. The script only files this commit and saves a local tag. Run the matrix again when updating the image contract. To run only the server, use `docker compose up -d --no-build`.

## Recorded measurements

Artifacts of November 7, 2026, time America/Sao_Paulo:

- [CSV complete: 104 combinations](performance/2026-11-07-results.csv).
- [Environment, parameters, revision and image ID](performance/2026-11-07-environment.json). The timestamp in JSON is in UTC, already in 08/11.

The client ran on Windows 11 with OpenJDK 25.0.2 and an Intel Core i7-14700HX with 28 logical processors available. Docker Engine 29.9.2 ran Linux x86_64 in a VM with about 7.6 GiB of memory. Each benchmark server was limited to **2 CPUs and 1 GiB**, with two read threads. Client concurrency was **1**. The host was shared, and the measurements include OS, virtualization, and loopback transport overhead.

They were loaded **1,000 and 10,000 documents by format** (JSON and HASH), ten cities/groups and payload String with 256 characters. Each format has IDX compound `(city, age)`, IDX UNIQUE by email and FT TAG/NUMERIC. Updates maintain these three indexes. There is no recording scenario without indexes to attribute the cost of UNIQUE in isolation.

The filter selects 10% of the documents: 100 or 1,000 results. The page has 20 records. The deep offset is 80 or 980. There are ten warm-ups per scenario and fifty samples, except limitless list (ten) and global count (five). Each scenario rotates with false and true validation-connection. Schematic creation and initial load are out of the samples. The loading time is recorded in JSON.

Selected results for **10,000 documents by format**, with connection validation **activated**, in milliseconds:

| Operation | JSON median / p95 | HASH median / p95 | Commands by call |
|---|---|---|---|
| IDX content only, first page | 0.849 / 0.911 | 1.079 / 1.199 | 1 |
| IDX Page, first page | 1.756 / 2.175 | 1.856 / 2.062 | 2 |
| IDX Page, offset 980 | 6.063 / 6.704 | 4.286 / 4.654 | 2 |
| IDX COUNT filter, 1,000 results | 0.854 / 0.950 | 0.717 / 0.823 | 1 |
| FT Page, first page | 2.497 / 2.825 | 2.847 / 3.032 | 1 |
| FT Page, offset 980 | 4.012 / 4.330 | 4.381 / 4.755 | 1 |
| Complete replacement with indexes | 0.674 / 0.830 | 0.770 / 0.889 | 1 |
| global count(s), 10,000 keys | 30.523 / 31.227 | 28.978 / 29.416 | 40 |

The CSV also contains lookup UNIQUE, projections IDX/FT, limitless lists and native increments JSON.NUMINCRBY/HINCRBY. The increase HASH is a low-level command via DatadamCommands, not a new API entity HASH. `commands_per_operation` counts library transport calls and **no** internal pool validation PINGs.

These numbers are a local reference, not SLO, concurrent capacity, load test or comparison with Redis/another bank. `serial_operations_per_second` is the inverse of the observed mean, not throughput maximum. There is no threshold of latency approval. Fifty samples and a fixed sequence of scenarios do not isolate effects of JIT/cache nor replace a statistical benchmark in the production environment.

In this benchmark, an IDX Page cost more than fetching the same content without COUNT, and deep pages cost more than first pages. Connection validation adds a PING each time the client borrows a connection. Projections return fewer fields. For these small payloads, the median JSON IDX projection without connection validation took 1.226 ms, compared with 1.101 ms for a full Page. The measurements do not establish a general latency advantage for projections or partial updates.

## API costs and limits

| Appeal | Work involved |
|---|---|
| Page IDX | QUERY and COUNT separately. The programmatic API counts before consulting, the derivation consults before counting. Both can observe different moments under competition. |
| IDX.COUNT | It runs through the range/candidates of the index that satisfy the filters and ACL. It is not global counter O(1). Reading documents depends on the necessary filters/validation. |
| IDX with OFFSET | You need to rule out previous offset matches. The cost is not constant for deep pages. |
| FT Page | A command returns total and content. The server evaluates candidates and applies ACL before the total. With sorting, it runs ordered entries to get offset+limit. A call does not mean O(1) cost. |
| List IDX / limitless derivation | You can materialize all results on the server/client. Less commands do not limit bytes, heap or response time. |
| Projections | They select native fields and avoid additional reading per document. There is still filter, total when requested, serialization and materialization of DTO. |
| Validate-connection | One PING per loan: two Page commands IDX usually involve two additional PINGs. It is not a repetition of writing. |
| count() without arguments | SCAN by prefix and type, COUNT 256 as an indication, up to zero cursor. Accumulate keys to count. Do not use IDX.COUNT or DBSIZE. |
| findAll() | The same SCAN, followed by individual reading JSON.GET/HGETALL. Cost and memory grow with the returned keys. |
| deleteAll() | Enumeration followed by sequential deletions, with additional readings/scripts in versioned entities or with callbacks. There may be partial success. |

Datadam SCAN uses the literal prefix to delimit the metadata cursor. We do not claim that all SCAN runs the entire bank. It still depends on the keys visited in the prefix, type filters, expiration and ACL, without global snapshot between calls. In the measured set, global count() visited 10,000 format keys in forty calls; IDX.COUNT of the example selects only 1,000 matches, so the two counts in the table do not have the same selectivity.

## Reproduce the workload

First install/validate the reactor with the local script. Then:

```powershell
./scripts/run-performance.ps1 -Documents '1000,10000' -Samples 50 -Warmups 10
```

The workload creates its own servers/volumes and does not use the application data. The class ends in Benchmark and stays out of the normal suite `*IT`. The performance profile separates its reports. The script calls test-compile and Failsafe directly, avoiding repackaging a JAR that is in use on Windows.

Outputs: `datadam-demo/target/performance/results.csv`, `environment.json` and `failsafe-reports`. To record another baseline, preserve parameters/images/environment and publish the raw files next to the interpretation. The measurement published above is a snapshot, is not automatically overwritten by subsequent executions.

References: [Spring Boot requirements 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [official images Temurin](https://github.com/docker-library/docs/tree/master/eclipse-temurin), code `DatadamTemplate`/`SimpleDatadamRepository` and implementation `src/db/index_ops.cpp`, `search_ops.cpp` and `database.cpp` of the fixed Datadam revision.
