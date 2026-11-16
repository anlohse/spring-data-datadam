# HASH mapping and CRUD

The item 4.1 allows you to choose the persistence format per entity. JSON remains the default, without changing existing entities:

```java
@DatadamDocument(prefix = "note:hash:", storage = DatadamStorage.HASH, timeToLive = 300)
public record Note(@Id @DatadamGeneratedId String id, String text, @Version Long version) {}

public interface NoteRepository extends DatadamRepository<Note, String> {}

Note first = repository.save(new Note(null, "original", null)); // HASH, version 0
Note next = repository.save(new Note(first.id(), null, first.version())); // remove text field, version 1
repository.delete(next); // requires the current version
```

Use unique prefixes by type/format. Change the annotation of an already persistent entity does not convert its keys. Read/save HASH on a key JSON generates WRONGTYPE and preserves the existing value. Migration must use another prefix or an explicit and coordinated conversion. There is no automatic migration.

## Server requirement

The recording HASH requires **HREPLACE**, a Datadam extension added in this delivery, along with the correction of the transaction wave mechanism HASH. The C++ code was changed and the local image was reconstructed. The reproducible patch is in [server-patches/hreplace.patch](../server-patches/hreplace.patch), with instructions in [Patches server/README.md](../server-patches/README.md).

HREPLACE replaces the complete set of fields in a single KeyTxn, preserves expiration, rejects another type of key and validates UNIQUE over the final state. It is not a standard Redis command. The library does not simulate this contract with HSET followed by HDEL or deletes the key before re-creation: these sequences may expose partial state, lose TTL or fail by an intermediate combination of fields that does not exist in the final result.

Servers without extension refuse writing without replacing it with a less secure path. Entities JSON continue to use the previous commands. For the local environment, run `./scripts/run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background`.

## Field conversion

Each serialized property on the root turns a field HASH with the name Jackson, including JsonProperty/naming strategy. Field `_class`, flattening of objects or auxiliary keys per entity are not used.

| Java value | Field HASH |
|---|---|
| String/char | Text without quotes JSON. Escapes are from actual content |
| Numbers and Booleans | Textual representation of value JSON, e.g. `42`, `12.50`, `true` |
| Standard Enum | Text name, e.g. `ACTIVE` |
| UUID | Text UUID |
| Dates/hours/Duration | Jackson representation configured, textual or numerical. Application modules maintained |
| Subject matter/ringed record | JSON of the object within a single field HASH |
| List, array or Map | JSON complete within a single field HASH, preserving the structure |
| byte[] | Jackson's Base64, in a Text Field |
| null in the root | Field omitted/removed |

The reading uses the declared type of the property to reconstruct the value. A literal string `"null"` remains the text `null`, different from an omitted field. Integral and BigDecimal numbers preserve accuracy. The codec avoids converting decimals to double during the passage through JSON. Ringed objects/collections are not available as separate fields HASH. Their internal paths still belong to the JSON stored in that field.

When saving, the set of fields is replaced in its entirety. Fields previously present and now null, excluded by Jackson configuration or removed from the model cease to exist, including external fields that are not reserialized. Field absent in the reading follows Jackson configuration: normally null in wrappers/references and zero/false in primitives. Constructors or more restricted options may refuse absence.

Object root properties are refused, as a native string such as `true` does not distinguish Boolean string without additional metadata. Declare the concrete type, Map or List. char[] at the root is also refused by having Jackson ambiguous representations between text and array. Use Character String or List. Dynamic values within containers JSON keep their format JSON. Custom serializers that change the expected form of a type need compatible reading conversion. The contract of this stage covers the representations of the table, not any arbitrary codec. ID should be part of the serialized fields and maintain its name/value in a stable way.

## CRUD and entity lifecycle

Repositories and template maintain the same API: save/saveAll, findById/findAll/findAllById, existsById/count, delete/deleteById/deleteAll and persist. HGETALL materializes each entity; SCAN uses TYPE hash and model prefix. Hash and JSON with the same ID can coexist in different prefixes. Reading one format does not include keys from the other.

Generated IDs, records, transferable classes, Persistable, audit, and callbacks follow previous contracts. Before writing: BeforeConvert, audit, and BeforeSave. After confirmation: application of managed values in transferable classes and AfterSave. AfterConvert is also applied to readings HASH. Records return a new instance with ID/version. Always use the return of save.

The creation data received is preserved in updates, with the same documented limits of confidence in the values provided by the application. The HTTP example loads the metadata of creating the stored state, but keeps the expected version sent by the client. BeforeSave faults refuse writing; AfterSave faults continue to be AfterSaveCallbackException because writing has already been confirmed. Effects on audit memory/callbacks do not have automatic rollback.

### Versioning and exclusive creation

`@Version` accepts the same types: Long/Integer start in null and are persistent in zero. Long/int start in zero and are persistent in one. In the update, the counter increases in Java and content/version are written together by HREPLACE. The script compares HGET as exact whole text, without conversion to the Moon number, including above 2^53.

New versioned entities, generated IDs, and Persistable.isNew=true use exclusive creation. A duplicate versioned creation generates OptimisticLockingFailureException. An unversioned generated ID collision generates DuplicateKeyException. Versioned updates/deletions require a corresponding version. Removed/expired keys are not recreated by an update. deleteById reads the current version and makes conditional deletion, as in JSON. An already absent key is a no-effect operation.

Old documents without version field require explicit migration. Reusing an ID after deletion/expiration maintains the limit ABA of the numeric counter. Commands HSET/HDEL/HREPLACE direct do not pass through the entity cycle. External writers must follow the same protocol to get version control.

### TTL, UNIQUE, and failures

The exclusive comparison/creation, substitution and eventual TTL occur under the isolation of a script. Before recording with TTL, the script checks PEXPIRE without changing the key. Without explicit policy, HREPLACE preserves the existing expiration date; TTL per type or instance is reapplied only after a successful replacement. Duration values are also accepted and converted to milliseconds by the TTL policy.

A UNIQUE refusal preserves all the fields, version, audit, expiration and owners of the indexes. The server validates the final state: removing one field from a composite index and changing another in the same substitution does not attempt to publish an intermediate combination. The undo correction included in the patch prevents the refusal from transforming old fields into persistent deletions.

HREPLACE records fields and version in the same native transaction. Durability limits remain valid: an index UNIQUE may anticipate commit, therefore HASH + TTL do not receive an additional guarantee of durable commit set after crash. A transport failure may leave the result uncertain. There is no automatic retry or transaction between entities. Batch operations are sequential and may have partial success.

Application permissions: EVAL, HLEN, HGETALL, HREPLACE; HGET for version; PEXPIRE with TTL; DEL to delete; SCAN to list; PERSIST to remove expiration. The Docker configuration declares these permissions and the demo prefix:hash:*. The ttl-restricted user continues without PEXPIRE to test refusal before writing.

## Indexes and queries

The item 4.1 delivers the CRUD. The item 4.2 adds ON scheme HASH and queries IDX/FT declared, derived and programmatic. See [indexes and queries HASH](hash-indexes-and-queries.md) for aliases, separators, differences of TAG/NUMERIC and derivation limits. The specific tests of CRUD still use administrative fixations to isolate the protection UNIQUE from writing.

Operations template.json(...) continue to refuse models HASH. There is no API partial field changes HASH at this stage. The `/hash-customers` example expands `/hash-notes` with native searches equivalent to those of the Customer JSON, respecting the differences in formats.

## HTTP example

`/hash-notes` uses HashNote with generated ID, Long version, audit, callback normalization, optional content, nested list and map and TTL five-minute standard:

```powershell
$data = @{ reference = [guid]::NewGuid().ToString(); text = ' rascunho '; tags = @('hash'); details = @{ enabled = $true } }
$first = Invoke-RestMethod http://127.0.0.1:8080/hash-notes -Method Post -ContentType application/json -Body ($data | ConvertTo-Json -Depth 5)
$data.version = $first.version
$data.text = $null # remove field HASH text
$current = Invoke-RestMethod "http://127.0.0.1:8080/hash-notes/$($first.id)" -Method Put -ContentType application/json -Body ($data | ConvertTo-Json -Depth 5)
# Repeat PUT with version=0 receives HTTP 409.
Invoke-RestMethod "http://127.0.0.1:8080/hash-notes/$($current.id)?version=$($current.version)" -Method Delete
```

POST requires missing/null version; PUT and DELETE require the expected version. Version conflicts/UNIQUE return HTTP 409. Audit is not received in DTO entry.

## Validation

HashMappingTest covers format choice, conversions, null, aliases, decimal/temporal accuracy, managed values and recording failures. HashRepositoryIntegrationIT uses Real Datadam on Docker: complete cycle, replacement without old fields, UNIQUE with full preservation and evaluation of the final state, concurrent version by independent pools, int64 accuracy, TTL numerical/Duration, mutables/records, types, coexistence/WRONGTYPE, ACL and HTTP.

The C++ patch includes TTL tests, duplicate fields, bank reopening, WRONGTYPE and refusal UNIQUE within the script preserving the data before and after the bank reopens. The Docker stage `test` executes the native suite and server integration/persistence tests.
