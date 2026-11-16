# Optimistic locking with @Version

Milestone 2.1 adds optimistic locking to JSON documents through `org.springframework.data.annotation.Version`. When two writes target the same existing version, only the first succeeds and increments the version. The second receives `OptimisticLockingFailureException`.

## Use in a repository

```java
@DatadamDocument(prefix = "note:")
public record Note(@Id @DatadamGeneratedId String id, String text, @Version Long version) {}

Note first = repository.save(new Note(null, "original", null)); // version = 0
Note current = repository.save(new Note(first.id(), "alterado", first.version())); // version = 1
repository.save(new Note(first.id(), "atrasado", first.version())); // conflito
repository.delete(first);   // conflict: version 0
repository.delete(current); // Excluded: version 1
```

Always use the entity returned by `save`/`saveAll`. Records receive a new instance by the canonical constructor, which needs to preserve the managed values of ID and version. Mutable classes receive generated ID and version only after confirmation of writing. Denials by version, UNIQUE or ACL do not advance these fields in memory. Auditing and callback effects on mutable objects remain subject to the documented contract of absence of rollback in memory.

## State and accepted values

| Field @Version | New State | Version of the first recording | Update |
|---|---|---|---|
| `Long` or `Integer` | `null` | `0` | expected value + 1 |
| `long` or `int` | `0` | `1` | expected value + 1 |

The version starts to determine if the entity is new, before examining the ID. Thus, an ID already assigned with wrapper null version requires exclusive creation. A wrapper zero version already represents existing entity. In the absence of `@Version`, previous creation/substitution behaviors remain.

`Persistable.isNew()` continues to be consulted, but needs to agree with the state indicated by the version. Inconsistencies are refused before writing. The library does not change Persistable's own flags.

Only the four types of the table are accepted, with a single version field in the hierarchy. Negative and overflow values are refused. At most, it is still possible to read or delete the entity with the correct version, but not update it. The version should be persistent, etching (or record component) and distinct from ID, TTL and audit fields. The annotation is inspected in fields such as `@Id`.

The name JSON can be renamed with Jackson, including `@JsonProperty("rev.no")`. The recorded version needs to be an exact whole JSON, readable back by the entity. Numeric serialization as string is refused before transport. Documents written externally must follow that same format. Strings, floats and absence of field do not equal the entire expected version.

## Server-side comparison

A script EVAL executes, under Datadam isolation:

1. Read only the version field via JSON.GET.
2. In creation, it requires missing key. In update/exclusion, it requires the expected version.
3. To save with TTL, check permission PEXPIRE before writing.
4. Runs JSON.SET root containing **content and new version together**, with NX in creation and XX in update. Then applies TTL, when specified. To delete, run DEL after comparison.

The comparison is not made in the client. The arithmetic uses the integers of Java and the comparison uses the exact whole text returned by JSON.GET, without converting the version to the floating point number of the Moon. This preserves Long values above 2^53, including Long.MAX_VALUE

An update does not recreate an entity removed or expired. Duplicate creation and obsolete/absent version result in `OptimisticLockingFailureException`. Conflict with index UNIQUE remains `DuplicateKeyException`. Content and version belong to the same JSON.SET, therefore a refusal UNIQUE cannot increase the version separately.

Refusals by version and UNIQUE preserve the entire document, its indexes IDX/FT and the absolute expiration date. A successful save respects TTL by entity or type. Without explicit policy, preserves the existing expiration. `persist(id)` is an explicit expiry only operation: it does not increase the version of the document.

Permissions required: EVAL, JSON.GET and JSON.SET to save; PEXPIRE when there are TTL; DEL to exclude, in addition to access to keys. The local application user already has such permissions. No need to administer indexes.

## Auditing, callbacks, and deletion

The order remains `BeforeConvert → auditing → BeforeSave → write → AfterSave`. Callbacks prior to writing receive the expected version; AfterSave receives the new confirmed version. AfterConvert receives the read version. Callbacks cannot change the version, identity or new state captured. A violation in AfterSave is reported by `AfterSaveCallbackException`, because the writing has already occurred.

Creation dates/authors use the status decision based on the version, even with assigned ID. The creation fields received are preserved in updates. The library does not authenticate values that the application has already delivered modified. Example HTTP loads existing metadata and preserves the expected version sent by the client.

`delete(entity)` requires a persistent version corresponding to the current document. It does not increase the version. A new, obsolete, removed or expired entity generates conflict. `deleteById(id)` reads the current version and excludes it conditionally: an update between the reading and the exclusion is protected. If the ID is already absent, it is a no-effect operation. As it receives only the ID, this method does not know the version previously seen by the caller. To require the client's version, use `delete(entity)`.

`saveAll`, `deleteAll(entities)`, `deleteAllById` and `deleteAll()` are sequential and stop in the first failure. Operations already confirmed remain. They are not transactions and there is no automatic repetition.

## Direct commands, migration, and limits

Native commands by `DatadamCommands` do not perform audit, callbacks or conditional version increment. An external writer who modifies the content without participating in the protocol may allow a later update to overwrite his work. All writers must adopt the same comparison/encrement to obtain this protection. Do not use JSON.NUMINCRBY isolated in the version to simulate the complete operation.

Old documents without version **are not automatically migrated**. Adding annotation does not authorize overwriting them: both the creation attempt and an upgrade with assigned counter are refused. Plan an explicit migration with suspended writers or a coordinated protocol, assign a valid whole and only then activate the versioned repositories. For primitives, choose a persistent initial value greater than zero.

The counter protects an incarnation of the entity. If the key is deleted/expired and the same ID is reused, the counter restarts. An old copy with the same counter as the new entity cannot be distinguished only by `@Version` numerical (problem ABA). Avoid recycling IDs; UUID is the standard of the generator. A guarantee between incarnations would also require a token of own identity.

The server does not offer general rollback scripts. The [Document durability limits and TTL](entity-ttl.md) remain valid: a writing UNIQUE can anticipate commit, so document + TTL have no guarantee of durable commit set after crash. Content and version, in turn, are recorded in the same JSON.SET. A transport failure may occur after writing and leave the result uncertain. There is no automatic retry. Reread the status before deciding how to recover the operation.

## HTTP example

`VersionedNote` combines generated ID, `@Version Long`, audit, callbacks, TTL five minutes and indexes IDX/FT with reference UNIQUE. A API local uses `/versioned-notes`:

```powershell
$data = @{ reference = [guid]::NewGuid().ToString(); category = 'draft'; text = 'original' }
$first = Invoke-RestMethod http://127.0.0.1:8080/versioned-notes -Method Post -ContentType application/json -Body ($data | ConvertTo-Json)
$data.version = $first.version # zero
$data.text = 'alterado'
$current = Invoke-RestMethod "http://127.0.0.1:8080/versioned-notes/$($first.id)" -Method Put -ContentType application/json -Body ($data | ConvertTo-Json)
# Repeat this PUT with version=0 returns HTTP 409; current.version is 1.
Invoke-RestMethod "http://127.0.0.1:8080/versioned-notes/$($current.id)?version=$($current.version)" -Method Delete
```

POST requires an absent or null version. PUT requires the expected version in the request body. DELETE requires `?version=...`. The API returns HTTP 409 for a stale version or UNIQUE constraint violation, HTTP 400 for invalid input, and HTTP 404 when loading a missing key. Loading audit fields preserves the version supplied by the client.

## Validation

`EntityVersionTest` covers metadata, status, types, overflow, callbacks, serialization and ID/version application only after confirmation. `OptimisticLockingIntegrationIT` uses the actual Datadam and two independent pool connections for the dispute, in addition to TTL, IDX/FT, UNIQUE, concurrent exclusion, expiration, audit, ACL, batches, int64 accuracy, refused migration and HTTP.

Run the complete suite and go up the example with `./scripts/run-local.ps1 -Restart -IntegrationTests -Background`.
