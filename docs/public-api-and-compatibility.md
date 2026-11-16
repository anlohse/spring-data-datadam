# Public API, distribution, and compatibility — 8.1

## Artifacts and dependency selection

**Coordinates and packages updated in November 9, 2026:** all Datadam artifacts use `com.anlohse.datadam`; Java packages have gone from `io.datadam.*` to `com.anlohse.datadam.*`. For the Central, the namespace to be checked is `com.anlohse`, corresponding to `anlohse.com`. Update groupIds, imports and class names in configurations, reinstall libraries and rebuild their consumers. Evidence from previous executions preserves the names used at the time. A new evidence records the migration of packages.

| Artifact `com.anlohse.datadam` | Responsibility |
|---|---|
| `spring-data-datadam` | Entities, callbacks/auditing, Spring Data Commons repositories, template, transport RESP/Jedis, operations JSON/HASH/IDX/FT, projections and explicit index administration. No Boot, Actuator or Micrometer Core on execution dependencies. |
| `datadam-spring-boot-autoconfigure` | Properties, pool/template/generators configuration and optional Boot diagnosis. Depends on the core; Actuator, Micrometer and configuration processor are optional. |
| `datadam-spring-boot-starter` | Recommended entry for Boot: adds auto-configuration and `spring-boot-starter`, which also brings the core. Without API own execution. |
| `datadam-spring-data-parent` | Metadata and build configuration. Necessary to resolve published/installed POMs. Do not need to be the parent of the consumer application. |

Applications using Spring without Boot depend on the core alone. Boot applications can use the starter to pull in the required modules. `EnableDatadamRepositories` and `EnableDatadamAuditing` stay in the core and require no Boot dependency. Classes that moved to another JAR kept their Java names. The core and auto-configuration share the `config` and `diagnostics` packages, so use the classpath for this version.

### Upgrade consumers of earlier snapshots

Before this delivery, `spring-data-datadam` included auto-configuration. To continue using `datadam.*` and the automatic discovery of beans in Boot, replaces direct dependency with the starter, with the same version:

```xml
<dependency>
  <groupId>com.anlohse.datadam</groupId>
  <artifactId>datadam-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Who already configures everything manually can maintain the core. Who mounts their own Boot Dependencies Convention can use `datadam-spring-boot-autoconfigure` directly. explicitly activate `@EnableDatadamRepositories`. The starter does not execute DDL or activate audit. Health/metrics remain disabled by default and only work with the corresponding optional classes and opt-in on properties.

## Public API surface

The consumption support focuses on the types below. The visibility `public` of an infrastructure adapter does not transform it into an extension point. Manufacturers/override of implementations used only internally by Spring are not guaranteed.

| Surface | Contract and use |
|---|---|
| `annotation.*` | Document/storage reporting metadata, index/fields, queries IDX/FT, TTL, ID and projection mapping. |
| `repository.DatadamRepository` | Interface to be extended by domain; CRUD Spring Data more native queries and projections in the documented subset. |
| `DatadamTemplate`, `EntityModel` | Programmatic APIs for entities, queries, and projections. The model associates the type with its key prefix, storage format, and declarations. |
| `CompoundQuery`, `SearchQuery`, `ProjectionSpec`, `JsonPath` | Typed input objects. Linked parameters are not user-free concatenation. |
| `DatadamJsonOperations`, `JsonReadResult`, `JsonPopResult` | Reading and partial mutation, null/absence results and arrays according to specific guides. |
| `DatadamIdGenerator`, `IdGenerationContext`, `UuidDatadamIdGenerator` | ID generation extension. The default strategy has name `datadamIdGenerator`. |
| `callback.*`, `auditing.DatadamAuditing` | Ordered callbacks and audit integration, including AfterSave failure already confirmed. Opt-in notes in `config` remain public. |
| `DatadamIndexOperations`, `DatadamIndexMigrations`, `Index*` | Explicit inspection/comparation, preparation, validation and withdrawal. Execute with separate credentials and administrative cycle. |
| `repository.DatadamQueryDiagnostics`, `DerivedQueryPlan` | Local explanation, no predicate values. Declared schema compatibility plan, no estimate of server cost. |
| `DatadamCommands`, `diagnostics.DatadamCommandObserver`, `CommandObservation` | Synchronous transport and observation. Direct controls are a low-level output and can ignore the cycle of entities. |
| `config.DatadamProperties` and Boot auto-configuration | Properties `datadam.*`, documented beans names and retreat when the consumer provides compatible beans. The implementation of the pool factory is internal. |

`SimpleDatadamRepository`, `DatadamRepositoryFactoryBean`, `DatadamRepositoriesRegistrar`, `DatadamAuditingConfiguration` and the audit mapping context are infrastructure adapters. There is no SPI promised for subclasses, reflection on internal fields or replacement of compilers/decoders. Use repository interfaces, callbacks, generators, template and documented beans.

When configuring Spring manually without Boot, the basic `DatadamTemplate` constructors do not discover callbacks or auditing in the Spring context. Supply `EntityCallbacks` and `DatadamAuditing` through the corresponding constructor to enable that lifecycle. The constructor that accepts both objects without a callback predicate conservatively rejects partial mutations. Boot auto-configuration wires these components and identifies the types with write callbacks.

Exceptions Spring DAO distinguish duplicate (`DuplicateKeyException`), obsolete version (`OptimisticLockingFailureException`), ACL, invalid use and resource failure. `AfterSaveCallbackException` confirm written information and its key. `JsonMutationDecodingException` distinguish decoding failure after shipment: it is not a sign that the mutation has been undone. Native messages are not a stable protocol for application logic and can contain details. Telemetry avoids recording them.

## Guarantees and limitations

- APIs are blocking, with a pool connection per command. There is no reactive support, cluster, database other than 0 or Spring transaction manager in this version. Dependency `spring-tx` offers the hierarchy of exceptions, without providing a Datadam transaction manager. [Step 9 Decision and native guarantees](spring-transactions.md).
- JSON is standard; HASH requires HREPLACE. Projections JSON require RETURNJSON. The validated server is the revision fixed in `config/test-images.properties`, not common Redis or any Datadam revision.
- UNIQUE, filters, order and pagination use native resources. There is no promise of equivalence with all Spring Data operators or full text search of RediSearch. TAG/NUMERIC and documented refusals define the scope.
- `@Version` does CAS per entity. Updates/deletions refused per version do not replace the document. Direct commands and changes outside the library may circumvent this contract.
- The library does not repeat transport writes automatically. After losing an answer, read/reconcile by ID or business key before deciding on a new operation.
- `saveAll`, imports and sequences of commands JSON are separate operations, with possibility of partial success and without general rollback. There is no promise of durable joint document recording/TTL after crash beyond the already documented warranties.
- Partial writing JSON cannot change ID/TTL or ignore version, audit or callbacks of recording. Projections return DTOs/interfaces, without loading complete entities or running your callback cycle.
- `findAll`/`count()` global do SCAN of the prefix. Indexed operations may have COUNT additional. Unlimited list can materialize all results. Prefer Page/Slice/limited list according to the desired cost.
- Runtime credentials and DDL are separated. There is no creation of indices implied by the starter, migration aliases, automatic removal of origin or global data cleaning.

The details by resource continue in the guides of [TTL](entity-ttl.md), [IDs](entity-identity.md), [Audit](auditing-and-callbacks.md), [version](optimistic-locking.md), [JSON](json-native-mutations.md), [HASH](hash-indexes-and-queries.md), [projections](projections.md), [derivation](derived-queries.md), [migration](index-migrations.md) and [faults/security](failures-and-security.md).

## Versioning policy

The current version is **0.1.0-SNAPSHOT**, still prior to the first stable version. SNAPSHOT is mutable and can receive incompatible changes, such as the division of artifacts in this delivery. Updates require reading the notes/guides and repeating the consumer's tests. A SNAPSHOT should not be treated as an immutable release.

For future stable versions, the proposed policy is SemVer: patch preserves the documented consumption surface. Minor adds compatible features and can deprecate APIs. Incompatible removals/changes of this surface require major and migration orientation. Corrections that alter incorrect behavior or strengthen security should explain the impact. This policy does not grant stability to internal adapters nor promises support to all external versions.

The validated matrix covers Java 17/21/25, Boot 3.5.16, and Data Commons 3.5.13. The core uses the parent POM's dependency management at build time and runs independently of Boot. The isolated core tests use Spring 6.2 and the same Data Commons version. Boot 4, Spring 7, and versions outside the matrix are unvalidated. Boot applications manage dependencies through a compatible parent POM or BOM. The project does not yet ship its own BOM. [Compatibility matrix](compatibility-and-performance.md).

## Local artifacts and metadata

```powershell
mvn -B -ntp -pl datadam-spring-boot-starter -am install
.\scripts\verify-library-artifacts.ps1
.\scripts\verify-consumer.ps1
```

The first command installs the parent and the three artifacts with POMs. Core and auto-configuration generate JAR normal, `-sources.jar` and `-javadoc.jar`. The starter generates the same sorters, with package documentation and a HTML explaining that it does not offer classes of API own. This JAR information was added in item 8.2 to the format required by the Central. The current verification confers nine JARs, project license MIT, core Lua scripts, Javadoc index and Boot metadata. Javadoc validates format/reference errors. Missing documentation notices do not prevent build. It is not an exhaustive documentation statement of each internal method.

License, author, URL, SCM and issue tracker are inherited from the parent. The JARs of code/sources and of Javadoc load `META-INF/LICENSE`. The core does not contain AutoConfiguration.imports and the auto-configuration includes this record and `spring-configuration-metadata.json`. Hashes of the JARs are in `target/library-artifacts.json`.

The actual consumption validation is [examples/consumer-boot](../examples/consumer-boot/README.md), outside the reactor. It packs and executes only JARs installed in another build Maven, with separate schema and disposable Datadam. There is no `systemPath`, import of the demo or failback to `target/classes` from the main project.

The item 8.2 adds CI, changelog, signature and bundle preparation to the Central Portal. The [release guide](ci-and-release.md) describes these steps. There are no `distributionManagement`, real credentials or automatic remote deployment. The publication continues to depend on real namespace/key and explicit authorization.

Validation of November 8, 2026: **395 approved tests**, with 140 in the core, 48 in the auto-configuration and 207 integration. Plus 564 unit re-executions in Java 17/21/25. The independent consumer executed JAR with CRUD/IDX/FT/UNIQUE/version, without demo dependencies, Actuator or Micrometer Core. Result, dependency tree, server review and hashes of the eight JARs are in [evidence of delivery](validation/2026-11-08-consumption.json). Hashes identify this local build of SNAPSHOT. They are not promise of bit-to-bit reproduction.
