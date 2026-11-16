# Entity TTL

The item 1.1 adds declarative expiration to entities JSON. Datadam keeps the indexes updated when the document receives a deadline, loses expiration or expires.

## Type defaults and entity overrides

```java
@DatadamDocument(prefix = "note:", timeToLive = 60)
public record Note(
    @Id String id,
    String text,
    @DatadamTimeToLive(unit = TimeUnit.MILLISECONDS) Long ttlMillis
) {}
```

The type above defaults to 60 seconds. Use `ttlMillis = 1500L` to override it with 1.5 seconds, or `ttlMillis = null` to use the default. Set `timeToLiveUnit` to change the unit of the default TTL:

```java
@DatadamDocument(prefix = "session:", timeToLive = 30, timeToLiveUnit = TimeUnit.MINUTES)
public record Session(@Id String id, String userId) {}
```

The field `@DatadamTimeToLive` accepts byte, short, int and long, its wrappers and `Duration`. For Duration, the duration already informs the unit:

```java
@DatadamDocument(prefix = "draft:")
public record Draft(@Id String id, @DatadamTimeToLive Duration lifetime) {}
```

There is at most one field of TTL per entity, including by inheritance. It cannot be static or share the field `@Id`. Mutable classes and records are accepted. Fields with wrappers or Duration can be null. Primitives need to be filled with a valid value before saving.

## Create and update rules

| Situation | Effect of `save` |
|---|---|
| Field TTL with positive value | Apply this deadline again, counted from the recording on the server |
| Field null or non-existent, with positive pattern in type | Apply the pattern again |
| Invalid or non-existent field, no pattern in type | Maintains existing expiration. A new key runs out of expiration |
| `timeToLive = -1` in type | Disable pattern. Is the standard value of annotation |
| Field TTL zero or negative | Refuse before sending any command |
| Standard zero or less than -1 | Refuses mapping when creating repository |
| Accuracy less than one millisecond or overflow | Refuse to truncate or saturate the value |

TTL positive is a policy of renewal by recording. A successful `save` restarts the deadline, even when the other fields do not change. Reading an entity or executing a search does not renew the deadline.

The resolution is one millisecond, and the accepted interval is 1 to `Long.MAX_VALUE / 2` milliseconds. The limit leaves room for the server to add time to its timepiece without overflow in normal use. Conversions between units are exact: 2000 microseconds are accepted as 2 ms; 2001 microseconds are refused.

The annotated field remains a normal JSON field, subject to the rules of the ObjectMapper. When serialized, it keeps the policy requested, not a countdown. Reading `ttlMillis = 1500` does not mean that there are still 1500 ms left. Saving again with this value renews the deadline to 1500 ms.

## Remove expiration

```java
boolean removed = repository.persist(id);
// Or:
boolean removedByTemplate = template.persist(id, new EntityModel<>(Note.class));
```

`persist` executes only PERSIST, preserving content and indices. Returns true when it removes a term, false if the key does not exist or is already without expiration. It does not make a reading followed by a rerecording.

The name means to make the key persistent in relation to expiration. It does not mean to force an fsync. The next `save` with a positive policy, explicit or standard, will apply the expiration again. To preserve the key without term in future writes, use an entity without policy of TTL.

Zero and negative in the field are not shortcuts to delete the key or remove the expiration. Exclusion continues to be made by `deleteById` and expiration removal by `persist`.

## Writes, UNIQUE, and permissions

Without a policy of TTL, `save` maintains the previous path: a JSON.SET, which preserves the existing deadline.

With a positive policy, the library sends a EVAL with fixed script and key/document/time in separate arguments. The script checks PEXPIRE permission without changing the key, runs JSON.SET and, if the recording succeeds, applies PEXPIRE.

The verification uses a deliberately non-numerical argument of PEXPIRE. In Datadam, ACL is verified before parse of value. The expected invalid number error proves that the permission verification passed without modifying the key. An unexpected refusal or response closes the script before JSON.SET. This behavior was validated on the local server and is an explicit dependence on implementation.

A UNIQUE refusal occurs in JSON.SET and closes the script before changing the expiration. Tests compare the absolute expiration date, not just an approximate TTL, to check that a refused update did not renew or shorten the deadline. A refused insertion does not create the document.

In addition to the normal recording permissions, the user needs `+eval +pexpire` and access to the key. `persist(id)` needs `+persist`. Inspecting the deadline requires `+pttl` or `+pexpiretime`. The save does not need these reading permissions. It is not necessary to grant index administration or the entire scripting category.

### Atomicity and durability limits

Other customers do not observe the document between JSON.SET and PEXPIRE, because the script has isolated execution. TTL invalid is refused before EVAL. Insufficient permissions and UNIQUE are treated before partial alteration in documented and tested cases.

This does not create script rollbacks. An unexpected failure after writing can keep it, according to the server's contract. Datadam documentation and implementation also indicate that an indexed UNIQUE writing can force a commit before the end of the block. Therefore, this API **does not guarantee that document and new TTL are always a single durable recording in case of crash** between these commands when there is UNIQUE. This guarantee would require an additional native operation or an explicit change in the server outside the item 1.1.

A transmission failure may also leave the client without knowing if the operation is over. The library does not automatically repeat the recording. Applications that need joint recovery after crash should consider this limit.

## Runnable example

`TemporaryNote`, `TemporaryNoteRepository` and API `/temporary-notes` implement the example: 60-second standard and optional replacement in milliseconds, with indices IDX, FT and UNIQUE.

```powershell
$note = @{
    id = 'note-' + [guid]::NewGuid().ToString('N')
    reference = [guid]::NewGuid().ToString('N')
    category = 'demo'
    text = 'Expira em dez segundos'
    ttlMillis = 10000
}
Invoke-RestMethod http://127.0.0.1:8080/temporary-notes -Method Post -ContentType application/json -Body ($note | ConvertTo-Json)
Invoke-RestMethod "http://127.0.0.1:8080/temporary-notes/$($note.id)/ttl"
Invoke-RestMethod 'http://127.0.0.1:8080/temporary-notes?category=demo'

# Optional: Remove the deadline before expiration.
Invoke-RestMethod "http://127.0.0.1:8080/temporary-notes/$($note.id)/persist" -Method Post
```

`GET /temporary-notes/{id}/ttl` returns `remainingMilliseconds`: positive value for active term, -1 for non-expiration key and -2 for missing key. After expiring, `GET /temporary-notes/{id}` returns 404 and the key ceases to appear in the indexes.

Invalid updates return HTTP 400. Reference UNIQUE from another note returns 409. The application user continues without permission to create indexes. `SchemaApplication` prepares the scheme with the migration credentials.

## Validation

Execute `scripts/run-local.ps1 -Restart -IntegrationTests -Background` to recompile, validate and replace the instance initiated by the script. `EntityTtlTest` copper metadata and conversions; `EntityTtlIntegrationIT` copper actual expiration, indices, renewal, preservation, Duration, persistence, UNIQUE, ACL, observation by another connection and HTTP.

The native contract was awarded in the local documents `docs/json.md`, `docs/commands.md`, `docs/security.md`, `docs/scripting.md` and `docs/transactions.md` of the C++ Datadam project.
