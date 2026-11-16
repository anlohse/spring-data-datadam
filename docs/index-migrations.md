# Explicit index migrations

The item 6.2 prepares a new index without replacing the index that the application uses. The flow has three independent phases: **prepare**, **exchange consumers** and **remove previous index**. There is no logical alias, atomic name exchange, automatic repositories update or automatic rollback.

## 1. Declare a separate versioned target

```java
@DatadamDocument(prefix = "customer:")
@DatadamIndex(name = "customers:search:v2", kind = DatadamIndex.Kind.SEARCH,
    fields = {
        @IndexField(path = "$.city", alias = "city", caseSensitive = true),
        @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC),
        @IndexField(path = "$.email", alias = "email", caseSensitive = true)})
record CustomerSearchV2(@Id String id) {}

var migration = new IndexMigration(
    CustomerSearchV2.class, "customers:search", "customers:search:v2");
var migrations = new DatadamIndexMigrations(administrativeCommands);
```

The target must be declared in the informed model and have suffixed `:vN`, with positive integer N. Origin and target must have different names. Origin must exist, be READY and cover exactly the same prefix and format JSON/HASH. It is allowed to change the family IDX/FT and the scheme. These changes need to be compatible with the application's queries and data.

Use a separate preparation model of the active entities/repositories. The derivative chooses between the entity's notes: adding v2 to the active entity may change that choice in the next execution, even before the intended deployment. Creating v2 on the server, using a separate model, does not change the plan of the old entity. Do not include the preparation model in the automatic provisioning of all schemes.

## 2. Prepare and validate

```java
IndexMigrationStatus state = migrations.inspect(migration); // read only
state = migrations.prepare(migration, Duration.ofMinutes(5));
if (!state.readyForUse()) throw new IllegalStateException("Migration is not ready");

// Revalidate before publishing consumers referring to the new name:
migrations.validateReady(migration);
```

`prepare` validates the scope of the origin and the declared schema. If the target does not exist, it creates **only this index**, with full backfill. It does not use SKIPINITIALSCAN. If it exists and the schema corresponds, it takes advantage of it and waits READY. Different schema interrupts the flow with differences by property, without DROP or substitution. A concurrent creation that responds to otherready existences is also inspected and compared.

The return contains the migration declaration, origin inspection and target comparison. `readyForUse()` requires both indexes READY and compatible target scheme. `inspect` may show missing target/BUILDING/FAILED and absent origin, without creating anything. `validateReady` requires READY state. Does not execute DDL nor changes routing.

The timeout limits waiting by backfill, not the sum of metadata calls, which also follow the connection/socket timeout. Schematics and states are observed by separate commands. FT.INFO scrolls through the index to obtain complete/statistical metadata. Use [light status](index-diagnostics.md) for frequent polling.

READY confirms that the backfill of this index is over, but does not replace tests of business queries. Field changes, split TAGs, box, aliases and missing values can change the results. IDX compound excludes documents that do not provide all indexable fields. Make validation queries using credentials with access to the necessary data and confirm filters, sorting, coverage and totals according to the new semantics. The API migration does not read or correct documents.

An existing index created externally with SKIPINITIALSCAN may have a compatible schema and state READY without covering historical data. The server does not expose this source in INFO. Retrieve only targets created by this stream or whose creation with full backfill has been verified operationally. The library cannot certify this history from INFO.

## 3. Switch the application to the new index

After `validateReady` and the business tests, make a statement that explicitly reference `customers:search:v2` in `@FtQuery`, `@IdxQuery`, in API programmatic or in the statements of the entity used by the referral. For example annotated:

```java
@FtQuery(index = "customers:search:v2", value = "@city:{?0} @age:[?1 ?2]")
Page<CustomerSearchV2> byCityAndAge(String city, int minimum, int maximum, Pageable page);
```

The index needs to be declared in the entity of this repository. The server does not redirect the old name to the new one. During a gradual deployment, old and new versions may consult different names. Keep both while there are old consumers, including scheduled tasks and workers. To return to the previous version, return the deployment while the previous index still exists. This does not undo the effects of new restrictions UNIQUE on writes.

**UNIQUE has affected the writes since CREATE, prior to the exchange of queries.** The new index is maintained as soon as it is recorded. Before preparing it, adjust the producers and resolve the data incompatible with the new restriction. Migration is not a phase without effects on writes, even if the reading route remains intact.

## 4. Retire the old index separately

```java
// With another connection, authorised to DROP, after removing all old consumers:
var cleanup = new DatadamIndexMigrations(retirementCommands);
cleanup.retirePrevious(migration);
```

This operation revalidates schema and readiness and sends only `IDX.DROP previousIndex`. Datadam accepts IDX.DROP both for IDX and FT. FT is not used.DROPINDEX DD and no document is removed. API cannot detect traffic or prove that all consumers have migrated. This decision belongs to the Deployment/Operation. `prepare` and `validateReady` never call DROP.

Do not remove the previous one if it is still necessary for rollback. After DROP, the old declaration also needs to exit the layout of the application. Otherwise, `ensureIndexes` may recreate it. Revalidate the migration by the previous name after its removal informs absent origin: the report is not a permanent history of execution.

Competing administrative operations need to be serialized externally. Checking and DROP is not an atomic operation with the application or with another administrator that can remove/recreate the same name. Use unchangeable version names and do not reuse completed migration names.

## UNIQUE failures and recovery

If existing data violates UNIQUE, the target is FAILED, the preparation launches `DataIntegrityViolationException` and the previous index remains. The library does not erase documents, chooses a winner, modifies values, relaxes UNIQUE or recreates the target automatically.

1. Inspect the failure and identify the data with authorized tools.
2. Solve duplicates explicitly, respecting the domain and competing producers.
3. With cleaning credentials, call `discardFailedTarget(migration)`. This operation requires corresponding scheme and state FAILED, maintains the origin READY and removes only the target.
4. Run `prepare` again and repeat business validation.

The method of discarding rejects targets READY or BUILDING. To abandon a construction in progress, use a separate administrative intervention and evaluate its consumers. There is no automatic cancellation by the library. An index FAILED is not maintained by the writes. Correcting the data does not make it READY by itself.

Errors may contain index names, keys or values provided by the server. Treat the administrative output according to the sensitivity of the data and not publish it in logs HTTP.

## Timeouts, disconnects, and restarts

- Timeout/stopping wait leaves the existing indexes on the server. New `prepare` run inspects the target and resumes waiting, without creating another name or removing the origin.
- If the CREATE answer is lost, the API spreads the failure without repeating the mutation within the same call. Run again with the same name/intendence to find out if the definition was registered. Only "already exists" is treated as a creative dispute, after a new comparison of the scheme.
- Datadam persists in settings and automatically restarts the backfill of an index that has become BUILDING. After restarting the server, rewrite the connection/client, inspect and run `prepare`/`validateReady` again. Progress can start over.
- There is no migration table or cutover decision persisted by the library. The resume uses the version name and the server schema.
- The loss of the response of DROP also leaves the result uncertain. Inspect the names before deciding the next step. Do not repeat deletions blindly or reuse the name removed.

Failures ACL/transport are not converted to missing index. The API does not extend privileges or use an alternative connection automatically.

## Dedicated credentials

The local Docker separates three roles:

| User | Index permissions | Use |
|---|---|---|
| `index-observer` | IDX.LIST, IDX.INFO, FT.INFO | Diagnosis only reading |
| `index-migrator` | IDX.INFO, FT.INFO, IDX.CREATE, FT.CREATE | Preparation/validation without DROP |
| `index-retirer` | IDX.INFO, FT.INFO, IDX.DROP | Explicit withdrawal or disposal without CREATE |

These users also have connection/authentication. None receive read/write commands of documents or permissions to change ACL. The application `app` continues without DDL. Adjust users and passwords in the real environment. The secrets of the example are only local.

ACL of index commands does not limit the index name by key pattern. These administrative and metadata privileges are global. An authorized connection to DROP is not confined to the entity's prefix by ACL. API checks scope, but the access control must consider the native command. To validate queries/data, use another user with the necessary commands and reading patterns.

After editing the local configuration, `docker compose restart datadam` loads the new credentials without removing the volume. Do not grant DROP to the convenience preparer.

## Runnable example

`CustomerSearchV2` is the separate preparation model of `Customer`. The example migrates the FT `demo:customers:search` to `demo:customers:search:v2`, with exact city and email in the schema. This changes semantics in relation to the original FT with folding and tags. The active application continues to use its current statements. The example does not deploy/cutover.

```powershell
$env:DATADAM_MIGRATION_USER = 'index-migrator'
$env:DATADAM_MIGRATION_PASSWORD = 'local-index-migrator-password'
java '-Dloader.main=com.anlohse.datadam.demo.IndexMigrationApplication' -cp datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher inspect
java '-Dloader.main=com.anlohse.datadam.demo.IndexMigrationApplication' -cp datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher prepare
java '-Dloader.main=com.anlohse.datadam.demo.IndexMigrationApplication' -cp datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher validate
```

The CLI requires exactly one action. `retire` and `discard-failed` are separate actions and require credentials `index-retirer`. They are not part of `prepare`. Do not execute `retire` in this example while the current repositories still depend on the original FT.

## Automated validation

`IndexMigrationsTest` test preparation of a single target, resumed, conflicts, lost creation response, invalid states, interruption and separation between preparation/discard/withdrawal. `IndexMigrationsIntegrationIT` validates JSON/HASH, historical backfill, schema collision, duplicates UNIQUE and its explicit correction, maintenance of the active plane and ACL of each paper.

`IndexMigrationRestartIT` starts a Disposable Datadam in random loopback port, loads 15.000 documents and interrupts the process while the FT target is BUILDING. After restarting, another client resumes the preparation and checks the reconstruction and the totals in the two indexes without new CREATE/DROP. The counter observed after restart confirms that the backfill has been resumed. The container and its anonymous volume are removed at the end. The application service is not restarted by this test.

Run `scripts/run-local.ps1 -Restart -IntegrationTests -Background`. Restart tests require Docker CLI and the version image of [test contract](../config/test-images.properties); `-BuildDatadam` prepares the local revision fixed when necessary. No additional C++ change is required by this delivery.
