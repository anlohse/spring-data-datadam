# IDs and new entity detection

Item 1.2 adds optional generation of IDs and local detection of new entity. Application-informed IDs continue to be preserved.

## Enable ID generation

```java
@DatadamDocument(prefix = "note:")
public record Note(@Id @DatadamGeneratedId String id, String text) {}

Note input = new Note(null, "rascunho");
Note saved = repository.save(input);
// saved.id() contains a UUID; input.id() continues null.
```

`@DatadamGeneratedId` must be in the same field as `@Id`. The standard generator uses UUID randomly and accepts `String` or `java.util.UUID`. Type UUID is preserved when reading the entity again. The key remains `prefix + id.toString()`.

Always use the return of `save` and `saveAll`. For records, the library calls the canonical constructor with the generated ID and the values of the other components. This constructor needs to accept the new ID without transforming it. The library refuses a transformation that would make the returned ID diverge from the key. Other immutable constructors, builders and wither methods are not yet used.

For mutable classes, the ID field must be writable and included in serialized JSON. A successful save populates the ID on the instance it returns. This is the original instance unless a callback replaces it. A final ID field in a non-record class does not support ID generation. The library respects Jackson's JSON names, including `@JsonProperty` and the configured naming strategy.

Without generation annotation, the application remains responsible for informing a valid ID before saving. An existing ID is never replaced by the generator, even if the field has annotation.

## How the library detects new entities

The module displays the same decision in `EntityModel.isNew` and `EntityInformation` used by Spring Data. The inspection follows the [Spring Data state strategies](https://docs.spring.io/spring-data/commons/reference/is-new-state-detection.html), with blank string also treated as missing ID.

| Entity/ID | Local decision |
|---|---|
| Implementation `Persistable` | Use `Persistable.isNew()` |
| There is `@Version` wrapper null, no Persistible | New, even with assigned ID |
| It has `@Version` primitive zero, without Persistable | New, even with assigned ID |
| It has `@Version` valid and already persisted, without Persistable | Not new |
| Invalid ID | New |
| Empty string ID or spaces only | New |
| Primitive numeric ID equal to zero | New |
| Numerical wrapper ID equal to zero | Not new: zero is an assigned value |
| Too many IDs present | Not new |

This decision does not consult Datadam and does not prove that the key exists. An entity with assigned ID may not yet be in the bank. Without @Version, implement `Persistable` to explicitly distinguish this case.

Since the item 2.1, `@Version` participates before the ID inspection. With Persistable, the two decisions need to agree. The audit and the callbacks use the same local decision. See [optimistic locking with @Version](optimistic-locking.md) and [audit and callbacks](auditing-and-callbacks.md).

### Write policy

The table below applies to entities **without @Version**. Entities versioned always use exclusive creation or update conditioned to the persistent version, according to the competition document.

| Case | Behaviour |
|---|---|
| Missing ID and Enabled Generation | Generate and try to create with `JSON.SET ... NX` |
| ID already informed, common entity | Maintains previous behavior: save creates or replaces the key |
| `Persistable.isNew() == true`, informed ID | Just try to create, using NX |
| `Persistable.isNew() == false`, informed ID | Allows to create or replace, as normal save |
| `Persistable.isNew() == false`, missing ID with activated generation | Unconsistent state objection before recording |
| ID missing, generation disabled | Null/String blank are refused. There is no implicit generation |

For compatibility, a zero primitive numeric ID without generation can continue to be saved as a completed key in `0`. The local state decision does not alter this save for exclusive insertion. With generated, zero primitive is the missing value that requires generation. It prefers wrappers when you need to represent absence explicitly and accept zero as assigned ID.

If you use `Persistable`, keep `getId()` consistent with the `@Id` field. Also control the change from the indicator again to existing: this item does not change arbitrary flags of the application. For example, a new entity flag can be ignored in JSON and assume false when loading. Repeatly save the same instance that continues to return true will try to insert again and may result in conflict.

## Custom generators

Register a bean that implements `DatadamIdGenerator` and inform its name in the annotation:

```java
@Configuration
class IdentityConfiguration {
    @Bean("documentIds")
    DatadamIdGenerator documentIds() {
        return context -> "doc-" + UUID.randomUUID();
    }
}

@DatadamDocument(prefix = "document:")
public record Document(
    @Id @DatadamGeneratedId(generator = "documentIds") String id,
    String title
) {}
```

The context reports `entityType`, `idType` and `keyPrefix`. It is not necessary to access the server or modify the entity to generate the value. Beans-shared generators need to be safe for concurrent use.

In addition to String and UUID, custom generators can produce Byte, Short, Integer, Long and its primitives. The value needs to have the corresponding type: the library does not silently convert Integer to Long, for example. Null, String in blank and zero to primitive ID are refused. Zero numeric wrapper is accepted as present value.

The optional method `validate(context)` allows you to refuse types/configurations while creating the repository. It is also executed before direct saves by the template and should be limited to validation of metadata. A non-existent generator name or a type incompatible with the generator UUID prevents the creation of the repository.

Bean `datadamIdGenerator` is the default for notes without a generator name. You can replace it with a bean with that name. The replacement implementation must support the types used by entities that depend on it or delegate the other cases to `UuidDatadamIdGenerator`.

Outside Spring, the template's two argument builder provides the generator UUID. For own strategies, provide the complete map of the required generators:

```java
var template = new DatadamTemplate(commands, mapper, Map.of(
    "datadamIdGenerator", new UuidDatadamIdGenerator(),
    "documentIds", context -> "doc-" + UUID.randomUUID()
));
```

The project does not include a distributed numerical sequence ready. A numerical generator needs to define its own uniqueness and coordination. A memory counter does not guarantee this between processes or restarts.

## Collisions, TTL, and failures

Creation with generated ID uses NX so that a collision does not overwrite an existing document. Document ID collision JSON results in `DuplicateKeyException`. There is no automatic generation of another ID or repeat of the operation. An incompatible type of existing key can be rejected with type error by the server, preserving its content.

With TTL positive, the script of the item 1.1 also uses NX. If the key already exists, the script ends without renewing its term. Index conflicts UNIQUE and refusals of ACL continue to preserve the document and expiration in documented cases.

The ID is only applied to the moving input after the recording responds successfully. Validation faults, collision, UNIQUE, ACL or transport do not fill this field. In records, the input never changes. A generator may have consumed a value before a failure. Values are not returned or reused automatically.

Transport failure is an uncertain result: the server may have applied the recording without the response arriving. The caller does not receive a saved entity in this situation, and the library does not repeat the operation automatically. If the ID was generated in this attempt, it was not applied to the input. For streams that require reconciliation by known key, assign the ID in the application or keep a queryable business reference. Do not consider a new save from an input without ID an identical repetition.

`saveAll` remains sequential and may have partial success. The successful result contains all entities with their IDs. If it occurs failure in the middle, there is no batch rollback or return of your partial list. In records, business references help to locate already recorded items. Document durability limits + TTL after crash with UNIQUE remain those described in [TTL of entities](entity-ttl.md).

## HTTP example

`TemporaryNote` now accepts POST without ID, combining generation UUID, TTL and native indices:

```powershell
$noteData = @{
    reference = [guid]::NewGuid().ToString('N')
    category = 'demo'
    text = 'Nota com ID gerado'
    ttlMillis = 60000
}
$saved = Invoke-RestMethod http://127.0.0.1:8080/temporary-notes -Method Post -ContentType application/json -Body ($noteData | ConvertTo-Json)
$saved.id
Invoke-RestMethod "http://127.0.0.1:8080/temporary-notes/$($saved.id)"
```

The endpoint continues to accept an explicitly informed ID. Indexes do not need migration to activate the generation: the format of the key and existing documents remains the same.

## Validation

`EntityIdentityTest` covers the policy of state and generation, types, JSON, Spring configuration and faults without transport. `EntityIdentityIntegrationIT` uses Real Datadam for CRUD, immutable records, changeable classes, custom IDs, saveAll, collisions with and without TTL, UNIQUE, ACL, competition and HTTP.
