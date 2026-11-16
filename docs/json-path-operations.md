# Basic JSONPath reads and writes

The item 3.1 offers native operations JSON.GET, JSON.SET and JSON.DEL, linked to the entity type and its prefix. The application sends the path and value of the change, without loading, changing and forwarding the entire document.

```java
DatadamJsonOperations<Customer> json = template.json(Customer.class);
JsonPath<String> city = JsonPath.of("$.city", String.class);

JsonReadResult<String> result = json.read(id, city);
boolean written = json.set(id, city, "Curitiba");
long removed = json.remove(id, JsonPath.of("$.name", String.class));
```

The ID is converted into a key by the same EntityModel of the repositories. Never pass the complete key instead of the ID. Operations respect the ACL application user and the maintenance of indexes by the server.

## Read types and result shape

The type of JsonPath represents **each selected value**, not the external array of the JSONPath protocol. Use wrappers for scalars that can be null, JsonNode for dynamic structures and TypeReference for parameterized collections:

```java
var tags = JsonPath.of("$.tags", new TypeReference<List<String>>() {});
json.read(id, tags).values(); // [["java", "database"]]: a match, the value of which is a list
json.read(id, JsonPath.of("$.tags[*]", String.class)).values(); // ["java", "database"]: dois matches
json.read(id, JsonPath.of(".tags", new TypeReference<List<String>>() {})).values(); // [["java", "database"]]
```

API normalizes the difference between JSONPath's external array and the unique value of legacy paths, without flattering arrays that belong to the document. The results use an immutable list that accepts null values:

| Situation | keyExists | values |
|---|---|---|
| Key missing | false | empty list |
| JSONPath no matches, with existing key | True. | empty list |
| A null value JSON | True. | list with a null element |
| A scalar value | True. | list with this value |
| Several games JSONPath | True. | one element per match, in order of the server |
| Non-existent Legacy Path | — | server exception. Not converted into absence |

With JsonNode, JSON null is represented by NullNode, according to Jackson. JSON.GET with path `$` also has match envelope. The legacy root `.` directly returns the document. Both saw a list with an element.

Readings accept the selectors that Datadam supports: members, indexes, wildcards, descendants, filters, slices and unions for JSONPath. The legacy subset follows the server. Invalid syntax and WRONGTYPE are spread as InvalidDataAccessResourceUseException. Errors from ACL continue PermissionDeniedDataAccessException. Failed to convert the value to the requested type generates DataRetriivalFailureException.

The copy of the ObjectMapper maintains modules, names and converters of the application and disables common scalar coercions: a numerical string does not turn Integer, a number does not turn String and a float is not truncated to the whole. Custom Jackson converters continue to have their own semantics. The configuration of the shared mapper is not altered.

These are readings of values JSON, without materialization by the repository cycle: they do not perform AfterConvert, even if the requested type coincides with the class of the entity.

## Set and remove

In this stage, the writes accept only singular non-root paths: members and indices, including names between quotes, names with escapes and negative indices.Examples: `$.address.city`, `.tags[0]`, `$['metadata']['display.name']` Wildcards, filters, unions, slices, descendents and roots `$` and `.` are refused prior to transport. Readings continue to be able to use these selectors.

`set` sends a single JSON.SET with serialized value. Returns true when the server confirms OK, including if the value was already equal, and false when the path cannot be created/changed. A Java null is sent as JSON `null`. Does not mean removal. The value is verified against the type declared by JsonPath before sending.

The native rules are preserved:

- An absent end member can be created if the parent object already exists.
- Absent parents, scalar parents and indices outside the array do not create intermediate structures: set returns false.
- A missing/expired key cannot be created by a non-root path: set spreads server error. API does not resuscitate documents.
- `remove` sends a single JSON.DEL and returns the removed number, zero or one to the paths allowed. Path/key missing returns zero. Remove an array index moves the following as on the server.

The JsonPath type validates the value sent or received. It is not a complete validation of the entity's scheme. For example, deliberately declaring an Integer path as String can produce a document that the repository cannot materialize later. Use types consistent with its model. The API does not execute Bean Validation or entity constructor validations in partial changes.

Item 3.2 adds [increments, array operations, and merges](json-native-mutations.md), with the same protections for writing. NX/XX are not part of this API.

## Entity policies

Partial changes to this stage are intended for simple documents. To preserve the contracts already delivered, API refuse set/remove with InvalidDataAccessApiUsageException in the following cases:

- Entity with `@Version`: does not ignore the expected version nor makes separate increase.
- Entity with audit metadata, including embedded, or implementing auditable. Refusal occurs even without auto-enabled completion.
- Type with BeforeConvert, BeforeSave or AfterSave registered for it. Callbacks of other types do not prevent change. Unsolved generic types are treated conservatively. AfterConvert isolated does not prevent, because API does not materialize entities.
- Path that modifies/removes the ID or its descendants, considering the names Jackson, aliases of reading and escapes from the path.
- Path that changes/removes the property `@DatadamTimeToLive` or its descendants/aliases.

Use `repository.save(entity)` for cases that need these resources. Support for partial changes with version/auditing is not included at this stage. It will require an operation that applies all policies in the same writing protocol. There is no option in JsonPath to ignore these protections.

The automatic configuration consults callbacks recorded in the Spring context, including ancestral ones, and considers types related by inheritance. Manual templates built with commands/mapper/generators and without callbacks allow changes in simple models. The builder who receives the opaque EntityCallbacks cannot inspect their records and blocks all partial changes in a conservative way. The new constructor with Class predicate allows declaring types with callbacks. Who configures it should include all applicable callbacks and treat generics unknown as applicable to all. Readings remain available in all these cases.

`DatadamCommands` remains a API direct commands outside the entity cycle. Do not use direct commands as a way to bypass the version, audit or validation required by the application.

## TTL, indexes, and atomicity

Each set/remove is a single native command, executed by the server under the key lock. A read/set sequence, two sets or set/remove **no** is a transaction: another connection can write between the commands. There is no automatic retry or overwritten protection from another change on the same path as a document without a version. Changes in different paths do not forward values from other fields read previously.

These commands preserve the absolute expiration date, including in entities with TTL default or instance: do not restart the deadline and do not need PEXPIRE. To change the stated policy of TTL, use save. `persist(id)` is still the explicit operation to remove expiration.

IDX and FT are updated by the server. Changing an indexed field removes old values and publishes new ones. Removing the field also updates indexes. A refusal UNIQUE generates DuplicateKeyException and preserves document, absolute expiration date and previous owners of indexed values.

The server reads, interprets and regulates the entire document internally for a partial JSON change. The gain of this API is to avoid the traffic and reconstruction of the document in the application. The server's processing cost remains proportional to the size of the document. Transaction or durability guarantees are not added beyond those already offered by the native command. Transport faults may leave the result uncertain.

## HTTP example

CustomerJsonController offers operations on Customer, a document without version/audit/record callbacks:

```powershell
$id = [guid]::NewGuid().ToString()
$customerData = @{ id = $id; name = 'Ana'; email = "$id@example.test"; city = 'SP'; age = 32; tags = @('java', 'database') }
Invoke-RestMethod http://127.0.0.1:8080/customers -Method Post -ContentType application/json -Body ($customerData | ConvertTo-Json)
$url = "http://127.0.0.1:8080/customers/$id/json"
Invoke-RestMethod "$($url)?path=$.tags"
Invoke-RestMethod "$url/city" -Method Put -ContentType application/json -Body (@{ value = 'Curitiba' } | ConvertTo-Json)
Invoke-RestMethod "$url/name" -Method Delete
Invoke-RestMethod "http://127.0.0.1:8080/customers/$id" -Method Delete
```

GET accepts a read path and returns keyExists/values as dynamic JSON. PUT city receives a string value (or explicit null) and returns writetten; DELETE name returns removed. Other business fields are not exposed to writing by this demonstration controller. Invalid paths receive HTTP 400 and conflicts UNIQUE receive HTTP 409.

## Validation

JsonOperationsTest copper envelopes, null, types, errors, aliases and restrictions before transport. JsonPathIntegrationIT uses Datadam real in Docker for JSONPath paths/legates, arrays, absence, incompatible types, TTL absolute, IDX/FT, UNIQUE, ACL, version/audit/callbacks policies, expiration and HTTP.

Run `./scripts/run-local.ps1 -Restart -IntegrationTests -Background` to validate and start the example.
