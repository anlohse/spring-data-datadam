# Auditing and callbacks

The item 1.3 uses `AuditingHandler` and `EntityCallbacks` from Spring Data Commons to provide optional audit and synchronous callbacks. It does not depend on keyspace notifications or administrative commands.

## Enable auditing

```java
@Configuration
@EnableDatadamAuditing(auditorAwareRef = "currentActor")
class AuditConfiguration {
    @Bean AuditorAware<String> currentActor() {
        return () -> Optional.of("application");
    }
    @Bean Clock auditClock() { return Clock.systemUTC(); }
}

@DatadamDocument(prefix = "note:")
public record Note(
    @Id @DatadamGeneratedId String id,
    String text,
    @CreatedDate Instant createdAt,
    @LastModifiedDate Instant modifiedAt,
    @CreatedBy String createdBy,
    @LastModifiedBy String modifiedBy
) {}
```

The four notes are `org.springframework.data.annotation` and can be used selectively. To record only dates, it is not necessary to provide `AuditorAware`. Without `@EnableDatadamAuditing`, the notes do not activate automatic filling. Callbacks work independently of the audit.

### Clock and auditor

A `DateTimeProvider` provides the instant of the operation. The library uses an available bean of this type; `dateTimeProviderRef` selects a specific provider. Without a provider, it gets a clock available or uses UTC and produces `Instant.now(clock)`. The audit watch does not change the Datadam watch or the calculation of TTL.

The author comes from `AuditorAware<T>`, whose type must be compatible with the author fields. When there are multiple candidates, choose by `auditorAwareRef` or `@Primary`. Optionally empty preserves existing values. The same applies to dates when the time provider does not provide a value.

`modifyOnCreate = true` also fills the fields of change in creation. With false, fills only those of creation at that time. `setDates = false` allows to fill authors without dates. Both are attributes of `@EnableDatadamAuditing`.

Prefer Instant for persistence. Conversions to Long in milliseconds and Date have also been validated. Other types depend on the conversion available in Spring for the value provided. Incompatibilities fail before writing.

### New entities and creation fields

The state is captured once at the save input, before callbacks, audit and ID generation, by the item policy 1.2. New entities receive creation data. Updates receive only alteration data. Changeable classes, inherited fields and records are supported. In records, the handler returns new instances. Always use the save/saveAll return.

The detection does not consult the bank. Without @Version, an assigned ID usually indicates existing status, even if the key is not yet stored. Use `Persistable.isNew() == true` to explicitly declare a creation. With @Version, a null or zero primitive wrapper version indicates creation even with assigned ID; Persistable, if implemented, needs to agree.

The creation fields provided in the entity are preserved in updates. This does not mean searching for the original values in the bank or preventing the application from delivering forged values before calling the library. A API must receive DTOs without audit fields and load the existing entity before changing its business fields, as it does `/audited-notes`.

In this version, audit notes should be at the root of the entity. Embedded metadata and implementations of `Auditable` are refused. Each field should have a single audit function, be serialized in JSON and cannot simultaneously be ID or TTL.

## Callbacks and execution order

The `com.anlohse.datadam.spring.callback` interfaces extend `EntityCallback<T>` from Spring Data.

| Callback | Time |
|---|---|
| BeforeConvert | Before audit and serialization |
| BeforeSave | After audit, before ID generation and writing |
| AfterSave | After confirmation of writing, with the effective key |
| AfterConvert | After deskilling each entity read |

The saving cycle is:

1. Validate mapping and capture new ID/state.
2. Run BeforeConvert.
3. Apply audit if enabled.
4. Run BeforeSave.
5. Validate TTL, generate ID when necessary and prepare the JSON.
6. Record with the corresponding NX/TTL policy.
7. After success, apply the generated ID to the changeable instance, when applicable.
8. Run AfterSave and return the resulting entity.

`DatadamSaveContext` informs type mapped, prefixed, key and new status captured. The key is null in the steps prior to recording when ID is missing. Use DatadamIdGenerator to generate IDs. Previous callbacks cannot change identity or new status.

Callbacks of the same stage obey the Ordered or @Order, with lower values first. The order is valid within each operation and does not serialize calls competitors. Callback Beans need to be safe for simultaneous use.

```java
@Component
@Order(100)
class ValidateNote implements BeforeSaveCallback<Note> {
    @Override
    public Note onBeforeSave(Note note, DatadamSaveContext context) {
        if (note.text() == null || note.text().isBlank()) {
            throw new IllegalArgumentException("Text is required");
        }
        return note;
    }
}
```

The automatic configuration finds the beans in ApplicationContext. It prefers classes with declared generic type so that Spring filters the entities. Lambdas are accepted, but they may lose this type information in runtime.

A callback must return a compatible entity and not null, and may return another instance. ID cannot change. With active audit, callbacks cannot change the creation of an existing entity or overwrite the fields filled by the handler. If they replace a moving instance, the ID will be applied to the effectively saved instance. Always use the return.

### Reads and other operations

AfterConvert is applied to findById, findAll, findAllById, lookup and queries IDX/FT, annotated or derived. It can enrich the return without rerecording the document. Identity and audit fields are preserved.

Native counts and existence without materialization do not trigger AfterConvert. The CRUD existsById method currently uses findById and therefore triggers the callback if you find an entity. ExistsBy derived uses indexes and does not trigger it.

DatadamCommands commands, persistence(id) and deletions stay out of the save cycle. This item does not add exclusion callbacks or audit to native commands executed directly.

### Exceptions and in-memory effects

BeforeConvert and BeforeSave may refuse the operation with one exception. The library does not write the document in this case. Failures UNIQUE, of ACL or transport also do not execute AfterSave.

Direct changes to the object and other effects executed by the callback itself do not receive rollback. The handler can fill fields of the changeable instance before writing. This does not prove persistence. The ID generated by the library continues to be applied to the changeable instance only after success.

An AfterSave failure is spread as `AfterSaveCallbackException`: the writing has already been confirmed, and getKey() identifies the recorded key. There is no rollback or automatic repetition. Business changes made at this stage affect only the return. Prefer to use it for observation or transitional information.

AfterConvert does not record. Enrichments should prefer fields ignored in JSON in order not to be persisted by a subsequent save. Complex authors should be treated as unchangeable values: protection is not a security mechanism nor a deep snapshot of the entire graph.

The limits of transport, sequential lot and durability document/TTL with UNIQUE remain documented in the previous items. Item 2.1 adds [optimistic location com @Version](optimistic-locking.md), integrated with state detection and callbacks. BeforeConvert/BeforeSave receive the expected version and AfterSave receives the new version. Callbacks cannot change it. Transactions remain outside this scope.

## Manual configuration

The old builders still have no audit and no registered callbacks. To explicitly configure:

```java
var auditing = new DatadamAuditing(
    () -> Optional.of(Instant.now(clock)),
    () -> Optional.of("application"), true, true
);
var template = new DatadamTemplate(commands, mapper,
    Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()),
    EntityCallbacks.create(new ValidateNote()), auditing);
```

A null audit parameter disables only automatic filling. Explicit callbacks continue to work.

## HTTP example and validation

API `/audited-notes` uses records, generated ID, TTL five-minute standard, indexes IDX/FT and reference UNIQUE. The fixed author of the demonstration is `demo-application`. A real application must provide its authenticated identity. AuditedNoteCallbacks removes external spaces and refuses empty business fields. DTO HTTP does not receive audit fields.

```powershell
$noteData = @{ reference=[guid]::NewGuid().ToString('N'); category='demo'; text='  Nota auditada  ' }
$saved = Invoke-RestMethod http://127.0.0.1:8080/audited-notes -Method Post -ContentType application/json -Body ($noteData | ConvertTo-Json)
$saved
$noteData.text = 'Nota atualizada'
Invoke-RestMethod "http://127.0.0.1:8080/audited-notes/$($saved.id)" -Method Put -ContentType application/json -Body ($noteData | ConvertTo-Json)
```

AuditingLifecycleTest validates watch, authors, conversions, configuration, protections and order. AuditingIntegrationIT uses Real Datadam for persistence, TTL, UNIQUE, refusals, index materialization and HTTP.
