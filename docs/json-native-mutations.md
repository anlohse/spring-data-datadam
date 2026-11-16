# JSON increments, arrays, and merge

The item 3.2 expands `template.json(Entity.class)` with native commands to modify values within the document. Each call sends a command to Datadam. Does not search for and forwards the document through the application.

```java
var json = template.json(Customer.class);
var age = JsonPath.of("$.age", Integer.class);
var tags = JsonPath.of("$.tags", new TypeReference<List<String>>() {});

List<Number> ages = json.increment(id, age, 1);
json.multiply(id, age, 2);
List<Long> lengths = json.arrayAppend(id, tags, List.of("datadam"));
json.arrayInsert(id, tags, 0, List.of("spring"));
List<JsonPopResult<String>> popped = json.arrayPop(id, tags); // last element
json.arrayTrim(id, tags, 0, 2); // hold positions 0 to 2, including
```

[protections and path forms of the item 3.1](json-path-operations.md). Writers accept only non-root singular members/indexes and refuse entities with version, audit or callbacks of recording, as well as changes in ID and policy of TTL. Use save for these entity cycles. ArrayLength/arrayIndex queries are read only and can use multiple selectors.

## Return values and rules

The methods normalize JSONPath and legacy paths in immutable lists. Written have zero or a result because of restriction to singular paths. Queries may have several. A JSONPath without matches returns empty list. A nil result per incompatible type is preserved as null element, without using List.of or Optional, which would lose that distinction.

| Method | Command | Return |
|---|---|---|
| increment(id, path, amount) | JSON.NUMINCRBY | Number list: values after increment |
| multiply(id, path, factor) | JSON.NUMMULTBY | Number list: values after multiplication |
| arrayAppend(id, path, values) | JSON.ARRAPPEND | Long list: final lengths |
| arrayInsert(id, path, index, values) | JSON.ARRINSERT | Long list: final lengths |
| arrayPop(id, path[, index]) | JSON.ARRPOP | JsonPopResult list: result removed per match |
| arrayTrim(id, path, start, stop) | JSON.ARRTRIM | Long list: final lengths |
| arrayLength(id, path) | JSON.ARRLEN | Long list: current lengths |
| arrayIndex(id, path, value[, start, stop]) | JSON.ARRINDEX | Long list: found positions; -1 absent |
| go(id, path, patch) | JSON.MERGE | Boolean true when server responds OK. It is not change count |

### Numbers

The operands must be finite JSON numbers. NaN, Infinity, and settings that serialize numbers as strings are rejected before writing. Datadam keeps integer addition in int64 while the result fits. Overflow promotes the result to floating point. A non-finite result is rejected without a write.

Therefore, the return is List of Number, regardless of the numerical type declared on the way. A Long JsonPath can return Double after promotion, without truncation or attempt to deserialize back to Long. The exact whole int64 is preserved in the answer. Promoted numbers follow the floating point accuracy of the server. The Jackson mapper determines the concrete Java subclasses (e.g. Integer, Long or Double).

With JSONPath, a non-numerical value returns `[null]`. No matches, `[]`. With legacy path, incompatible type and absence of path are errors. An absent/expired key is error, without implicit creation. The library spreads server errors and does not repeat increments automatically.

### Arrays

Use JsonPath of List with TypeReference to inform the type of items. Append/insert require at least one value and send each item serialized as a separate JSON argument. The null value can be inserted using a list that accepts it, such as Arrays.asList. Items are validated against the declared type before sending.

Negative indices count from the end. Insert accepts index equal to the length to add at the end. An index outside the limits is error and does not modify the document. Pop uses -1 by default. An index outside the ends is adjusted by the server at the nearest end. Trim keeps start/stop **inclusive**. Empty intervals empty the array. ArrayIndex searches start/stop with **Exclusive stop** and zero as the end of the array. The patterns are 0/0.

Pop distinguishes JSON content from RESP nile:

- `JsonPopResult(true, null)`: an element JSON null has been removed.
- `JsonPopResult(true, value)`: the element has been removed, even if it is valued as another list/object.
- `JsonPopResult(false, null)`: the match was not an array or the array was empty.
- List with no results: JSONPath did not select anything.

Append/insert/trim/length/index return a null element for incompatible JSONPath matches. Legacy paths keep server type/absence errors except pop, whose empty or non-array array nill is preserved as popped=false. Absent key ArrayLength returns empty list with JSONPath and list containing null with legacy path. Other array commands require existing key.

### Object merge

```java
var metadata = JsonPath.of("$.details", new TypeReference<Map<String, Object>>() {});
json.set(id, metadata, Map.of("keep", true, "obsolete", "old"));
Map<String, Object> patch = new HashMap<>();
patch.put("obsolete", null);
patch.put("added", 42);
json.merge(id, metadata, patch);
// details = {"keep": true, "added": 42}
```

Merge receives an object patch Map and applies the rules RFC 7386: objects are combined recursively, a null member is removed, arrays are replaced as values and not concatenated. A scalar target can be replaced by object. Null patch values, including nested maps, are sent even when the mapper uses inclusion NON_NULL. Scalars and scalars in the root of the patch are not part of this API.

The path needs to exist to make the combination. JSONPath without matches responds OK without changing anything, so merge returns true even in this case. Do not promise that there was a change. A legacy path does not exist generates error. The root of the document is refused to preserve identity and TTL, and a missing/expired key is not created.

## Atomicity, indexes, and failures

Each isolated command is applied under the key lock on the server. Competing increments do not make the read/modify/write on the client, so do not lose increments. Competing appends preserve the added elements and merge into distinct members preserve the other members. Order and intermediate results depend on the actual order of the executions.

A sequence of commands is not a transaction. If an append succeeds and the next increment fails by UNIQUE, the append remains. Two changes in the same member of a document without version follow the execution order, without version control. There is no guarantee that a query made then only observes the change of the connection itself.

All changes preserve the absolute expiration date, without reapplying TTL or requiring PEXPIRE. IDX/FT are maintained by the server. UNIQUE may refuse numbers, values added to arrays or members modified by merge. Pop/trim may also cause conflict when there is an index on an array position: when removing the first element, the second element becomes the indexed position. Refusal generates DuplicateKeyException and preserves document, TTL and indexes.

Syntax/type errors, invalid index, unfinished numerical result and ACL maintain the native exceptions translated by DatadamCommands. A transport failure may still leave the result uncertain. There is no automatic retry.

There is also the conversion of **result of a mutation already sent**. If, for example, the client declares a string array and pops a number, the element may have been removed before the conversion fails. The library generates JsonMutationDecodingException with getKey()/getCommand(), distinguishing this case from a command refusal. Reread the state to decide recovery. Repeat pop would remove another element.

Datadam still interprets and regulates the entire document internally. Modifying a single element avoids transfer/reconstruction in the application, but does not make the internal cost independent of the size of the document. These APIs do not add transactions, sequence rollbacks or new guarantees of durability after crash.

## HTTP example

CustomerJsonController exposes age increase, append/insert/pop/trim tags and an additional object JSON details. This member is accessed by operations JSON, without extending the DTO Customer returned by the repository.

```powershell
# Use the ID of an already created Customer.
$url = "http://127.0.0.1:8080/customers/$id/json"
Invoke-RestMethod "$url/age/increment" -Method Post -ContentType application/json -Body '{"amount":1}'
Invoke-RestMethod "$url/tags/append" -Method Post -ContentType application/json -Body '{"values":["datadam"]}'
Invoke-RestMethod "$url/tags/insert?index=0" -Method Post -ContentType application/json -Body '{"values":["spring"]}'
Invoke-RestMethod "$url/tags/pop" -Method Delete
Invoke-RestMethod "$url/tags/trim?start=0&stop=1" -Method Put
Invoke-RestMethod "$url/details" -Method Put -ContentType application/json -Body '{"keep":true,"obsolete":"old"}'
Invoke-RestMethod "$url/details" -Method Patch -ContentType application/json -Body '{"obsolete":null,"added":42}'
Invoke-RestMethod "$($url)?path=$.details"
```

The DTO age uses Integer and the Customer model uses int. The API number follows the server and does not validate the full schema. Avoid taking the age beyond the field interval, as the repository would stop materializing it properly. For large counters, choose Long/Number in your own model.

The Docker app user explicitly receives new commands. The ttl-restricted user remains without these permissions for the ACL tests. No index administration is required for the application.

## Validation

JsonMutationTest covers serialization, envelopes, types, nulls, numeric promotion, guards, merges under NON_NULL, and decoding failure after a mutation. JsonMutationIntegrationIT runs against Datadam and covers concurrent increments, appends, and merges from two independent connections, UNIQUE on numbers, arrays, positions, and merge results, absolute TTL, IDX/FT, expiration, ACLs, and HTTP. Test keys use the demo:mutation: prefix and are deleted individually. Test indexes are created explicitly.

Execute `./scripts/run-local.ps1 -Restart -IntegrationTests -Background`.
