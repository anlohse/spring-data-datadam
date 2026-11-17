# Roadmap — Spring Data Datadam

This roadmap tracks the library's development from its current feature set to a release ready for adoption in other projects. Each milestone delivers usable functionality that later milestones can build on.

**Current status:** CRUD, native IDX/FT queries and projections for JSON and HASH, TTL, IDs, auditing, callbacks, optimistic locking, and JSON operations for increments, arrays, and merges. The library provides query and connection-pool diagnostics and explicit index migration. The compatibility matrix covers Java 17/21/25 with Boot 3.5.16 and Data Commons 3.5.13. Images are identified by revision and digest, and benchmark results are published. Milestone 8.1 split the core, auto-configuration, and starter modules, added sources, Javadoc, and metadata, and validated a standalone consumer. All 403 Java tests passed: 188 unit tests (140 in core and 48 in auto-configuration) and 215 integration tests against Datadam in Docker. The matrix also reruns all 188 unit tests on each isolated JVM, for 564 additional runs. In milestone 5.1, the server with HREPLACE/RETURNJSON passed 186 native tests as well as the C++ integration and persistence suites. Milestones after 5.1 through 7.3 left the C++ code unchanged.

**Next action:** finish **Maven Central publication for 8.2** after verifying the com.anlohse namespace and production signing key, reviewing a release candidate from a clean checkout, and obtaining explicit authorization. CI, candidate preparation, and example 8.2.1 are implemented and have been validated locally. The Central upload is still pending.

**Next development milestone:** **10 — Reactive repositories**. Milestone 9 concluded the transaction investigation and deferred the Spring transaction manager and the transaction example in the demo.

**Demo examples:** all 16 examples from **0.1 through 7.3.1** are complete, with dedicated models, CLI and HTTP walkthroughs, and Docker validation. See the [runnable examples](docs/demo-examples.md) and [recorded run results](docs/validation/2026-11-08-demo-examples.json). **8.1.1 — Standalone consumer** and **8.2.1 — Release candidate smoke test** are also complete. The Spring example for milestone 9 was deferred, and the example for milestone 10 is pending.

## How we work

- Each numbered item represents a small delivery that we can implement and review separately.
- We will follow the order below. Upon completion of an item, we will update your checks and the field next delivery.
- An item receives `[x]` only after implementation, appropriate tests, documentation and example of use, when applicable.
- Existing resources do not need to be redone. They will be expanded by maintaining current contracts.
- Incompatible changes need a migration orientation. No step allows you to delete documents, remove indexes in use or publish artifacts remotely automatically.
- Investigations must result in a documented decision: implement, reduce the scope or postpone for a specific reason. We will not mark a functionality as delivered only because its viability has been investigated.

## Expanding the demo examples

- Each delivery receives its own subtopic of example, with a domain and a script of use. We can work on it by number, like **1.1.1** or **5.2.1**.
- Existing examples are the basis of each subtopic. Their files and routes remain available. New checks indicate additional still pending scenarios.
- New domains will be gradually organized into sample packages such as catalog, reservations, stock, preferences, cart, books and devices. Entities, repositories, DTOs, callbacks and services will be separated according to the need of the scenario.
- To complete an example, deliver execution instructions, expected inputs and outputs, a successful path, a relevant limit or failure, and main script integration validation. HTTP, CLI or script will be chosen according to the resource.
- Include the preparation of the diagram, the necessary prefixes and local permissions. The application continues without DDL. The scripts create and clean only their own data.
- Reuse domains when this shows a useful evolution: `Product` in the catalog, projections and migrations; `DeviceState` in HASH and in the queries. New domains do not implicitly receive a joint transaction.
- Examples of transactions and reactivity depend on the decisions and implementations of steps 9 and 10. Your checks remain pending until the corresponding support exists.

## Roadmap overview

| Step | Objective | Expected result |
|---|---|---|
| 0 | Consolidate existing base | CRUD, native indexes and searches working |
| 1 | Complete the entity cycle | Expiration, IDs and audit |
| 2 | Protect competing updates | Version control with `@Version` |
| 3 | Working with parts of documents | Operations JSON without replacing the document with the application |
| 4 | Offer another persistence format | Repositories for HASH and JSON |
| 5 | Expanding queries | Server-compatible projections and operators |
| 6 | Evolve indexes safely | Explicit schema inspection and migrations |
| 7 | Prepare the continuous operation | Diagnostics, safety and fault tests |
| 8 | Distribute a stable version | Reproducible build, CI, and artifacts ready to use |
| 9 | Investigate Spring transactions | Explicit guarantees for atomicity and failure handling |
| 10 | Provide reactive access | Non-blocking transport and reactive repositories |

Steps 9 and 10 are further evolutions. The first stable version doesn't have to wait for them.

## 0. Existing foundation — complete

- [x] Maven project with separate Spring Boot library and application.
- [x] Automatic configuration of connection, pool, commands and template.
- [x] Entities JSON with their own identification and prefix.
- [x] Spring Data Repositories with CRUD.
- [x] Composite indexes IDX, native bands, order and UNIQUE.
- [x] FT searches on TAG/NUMERIC with linked parameters.
- [x] Annotated queries and name derivation `findBy`, `countBy` and `existsBy` in the documented subset.
- [x] Optional Returns, List, Page and Slice, with native limits and ordination.
- [x] Idepotent index creation, schema validation and backfill monitoring.
- [x] Separation between application credentials and index administration.
- [x] Local data in Docker, with ACL, persistent volume and gate restricted to the local host.
- [x] Example application HTTP and run scripts.
- [x] Integration tests with UNIQUE competitor, ACL, TTL via command, queries and HTTP.
- [x] Manual verification of persistence and connection recovery after restarting the container.
- [x] Installation of the library in the local Maven repository.

**Original limit of this base:** TTL was only available by command. Declarative integration was delivered in item 1.1 below. Remote Maven publication has not yet been made.

### 0.1. Demo example — product catalog

**Existing base:** `Customer`, `CustomerRepository`, `/customers` and sample data scripts.

- [x] Create `Product` and its repository/controller, with SKU UNIQUE, index composed by category/price and search FT by TAG/NUMERIC.
- [x] Add registration script, query by SKU, price range, ordination/page and refusal of SKU duplicate, with documentation and validation of integration.

## 1. Entity lifecycle

### 1.1. Entity TTL — complete

**Benefit:** allow an entity to expire without the application having to issue additional commands manually.

- [x] Define API of TTL: standard value by type and, if reported, value by entity with explicit unit.
- [x] Define the behaviors for creation, update, absence of TTL, removal of expiration and invalid values.
- [x] Implement the write with expiration in isolated script, validate before recording and verify/document the atomic limits of Datadam.
- [x] Test creation, update, preservation/alteration of expiration and failure by UNIQUE. A refused recording cannot modify the existing TTL.
- [x] Confirm that expired documents cease to appear in both IDX and FT.
- [x] Add example of temporary entity and documentation.

**Done when:** the expiry policy is explicit and the tests prove the behaviour of the document and its indexes.

**Delivery:** `@DatadamDocument(timeToLive = ...)`, `@DatadamTimeToLive`, `persist(id)` and example HTTP `/temporary-notes`. There are 8 new unit tests and 11 new integration tests, in addition to the previous suite. See [TTL of entities](docs/entity-ttl.md).

**Atomicity Decision:** the tests prove isolation of the recording/TTL and preservation of the document and of the absolute expiration date in refusals by UNIQUE, ACL and invalid values. The server does not offer general rollback scripts, and a writing UNIQUE may force early commit. The document guarantee + TTL in a single durable recording after crash is not included in this delivery. It would require an explicit evolution of the C++ server. This limit is documented and is not considered to be resolved by the library.

#### 1.1.1. Demo example — temporary reservations

**Existing base:** `TemporaryNote` e `/temporary-notes`.

- [x] Create `TemporaryReservation` to demonstrate TTL pattern, override per entity and explicit removal of expiration with `persist`.
- [x] Add script that observes TTL, consult the reservation before/after expiration and check its exit from IDX/FT. Explain that the expiration does not implement a stock reserve transaction.

### 1.2. ID generation and new entity detection — complete

- [x] Define when an entity is considered new, preserving support for IDs assigned by the application.
- [x] Offer optional ID generation and an extension for your own strategies.
- [x] Support changeable classes and records, returning the entity with the effectively persistent ID.
- [x] Test generation, preservation of existing IDs and behavior in recording failures.
- [x] Document supported ID types and update the example.

**Done when:** the user can choose between informing the ID or receiving it on the return of `save`, with predictable behavior in new writes and updates.

**Delivery in November 5, 2026:** `@DatadamGeneratedId`, UUID standard for String/UUID, named strategies `DatadamIdGenerator` and local state detection with `Persistable` support. Generated IDs use NX creation, including TTL, to refuse collisions without overwriting documents. Records return a copy. Mutable classes receive ID only after success. Example `/temporary-notes` accepts POST without ID. See [IDs and identification of new entities](docs/entity-identity.md).

**Validation:** 17 new unit tests and 12 new integration tests; 81 tests passed in total. Includes UNIQUE, ACL, simulated transport failure, collision without change of TTL, simultaneous dispute over the same ID, custom generators, saveAll and HTTP. The API site was also conferred by POST without ID, read by the returned ID, and query of TTL.

**Contract decisions:** IDs assigned in common entities keep save as creation/substitution. `Persistable.isNew() == true` request exclusive creation. The detection does not consult the bank, and Persistable flags are not changed automatically. Transport failure may leave result uncertain. There is no automatic repetition. `saveAll` remains sequential with possibility of partial success, as documented.

#### 1.2.1. Demo example — opening support tickets

**Existing base:** generation of IDs in the registry of `TemporaryNote` and support of own strategies in the library.

- [x] Create `SupportTicket` with ID generation and a separate externally assigned ID scenario. Demonstrate a named generation strategy without replacing the pattern of the other examples.
- [x] Add script that uses the returned ID by `save` to view/update, preserve existing IDs and demonstrate exclusive crash refusal.

### 1.3. Auditing and callbacks — complete

- [x] Enter persistent and read callbacks with documented execution order.
- [x] Integrate creation/alteration dates and author creation/alteration with the abstractions of Spring Data.
- [x] Use the new stage entity detection 1.2 to preserve the creation data.
- [x] Test mutable classes, records, controllable clock and callbacks that refuse an operation.
- [x] Add audit example without requiring the application to use this resource.

**Done when:** save an entity can complete an audit and execute callbacks without unduly altering its identity or creation data.

**Delivery in November 5, 2026:** `@EnableDatadamAuditing` with notes Spring Data, AuditorAware and DateTimeProvider/Clock. Callbacks BeforeConvert, BeforeSave, AfterSave and AfterConvert, ordered and filtered by type by Spring. The new status is captured before ID generation. The audit is optional, supports changeable classes and records and preserves creation data in updates. See [audit and callbacks](docs/auditing-and-callbacks.md).

**Validation:** 18 new unit tests and 9 new integration tests; 108 tests passed in total. Watch coverage/controlled authors, creation/alteration, order, standardization, identity protection and audit, refusals, UNIQUE, TTL, materialization by CRUD/IDX/FT and API HTTP. Example `/audited-notes` was also given by POST/PUT in the local application.

**Contract decisions:** audit by annotations at the root of the entity. Embedded metadata and an auditable interface stay out of this scope. The library preserves the creation data received and does not look for the previous state to authenticate these values. The example HTTP uses business DTOs and loads the entity before updating. AfterSave Failure is distinguished by AfterSaveCallbackException because the writing has already been confirmed. Effects executed by callbacks and direct changes in memory do not receive automatic rollback. The status of the previous items and their transport/sustainability limits are maintained.

#### 1.3.1. Demo example — publishing articles

**Existing base:** `AuditedNote`, `DemoAuditingConfiguration`, callbacks and `/audited-notes`.

- [x] Create `Article` with creation and modification dates/authors, normalization callback and content validation that may refuse recording.
- [x] Add creation/edition script that preserves the creation audit and shows the order of callbacks, refusal before writing and failure treatment after confirmation.

## 2. Concurrency and version control

### 2.1. Optimistic locking with `@Version` — complete

**Benefit:** prevent an update from silently overwriting an alteration made by another connection.

- [x] Set the initial version and increment rules for creation, update and deletion.
- [x] Validate and implement in Datadam a comparison of atomic version and recording. Compare in the client before saving is not enough.
- [x] Translate version conflict to `OptimisticLockingFailureException`.
- [x] Integrate version with TTL, ID generation, audit and callbacks.
- [x] Test two concurrent connections, entity removed/expired and failure by UNIQUE without undue version increase.
- [x] Document the difference between saving a versioned entity and running native commands directly.

**Done when:** two updates of the same version could not succeed simultaneously, and the operation refused to preserve document, TTL and indexes.

**Delivery in November 5, 2026:** support for `@Version` Spring Data in Long/Integer and primitives, with comparison and recording in EVAL isolated on the server. Wrappers start in null and are recorded as zero. Primitives start in zero and are recorded as one. Each update increases the counter. Exclusions check the version without increasing it. Content and new version belong to the same JSON.SET, including under UNIQUE. See [optimistic locking with @Version](docs/optimistic-locking.md).

**Validation:** 14 new unit tests and 15 new integration tests; 137 tests passed in total. Includes dispute over two independent pool connections, duplicate creation refusal, entity removed/expired, accuracy above 2^53 and Long.MAX_VALUE, TTL absolute, IDX/FT, UNIQUE, ACL, mutable classes, records, batches, callbacks and HTTP. Example `/versioned-notes` was also given in the local application: zero creation, upgrade to one, PUT obsolete with HTTP 409 and exclusion from the current version.

**Contract decisions:** the version determines the new state even with assigned ID; Persistable must agree. deleteById reads and deletes with the current version. To require the version seen by the client, use delete(entity). Direct commands do not receive automatic increase/check, documents without version require explicit migration and reuse the same ID after exclusion/exhalation can produce ABA. The previous limits of uncertain transport, sequential batches and document/TTL durability remain documented.

#### 2.1.1. Demo example — concurrent inventory updates

**Existing base:** `VersionedNote`, `/versioned-notes` and conflict responses HTTP.

- [x] Create `StockItem` with `@Version` and a quantity change stream that receives the version observed by the consumer.
- [x] Add script with two consumers reading the same version, one accepted update and another refused, followed by new explicit reading and reconciliation. Include deletion with obsolete version.

## 3. Partial JSON updates

### 3.1. Path API and basic mutations — complete

- [x] Create a API typed to read, define and remove values by JSONPath.
- [x] Treat the different responses of JSONPath and the legacy paths correctly.
- [x] Define the interaction of these changes with `@Version` and audit. Do not allow a API library to silently circumvent these policies.
- [x] Test missing paths, null values, incompatible types, TTL and index update.
- [x] Add examples in the test application.

**Delivery in November 5, 2026:** `template.json(Entity.class)` with `JsonPath<T>`, TypeReference, read/set/remove, and JsonReadResult. Readings normalize JSONPath and legacy paths without scratching arrays from the document and distinguish missing key, no match and JSON null. Unique non-root writes only send the path/value in a native command and protect ID/TTL, including Jackson names and aliases. See [operations by JSONPath](docs/json-path-operations.md).

**Validation:** 12 new unit tests and 12 new integration tests; 161 approved tests in total. Coverage of paths and envelopes, collections, null, incompatible types, absence, TTL absolute, IDX/FT, UNIQUE, ACL, expiration, entity policies and HTTP. Example `/customers/{id}/json` was also given in the local application with array reading, city modification and name removal, preserving the other fields.

**Contract decisions:** partial changes are available for simple documents. Entities with @Version, applicable audit or recording callbacks are refused prior to transport, with guidance to use save. Partial support for these policies requires an additional protocol and has not been delivered in this item. Readings may use multiple selectors, but written only accept unique members/indices, preserve expiration without restarting TTL and do not create missing keys. The declared type validates the values of the operation, without full validation of the entity's scheme. Each change is a command. Sequences are not transactions and the server still reprocesses the entire document internally.

#### 3.1.1. Demo example — user preferences

**Existing base:** `CustomerJsonController` and operations in `/customers/{id}/json`.

- [x] Create `UserPreferences` with nested objects and demonstrate reading, definition and removal by `JsonPath<T>`, including missing paths, JSON null and arrays.
- [x] Add script that preserves unmodified fields and TTL and shows the refusal of partial changes when the entity requires version, audit or recording callbacks.

### 3.2. Increments, arrays, and merge — complete

- [x] Exhibit numerical increments, array operations and merge objects with clear return contracts.
- [x] Define atomic limits: a sequence of commands should not be presented as a single atomic operation.
- [x] Test competition, failure by UNIQUE and index maintenance after each type of change.
- [x] Documenting the cost of the server: a partial change JSON may continue to require reprocessing of the entire document in Datadam.

**Done when:** the application can change parts of a document without setting up its complete replacement and without losing the entity's documented warranties.

**Delivered on November 5, 2026:** increment/multiply, arrayAppend, arrayInsert, arrayPop, arrayTrim, arrayLength, arrayIndex, and RFC 7386 object merges. Results are normalized across JSONPath and legacy paths, and numeric promotion is preserved. JsonPopResult distinguishes removing JSON null from removing no element. JsonMutationDecodingException signals a failure to interpret the result of a mutation that was already sent. See [increments, arrays, and merges](docs/json-native-mutations.md).

**Validation:** 10 new unit tests and 15 new integration tests; 186 tests passed in total. Includes increments/appends/competitive dives by independent pools, UNIQUE in number, array, array position and merge member, preservation of TTL absolute and IDX/FT, types, null, index limits, overflow, numerical errors, expiration, ACL and HTTP. The local example was given with age increase, append/pop tags and it goes that removes a null member by preserving the others.

**Contract decisions:** maintained the 3.1 protections: unique non-root writes, without changing ID/TTL and without bypassing version, audit or callbacks. Increments return Number because overflow int64 can promote float. Pop distinguishes nil RESP from JSON null. Merge accepts object patch, combines nested maps and preserves removal nulls even with mapper NON_NULL; OK does not prove that there were matches. Each change is an atomic command on the server, but a sequence is not transaction and previous successes remain after failure. The server continues to reprocess the entire document, and transport/conversion failures after shipment do not authorize automatic repetition.

#### 3.2.1. Demo example — shopping cart

**Existing base:** numerical operations, arrays and merges already demonstrated over `Customer`.

- [x] Create `ShoppingCart`, without policies incompatible with the partial API, with array items, numerical quantities and object delivery options. Expose increments, append/insert/pop/trim and merge.
- [x] Add script that differentiates null removed from absence, use null to remove a limb via merge and show that a failed sequence can maintain previous changes.

## 4. HASH persistence

### 4.1. Mapping and CRUD — complete

- [x] Allow to choose explicitly HASH or JSON in the entity mapping.
- [x] Define conversion of numbers, booleans, enums, absent fields and null. Let explicit how to treat nested structures and collections.
- [x] Implement CRUD HASH with correct replacement of the removed fields, preserving TTL and respecting UNIQUE.
- [x] Integrate IDs, audit and `@Version`, verifying the atomic guarantees of the format HASH.
- [x] Test prefix isolation and co-existence of entities HASH and JSON.

**Delivery in November 5, 2026:** `@DatadamDocument(storage = DatadamStorage.HASH)`, with JSON as standard; CRUD/listing/count with its own format and the same cycle of ID, audit, callbacks, TTL and version. Scales saw text fields, null omit/remove fields, objects/collections saw JSON within each field HASH. Includes example HTTP `/hash-notes`. See [entities HASH](docs/hash-entities.md).

**Validation:** 9 new unit tests and 14 new integration tests; 209 Java tests passed. Coverage of types, aliases, decimal/temporal accuracy, bytes/arrays, Duration TTL, integral replacement, removed fields, final state UNIQUE compound, integral preservation in refusal, competition for independent pools, int64 version, mutable/records, expiration, ACL, coexistence/WRONGTYPE and HTTP. The server passed 184 native tests and the integration/persistence suite, including refusal UNIQUE inside the Moon and preservation after reopening the bank.

**Server Dependency:** delivery adds HREPLACE to Datadam C++, to replace fields and version in the same native transaction, preserving TTL and validating the final state UNIQUE. The investigation also found and corrected the undo representation that confuses the absence of entry into the batch with tombstone and could delete fields after a refusal. The local Docker image was reconstructed. The patch and instructions are in [Patches server](server-patches/README.md). HASH does not have failure back HSET/HDEL on old servers. JSON maintains the previous commands.

**Contract decisions:** exclusive prefixes, no automatic conversion JSON→HASH, Object properties at root rejected by ambiguity and non-flattened nesting structures. Maintaining transport limits, sequential batches, ABA and joint content durability/TTL after crash with UNIQUE. Schematics and queries HASH in the library are explicitly refused until 4.2. Indices HASH in the tests are administrative fixtures, without anticipating this step.

#### 4.1.1. Demo example — device state

**Existing base:** `HashNote`, callbacks and `/hash-notes`.

- [x] Create `DeviceState` in HASH, with scalars, list of capabilities and configuration object stored in own fields. Demonstrate complete substitution and removal of null fields.
- [x] Add creation/reading/update/exclusion script, TTL preservation and version control, with an object JSON from another domain coexisting in separate prefix.

### 4.2. HASH indexes and queries — complete

- [x] Generate and validate definitions `ON HASH` and their field names/aliases.
- [x] Adapt annotated, derived and programmatic queries to the format HASH.
- [x] Test differences of TAG, separators, absent values and NUMERIC between HASH and JSON.
- [x] Add to the application an example HASH equivalent to the example JSON.

**Done when:** the same set of public guarantees of CRUD and queries operate in both formats, with their documented differences.

**Delivery in November 5, 2026:** ON schema HASH with identical creation, backfill and format validation, fields, aliases, box and separators. IDX/FT materialize entities from the answer itself, without HGETALL additional. Declared, programmatic and derived queries use the paths HASH and native aliases. IndexField.separator displays separators TAG for both formats. Includes HashCustomer/HashCustomerRepository and HTTP `/hash-customers`, with tracks, order, page, searches and labels. See [indexes and queries HASH](docs/hash-indexes-and-queries.md).

**Validation:** 9 new unit tests and 16 new integration tests, with 234 Java tests passing. Coverage includes DDL and drift detection without dropping indexes, aliases and Jackson names, ranges, ordering and totals, Page/Slice/Top, count/exists, IDX/FT and derived queries, separators and case, JSON lists in HASH, missing fields and invalid NUMERIC values, field updates and removals, UNIQUE, TTL and expiration, callbacks, and ACL filtering of results and totals. The test reader receives complete entities directly from queries even without HGETALL permission. The IDX, derived query, FT, and labels endpoints were verified in the local application.

**Contract decisions:** IDX HASH does not accept AS, so alias should be equal to the stored field; FT accepts aliases. FT HASH divides TAG by comma by default; IDX without separator maintains full field equality. Derivation refusal TAGs divided into tokens for equality/scalar sorting, instead of changing semantics or post-filter pages. Can select a IDX without separator or a compatible numerical FT plane. Objects/collections HASH are not found or indexed as arrays JSON automatically. The C++ patch 4.1 remains necessary for writing, without new server changes at this stage. Partial projections remain for 5.1.

#### 4.2.1. Demo example — finding devices by region and activity

**Existing base:** `HashCustomer`, `/hash-customers` and queries IDX/FT on HASH.

- [x] Expand `DeviceState` with region, instant of activity in explicit numerical field and labels. Demonstrate IDX compound, FT with alias and separator TAG configured.
- [x] Add tracks, pages and totals script and compare search for token with whole field equality. Show the refusal of scalar derivation over TAG divided.

## 5. Expanded query support

### 5.1. Projections — complete

- [x] Implement field selection using the native returns of IDX/FT.
- [x] Map the result for DTOs and/or projection interfaces, explicitly defining the first supported set.
- [x] Preserving aliases, conversions, missing fields, order and pagination without extra queries per document.
- [x] Test HASH and JSON without presenting an incomplete entity as if it were fully loaded.

**Delivery in November 6, 2026:** ProjectionSpec, ProjectionProperty and native selection in projectQuery/projectSearch. Queries @IdxQuery/@FtQuery also infer records and interfaces closed by List/Page returns. Projected types are distinct from entities, with nullifiable root properties, Jackson/aliases names and typed conversions. Includes `/customer-projections` to JSON/HASH by IDX/FT. See [projections](docs/projections.md).

**Validation:** 12 new unit tests and 16 new integration tests, with 262 Java tests passing. Coverage includes returning only the requested fields, records and interfaces, bindings, null versus the literal string null, missing fields, conversions and containers, BigDecimal/int64, ordering, List/Page and totals, declared and programmatic queries, ACLs without JSON.GET or HGETALL, expiration, skipping callbacks and preserving content and TTL, and HTTP. The server passed 186 native tests and the integration and persistence suites, including compatibility with the existing RETURN option and type and alias handling in RETURNJSON.

**Server Dependency:** projections JSON use RETURNJSON, additive option implemented in Datadam C++ to preserve string, null and structure quotes without an extra reading per document. RETURN above is unchanged; HASH uses RETURN existing. The reproducible patch is in server-patches/typed-return.patch and the local image is updated. There is no failure to load the entire entity on old servers.

**Contract decisions:** initial support for records and closed interfaces with getters, root properties with nullifiable types and returns List/Page in annotated or programmatic queries. Entity targets, primitive/Object/Optional on root, SpEL/default methods/setters, nested sources, incompatible types and dynamic/derivative projections are rejected. Projections do not execute entity callbacks nor complete audit/version. Absence and null null null on DTO/getter, without defaults or discard of the line. Complex types fields are selected whole. The native pagination/counting limits and separation between projection and complete entity are maintained.

#### 5.1.1. Demo example — product and device summaries

**Existing base:** `CustomerSummary`, `CustomerView` e `/customer-projections`.

- [x] Create `ProductSummary` on record and a closed summary interface of `DeviceState`, with name mapping by `@ProjectionProperty` and nullifiable properties.
- [x] Add projection script IDX/FT in JSON/HASH that confers returned fields, aliases, absence/null, pagination and totals, keeping the return distinct from the complete entity.

### 5.2. Expanded query derivation

- [x] Develop a Spring Data operator matrix and its viability in IDX/FT.
- [x] Implement viable additional operators in separate deliveries, starting with prefixes TAG (`StartingWith`).
- [x] Evaluate `Not`/`NotIn` with explicit rules for documents without the field, before implementing them.
- [x] Maintain clear errors for operators without correct equivalent. Do not simulate support by filtering pages in memory.
- [x] Expand tests of combinations, aliases, absent fields and sorting.

**Delivered on 2026-11-06:** literal string prefixes via an IDX TAG range or an FT parameter, with ASCII IgnoreCase on compatible schemas. Numeric `Not` and `NotIn` use FT with a field-presence check. Missing, null, and invalid values fail the negation predicate. TAG negation and operators without an equivalent implementation remain unsupported. See [docs/derived-queries.md](docs/derived-queries.md) for the support matrix, decisions, limits, and examples. The 12 new unit tests and 9 new integration tests cover pagination and totals, Unicode, literal parameters, combinations, aliases, JSON/HASH, TTL, and HTTP. All 283 tests in the full suite passed.

**Done when:** new resources have documented semantics, native execution and tests that prove filters, content and totals.

**Known limit:** There is no commitment to support all Spring Data operators. Null, missing fields and text searches depend on the actual server capabilities.

#### 5.2.1. Demo example — book catalog

**Existing base:** `DerivedOperatorsController`, numerical prefixes and negation in `CustomerRepository`/`HashCustomerRepository`.

- [x] Create `Book` and derived methods for prefixing the title, page bands, `In`, `Not`/`NotIn` numerical and combinations `And`/`Or`, with compatible indices.
- [x] Add script with literal characters in the prefix, IgnoreCase ASCII, missing fields and paged results. Present the refused operators and their reasons.

## 6. Index evolution

### 6.1. Inspection and diagnostics

- [x] Exhibit public APIs to list, inspect, and track indexes and backfills.
- [x] Display differences between expected and existing schema in a legible way.
- [x] Provide the chosen plan for a derived query: index, family IDX/FT and reason for choice.

**Delivered in 2026-11-07:** list, light status by IDX.INFO, typed inspection JSON/HASH/FT and comparison without DDL differences by property. `ensureIndexes` reuse the comparator. Backfill timeout includes observed progress. `DatadamQueryDiagnostics` explains index, family, commands, sorting and candidates with the same execution planner, no commands or predicate values. Includes CLI administrative and observer user without DDL/reading documents. There are 20 new unit tests and 6 integration tests. Complete suite approved with 309 tests (152 unit tests and 157 integration into the Docker). Details and costs of FT.INFO in [docs/index-diagnostics.md](docs/index-diagnostics.md) No C++ change required.

#### 6.1.1. Demo example — troubleshooting catalog queries

**Existing base:** `IndexDiagnosticsApplication` e `DatadamQueryDiagnostics`.

- [x] Add diagnostic commands for `Product`, `Book` and `DeviceState`, with listing/status, schema comparison and explanation of candidate indexes of derived methods.
- [x] Create script with matching and divergent schema, incompatible sorting and observer user. Display differences and reasons without sending query values or running DDL.

### 6.2. Explicit migrations

- [x] Set a migration flow with versioned names, create the new index and validate the backfill before the change of use.
- [x] Treat duplicates in UNIQUE as data error that requires explicit resolution.
- [x] Separate the change of use and eventual removal of the previous index. Do not promise aliases or atomic exchange without Datadam support.
- [x] Test backfill interruption/restart and recovery from incomplete migration.
- [x] Document specific migration credentials and permissions.

**Delivered in 2026-11-07:** `IndexMigration` and `DatadamIndexMigrations` prepare a target `:vN`, validate scope/scheme/readiness and resume compatible targets after failure or timeout. UNIQUE with failure requires explicit resolution of data and separate disposal of the target FAILED. Origin is maintained. Consumer exchange is an explicit deployment, with separate preparation models. Withdrawal uses a proper operation/credential and never removes documents. It includes CLI and 15 new unit tests plus 8 integration, including actual interruption of the backfill in disposable container with 15,000 documents, resumed by another client and validation of totals. Full suite approved: 332 tests. Limitations of administrative competition, origin of the backfill and immediate effect of UNIQUE on writes are in [docs/index-migrations.md](docs/index-migrations.md).

**Done when:** it is possible to prepare and validate a new scheme without automatically removing the indexes that the application still uses.

#### 6.2.1. Demo example — evolving catalog indexes

**Existing base:** `CustomerSearchV2` and shares of `IndexMigrationApplication`.

- [x] Create a preparation model `ProductSchemaV2`, separated from active repositories, and a preparation/validation script with versioned names and business validation queries.
- [x] Demonstrate deployment/consumer change and withdrawal as separate steps. Include failure UNIQUE, explicit correction of data and resuming, using only indices/data specific to the example.

## 7. Operations, security, and compatibility

### 7.1. Library diagnostics

- [x] Exposing useful pool configuration and waiting limits, with documented patterns.
- [x] Add health check and optional connection metrics, latency and errors.
- [x] Ensure that logs and diagnoses do not include credentials or sensitive values by default.

**Delivered in 2026-11-07:** separate connection/reading/waiting limits with failure compatible with `datadam.timeout`, pool sizes and maintenance with advance validation and Spring metadata. Health PING and Micrometer metrics are optional, deactivated by default in the library and enabled in the demo. Timers/gauges/counters use listed operations and results, without arguments, credentials, keys or exception messages. Health and connection validation do not record detailed faults. Includes tests without optional dependencies, sanitization, authentication permissions, pool controlled exhaustion and HTTP Actuator. There are 20 new unit and 6 integration tests. Complete suite approved with 358 tests (187 unit and 171 integration). Configuration, costs and limits in [docs/library-diagnostics.md](docs/library-diagnostics.md).

#### 7.1.1. Demo example — tracking an import

**Existing base:** Actuator health/metrics enabled in the demo and `LibraryDiagnosticsIntegrationIT`.

- [x] Create `ImportJob` and a limited quantity import service with consultable states and progress.
- [x] Add script that follows health and metrics during readings/writes and a controlled failure. Demonstrate pool configuration and telemetry without payloads or credentials.

### 7.2. Failure and security tests

- [x] Automate reboot, timeout, loss of connection and pool saturation scenarios.
- [x] Test TLS and certificate validation with reproducible environment.
- [x] Expand tests ACL for reading, writing, query and index administration.
- [x] Document uncertain results after transport failure. Do not automatically repeat writes when it is not possible to know if they have been applied.

**Delivered in 2026-11-07:** 15 additional integration tests with isolated containers: six transport tests (proxy RESP with loss before/after writing, timeout, confirmation lost with @Version, restart and row/exhaustion), four of TLS (reliable chain, unknown CA, incorrect identity and expiration) and five of ACL to JSON/HASH, queries and metadata, administration and EVAL. The automatic pool enables endpoint identification TLS. Client and test server TLS stay at the Docker to preserve the original chain even in hosts with inspection TLS. Recordings are not repeated automatically. The tests demonstrate the ambiguity between unsent and applied writing without confirmation. Complete suite approved: 373 tests (187 unit and 186 integration). Reproduction, change TLS and reconciliation in [docs/failures-and-security.md](docs/failures-and-security.md).

#### 7.2.1. Demo example — lost acknowledgments and restricted access

**Existing base:** `TransportFailuresIT`, `TlsSecurityIT`, `AclSecurityIT` and the disposable test containers.

- [x] Create a runner/opt-in router that uses an update of `StockItem` to show lost response, persistent state, outdated local version and explicit reconciliation.
- [x] Add executable scenarios of reader/writer/schematic and connection TLS accepted/refused in isolated environment, with instructions and expected outputs. Keep the failure injection out of the normal application routes.

### 7.3. Compatibility matrix and performance

- [x] Define and test supported versions of Java, Spring Boot/Spring Data and Datadam.
- [x] Fix an identifiable version of Datadam's image in automated tests.
- [x] Measure representative cases of IDX, FT, pagination and updates, recording environment and volume of data.
- [x] Document costs of COUNT, connection validation, limitless lists and SCAN in the repository's global operations.

**Delivered in 2026-11-07:** Java matrix 17.0.21/21.0.13/25.0.5 in Docker, each with 187 unit and consumer tests Spring Boot 3.5.16/Data Commons 3.5.13 against Datadam fixed in the commit e2e2c0a. The build uses git review archive and label OCI. Tests check the revision and use the solved ID, with JVMs by digest. Complete suite: 376 tests (187 unit, 189 integration), in addition to the unit reruns in the matrix. Optional workload averages 104 format/operation/validation/volume combinations with 1,000 and 10,000 documents by format, server limited to 2 CPUs/1 GiB and published results CSV/environment. Costs, interpretation and reproduction limits in [docs/compatibility-and-performance.md](docs/compatibility-and-performance.md).

**Done when:** the connection and security guarantees are automatically tested and there is a verifiable compatibility matrix.

#### 7.3.1. Demo example — comparing query forms

**Existing base:** `NativePerformanceBenchmark`, `run-performance.ps1` and published CSVs.

- [x] Add a catalog/device script that compares full content, projection, Page, Slice/limited list and count, with configurable data volume.
- [x] Document commands and work performed in each alternative and allow to reproduce the measurement with the images fixed. Present environment, volume and limits of interpretation of the numbers.

## 8. Distribution and first stable release

### 8.1. Consumer readiness — complete

- [x] Review the division between Spring Data integration and Spring Boot specific configuration. Separate a starter if there is concrete benefit.
- [x] Review API public and document guarantees, limitations and compatibility policy.
- [x] Prepare source artifacts and javadoc, license and distribution metadata.
- [x] Validate dependence on a consumer application independent of the Maven reactor of this project.

#### 8.1.1. Consumer example — standalone application

**Dependence:** conclusion of the item distribution contract 8.1.

- [x] Create `examples/consumer-boot` with POM own, outside the main reactor, consuming the versioned artifact and demonstrating a repository of `Book` or `Product`.
- [x] Add instructions to install the library locally, configure the connection, prepare indexes with separate credentials and run a flow CRUD/consult without depending on the main demo classes.

**Delivered in 2026-11-08:** runtime-free core Boot/Actuator/Micrometer Core, auto-configuration in its own artifact and starter adopted by the demo. The Java names of the classes that moved were preserved; Boot consumers of this SNAPSHOT must change the direct dependency of the starter. POMs include license MIT, author, SCM/URLs. Eight JARs of code/sources/Javadoc were conferred. API, compatibility and migration policy in [docs/public-api-and-compatibility.md](docs/public-api-and-compatibility.md). The consumer [examples/consumer-boot](examples/consumer-boot/README.md), outside the reactor, packaged the installed artifacts and passed CRUD, IDX, FT projection, UNIQUE and version with separate users/scheme and disposable Datadam. Validation: 395 approved tests, plus 564 re-executions in the matrix; [evidence and hashes](docs/validation/2026-11-08-consumption.json). There was no change C++, CI, signature or remote publication.

### 8.2. Continuous integration and release

- [x] Automate build, unit tests, and integration with real Datadam in the Docker.
- [x] Create release checklist, changelog and update orientation.
- [x] Define publication destination and prepare signature/credential without including them in the repository.
- [ ] Publish the first version only after explicit authorization for the chosen destination.

**Done when:** another project can consume a versioned artifact with documentation, reproducible tests and upgrade instructions.

#### 8.2.1. Example — validating a version

**Dependence:** consumer application independent of the item 8.1.1.

- [x] Add a consumer smoke test script with the candidate version: startup, health, registration, reading and indexed query, using your own data.
- [x] Document local execution and IC and how to check the version of the artifact. Keep remote publication as a separate action according to the item contract 8.2.

**Preparation completed on 2026-11-08. Publication pending.** GitHub Actions workflows use actions pinned to commit SHAs, a portable runner, an isolated server with ACLs and an image identified by revision and ID, the test suite and compatibility matrix, and a standalone consumer. This milestone provides the changelog, checklist, environment-based signing configuration, and a Central-compatible bundle with checksums. Local candidate 0.1.0-rc.1 passed smoke tests for startup, version, health, CRUD, IDX, and FT. Validation also verified 13 signatures with a disposable key and exercised four expected rejection cases. All 395 tests passed, with 564 additional runs in the matrix. CI was reproduced locally on Windows. The hosted GitHub run and Portal validation are pending. The chosen destination is Maven Central. The maintainer must configure and verify the com.anlohse namespace (groupId com.anlohse.datadam, updated November 9, 2026) and the production signing key. The candidate has dirtySource=true and a test signature, so it cannot be published. The final checkbox stays open until publication is authorized and completes. See [docs/ci-and-release.md](docs/ci-and-release.md), [CHANGELOG.md](CHANGELOG.md), and the [recorded results](docs/validation/2026-11-08-ci-release.json).

**Update in 2026-11-09:** groupId changed to `com.anlohse.datadam` in the parent, modules, demo and independent consumer. In the first validation of this change, Java packages were still `io.datadam.*`. The posterior migration of packages is registered below. The artifactIds remain. Maven metadata, manifesto and layout Central were updated. Old bundles are refused. 395 tests, plus 564 reruns in the matrix and the local candidate smoke with the new coordinates. The candidate of this migration is not signed or published. [Evidence of migration](docs/validation/2026-11-09-groupid-migration.json).

**Package migration in 2026-11-09:** Java packages changed from `io.datadam.*` to `com.anlohse.datadam.*`, with import update, Boot records, Lua paths and entry points. The final validation approved 395 tests, 564 reruns in the matrix, independent consumer, catalog CLI and local candidate with 13 payloads. The JARs verifier started to refuse old classes and inconsistent Boot records. [Evidence of packages](docs/validation/2026-11-09-package-migration.json) Remote publication remains pending.

## 9. Spring transactions — investigation complete

- [x] Investigate the effective guarantees of MULTI/EXEC, WATCH and execution errors in Datadam.
- [x] Document isolation, rollback and block durability limits.
- [x] Evaluate connection, Spring propagation and compatibility with API repository synchronous.
- [x] Test the native protocol with actual Datadam, including errors, UNIQUE, version and loss of connection.
- [x] Record the reason for the Spring manager's postponement.

**Done when:** the decision is registered and any support offered has proven guarantees, without suggesting automatic equivalence with transactions of a relational bank.

**Decision on 2026-11-15:** `MULTI/EXEC` queues commands and returns a result for each operation. `WATCH` can prevent execution. Errors during `EXEC` leave other writes applied. A bound connection alone would change the synchronous results of `save` and reads, as well as when callbacks run. The Spring transaction manager was deferred. See the [transaction report](docs/spring-transactions.md) for the contract and test command.

### 9.1. Demo example — orders and inventory — complete

- [x] Create a Spring checkout example with `PurchaseOrder` and `StockItem` that reports complete, partial, and unknown outcomes.
- [x] Demonstrate a partial checkout, a lost stock reply, and read-only reconciliation by order ID against Datadam in Docker.

The demo uses two versioned repositories and an explicit checkout workflow. The Spring transaction manager remains deferred. See the [checkout walkthrough](docs/demo-examples.md#91-orders-and-inventory).

## 10. Reactive repositories — future work

- [ ] Select and validate a truly non-blocking transport that preserves the response formats used by Datadam.
- [ ] Implement reactive operations for JSON, IDX and FT, with cancellation and demand control.
- [ ] Create reactive repositories and set your support to TTL, version, audit and projections.
- [ ] Test competition, closure of connections and absence of blocking of event processing threads.
- [ ] Add application/reactive examples and document compatibility with API imperative.

**Done when:** the API reagent has non-blocking transport and tested behavior. Encapsular called Jedis blockers in Mono/Flux will not be considered completion of this step.

### 10.1. Demo example — reactive telemetry reads

**Dependence:** non-blocking transport and reactive repositories delivered in milestone 10.

- [ ] Create `TelemetryReading` and a reactive stream of persistence/query with demand control, according to API that is delivered.
- [ ] Add router and validation of cancellation, errors and limited demand, verifying the absence of blocking in the threads of the flow. Keep this example pending until there is the necessary transport.

## Out of scope for now

- TEXT, GEO, VECTOR, aggregations and scoring that Datadam has not yet implemented.
- Emulate a Redis cluster or multiple databases in the library.
- Promise constant cost for operations that the server performs by scanning.
- Introduce changes to the C++ server as a silent dependency on a library resource. If necessary, they will be identified and planned separately.

## Progress log

| Delivery | State | Evidence |
|---|---|---|
| Base JSON, native indexes and searches | Completed | Library, application HTTP and local Docker |
| Derivative queries | Completed | 33 tests passed in the last confirmed execution, adding the existing suite |
| 1.1 — TTL of entities | Completed | 52 tests passed in total. Standard/override, Duration, persistence, UNIQUE, ACL, indices and HTTP. Durability limit in documented crash |
| 1.2 — Generation of IDs and identification of new entities | Completed | 81 tests passed in total. Optional generation, new state, classes/records, collisions, TTL, failures and HTTP |
| 1.3 — Audit and callbacks | Completed | 108 tests passed in total. Optional audit, order and protection of callbacks, records/classes, clock and HTTP |
| 2.1 — Optimistic positioning com @Version | Completed | CAS native, competition, versioned exclusion and integration JSON/HASH |
| 5.1 — Projections | Completed | Records/interfaces, native selection IDX/FT and JSON/HASH; 262 Java tests in delivery |
| 5.2 — Extension of the derivation | Completed | String prefixes and numerical negation with presence. Matrix and examples; 283 approved Java tests (132 units and 151 integration) |
| 6.1 — Inspection and diagnostics | Completed | Typed scheme/status, comparison without DDL and derived plans; 309 approved Java tests (152 units and 157 integration) |
| 6.2 — Explicit migration | Completed | Retreadable version preparation, validation, separate withdrawal and recovery UNIQUE; 332 approved Java tests (167 units and 165 integration) |
| 7.1 — Library diagnosis | Completed | configurable pool, optional health/metrics and sanitized telemetry; 358 approved Java tests (187 units and 171 integration) |
| 7.2 — Failure tests and safety | Completed | Uncertain result failures, restart/pool, TLS with identity and matrix ACL; 373 approved Java tests (187 units and 186 integration) |
| 7.3 — Compatibility and Performance Matrix | Completed | Java 17/21/25, image by revision/ID, workload and published artifacts; 376 Maven tests and 561 additional unit executions in the matrix |

At the end of each delivery, we will add here the result, the tests performed and any decisions that alter the next steps.
