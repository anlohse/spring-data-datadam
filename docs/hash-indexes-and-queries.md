# HASH indexes and queries

The item 4.2 adds schema and queries IDX/FT for entities with `storage = DatadamStorage.HASH`. The same repository APIs return complete entities from the native fields of the response, without searching each document again for HGETALL.

```java
@DatadamDocument(prefix = "customer:hash:", storage = DatadamStorage.HASH)
@DatadamIndex(name = "customers:city-age", fields = {
    @IndexField(path = "city", alias = "city"),
    @IndexField(path = "age", alias = "age", type = IndexField.Type.NUMERIC)})
@DatadamIndex(name = "customers:search", kind = DatadamIndex.Kind.SEARCH, fields = {
    @IndexField(path = "city", alias = "home"),
    @IndexField(path = "age", alias = "years", type = IndexField.Type.NUMERIC),
    @IndexField(path = "labels", alias = "tag", separator = ";", caseSensitive = true)})
public record Customer(@Id String id, String city, Integer age, String labels) {}
```

## Fields, aliases, and schema

For HASH, path is **literal name of the stored field**, not JSONPath. A Java property renamed with `@JsonProperty("home_city")` must be declared with `path = "home_city"`. Ringed objects/collections of [mapping HASH](hash-entities.md) are like JSON within a field: `address.city` does not automatically extract the city member from an address object.

IDX HASH does not accept AS: alias must be equal to path. This limitation is validated before DDL. FT HASH accepts AS, and aliases may differ from the names stored. In the example, the FT search uses `@home` and the native ordination uses `years`. The returned entity continues to have city/age.

`ensureIndexes(Entity.class, timeout)` issues ON HASH or ON JSON according to the model. It continues to use administrative connection, idempotent creation, waits for backfill and explicit validation. An existing index with another format, prefix, order/fields, UNIQUE alias, box mode or separator generates DataIntegrityViolationException. No index is removed or automatically recreated. Information from IDX.INFO and FT.INFO are combined to also validate the actual identifier of a FT alias.

separator is a new IndexField option, available for TAG in HASH and JSON. Empty string uses the server standard. An explicit value must be a single character ASCII different from NUL. NUMERIC with separator, multi-byte character and CASESENSITIVE outside TAG FT are refused.

## Differences in indexed values

| Appearance | IDX HASH | FT HASH | JSON |
|---|---|---|---|
| TAG standard | Whole field, no trim, sensitive to the box | Tokens separated by comma, with trim and removal of voids/duplicates. Box ASCII ignored | Values selected by path. No standard separator |
| TAG with explicit separator | Separate tokens, with trim and removal of voids/duplicates | Same procedure, with the chosen separator | Divide the selected string values |
| CASESENSITIVE | Always difference box | Optional in TAG | IDX always difference; FT uses the option |
| List stored | The JSON of the list is text of a field HASH | Same text. Does not automatically turn one token per element | Arrays can produce a value indexed by element |
| Object stored | JSON object is field text | JSON object is field text | Selected objects are not TAG/NUMERIC indexable |
| NUMERIC | Text of the field interpreted as number | Text of the field interpreted as number | Numerical values selected by path |
| Field absent/NUMERIC invalid | Absence of any component excludes the IDX compound key | The key can continue in the index/presence, without the value of this field | Same rule of presence of IDX/FT applied to values JSON |

Example: city `Alpha,Beta` is a single value in IDX without separator, but two tokens in the standard FT HASH. A FT filter `@home:{Alpha}` can find this key. This does not amount to city equal to `Alpha`.

For labels, the example uses a String field separated by `;`. The tag property is a List stored as JSON by the codec HASH. Indexing that field does not make it a native TAG list. Choose a dedicated text representation when you want the server tokens HASH. Do not concatenate lists silently in the codec, as this would lose conversion types and ambiguities.

Invalid numeric values have not been zeroed or corrected by the library. An externally written key with invalid age may appear in the FT presence, but fail in the materialization as Integer if the query selects this complete document. Numerical queries do not include this value; LIMIT 0 allows to obtain totals without materialization. Numerical accuracy/order follow the native index of Datadam, without alternative numerical evaluation in the client.

## Declared and programmatic queries

```java
public interface CustomerRepository extends DatadamRepository<Customer, String> {
    @IdxQuery(index = "customers:city-age", value = "city = ?0 age >= ?1 age < ?2")
    Page<Customer> byCityAndAge(String city, int minimum, int maximum, Pageable page);

    @FtQuery(index = "customers:search", value = "@home:{?0} @years:[?1 ?2]")
    Page<Customer> searchCityAndAge(String city, int minimum, int maximum, Pageable page);
}

repository.query("customers:city-age",
    CompoundQuery.all().where("city", "=", "SP").where("age", ">=", 18).desc(),
    PageRequest.of(0, 20));

repository.search("customers:search",
    new SearchQuery("@tag:{$t}", Map.of("t", "Java"), Sort.by("years")),
    PageRequest.of(0, 20));
```

Conditions IDX use the native names of the fields/aliases; FT uses aliases of the schema. The sorting IDX follows the sequence of the index fields, with DESC in CompoundQuery; FT accepts the SORTBY native supported, with a field. Sort in declared/programmatic queries FT also uses schema names, such as years. Parameters remain linked arguments. Filters, tracks, counts and sorting are executed on the server.

The range of @IdxQuery above excludes maximum use `<`. The two ends of `Between` derivative are inclusive, as well as `[min max]` FT. These contracts are equal to those used by the example JSON.

## Query derivation

In delivery 5.2, `StartingWith` adds whole field prefixes by IDX without separator, including literal commas. `Not`/`NotIn` numerics use FT with explicit presence and exclude absent/null/invalid. Scalar prefixes in TAG FT HASH continue to be refused because of standard division in tokens. The [derivation matrix](derived-queries.md) semantic document, limitations and examples in `/customer-queries/hash`.

The Java properties are solved by Jackson for the root HASH field. The planner compares this field with the paths of the schema and uses the corresponding native alias. Operators and already supported returns remain available when there is a semantically compatible plan: equality, numeric tracks, count/exists, Optional/List/Page/Slice and Top/First in the subset documented by the library.

HashCustomerRepository demonstrates equality/email lookup UNIQUE, city equality with age range/order by IDX, count, existences, Slice/Top and only numerical query by FT. Derived ordination uses the Java age property and converts it to the alias FT years when that is the chosen plan.

In order not to change the meaning of equality, the planner **does not use TAGs with separation** for predicate equality scalars/IN/IgnoreCase or for ordination of this field scalar. This includes the FT HASH with standard comma, even if CASESENSITIVE is activated. It can use a IDX without separator that covers the predicate, or refuse the method in creating the repository if there is no correct plan. The same rule applies to a TAG JSON with explicit separator. There is no post-filtration in memory or scanning to simulate absent semantics.

Therefore, `findByCityAndAgeBetweenOrderByAgeDesc` in the example uses IDX and maintains equality of the whole field, while the declared FT query by city explicitly uses semantics of token. Numerical queries by FT remain eligible, even if other fields of the index are divided TAGs. Methods on nested properties of an object within a field HASH are refused. These members are not independent fields HASH.

## Materialization, permissions, and lifecycle

IDX.QUERY/IDX.GET and FT.SEARCH return all fields HASH by default. The template uses HashEntityCodec and executes AfterConvert in the same way as foundById. Lists, Maps, Jackson names and optional fields are reconstructed by the declared type. The answer is validated against the model prefix. These full entity APIs do not fill entities with fields omitted by RETURN. Item 5.1 adds [native projections](projections.md) with separate DTOs/interfaces and explicit selection, without entity callbacks.

There is no additional HGETALL per result. The ACL test uses a reader with IDX/FT and access only to keys `visible*`, without permission of HGETALL: content and total reflect only readable keys. native count/exists do not materialize entities or execute AfterConvert. Index creation continues to require administrative credentials. Application remains without DDL.

Changes HASH per save/HREPLACE update IDX/FT and release old or removed values. Objections UNIQUE preserve document, TTL and index owners. Expired keys no longer appear in both mechanisms. TTL, version, audit and callbacks maintain CRUD contracts 4.1. Queries do not change these data.

This step does not add new C++ commands to the HREPLACE patch: it uses the HASH support of IDX/FT that Datadam already has. The server requirement of CRUD HASH continues to be documented in server-patches/README.md.

## HTTP example corresponding to Customer

SchemeApplication also prepares HashCustomer accordingly. The routes `/hash-customers`, `/hash-customers/derived`, `/hash-customers/search` and `/hash-customers/labels` cover IDX, derivation and FT; GET/POST/DELETE per ID complete the flow CRUD.

```powershell
$id = [guid]::NewGuid().ToString()
$data = @{ id = $id; name = 'Ana'; email = "$id@example.test"; city = 'SP'; age = 25; labels = 'Java; Spring'; tags = @('java', 'database'); details = @{ active = $true } }
Invoke-RestMethod http://127.0.0.1:8080/hash-customers -Method Post -ContentType application/json -Body ($data | ConvertTo-Json -Depth 5)
Invoke-RestMethod 'http://127.0.0.1:8080/hash-customers?city=SP&minimum=18&maximum=40'
Invoke-RestMethod 'http://127.0.0.1:8080/hash-customers/derived?city=SP&minimum=18&maximum=40'
Invoke-RestMethod 'http://127.0.0.1:8080/hash-customers/search?city=sp&minimum=18&maximum=40'
Invoke-RestMethod 'http://127.0.0.1:8080/hash-customers/labels?label=Java'
Invoke-RestMethod "http://127.0.0.1:8080/hash-customers/$id" -Method Delete
```

## Validation

HashIndexContractTest covers generation/validation of DDL, separators, aliases, schema differences and materialization without extra readings. DerivedQueryValidationTest adds Jackson names HASH, split TAG refusal and nesting properties refusal. HashQueriesIntegrationIT uses Real Datadam in the Docker for identical schema, track/order/page/totals, declared/derivative/programming queries, aliases, TAG/NUMERIC absent/invalid values, field update/remove, UNIQUE, TTL, ACL, callbacks and HTTP.

Execute `./scripts/run-local.ps1 -Restart -IntegrationTests -Background`.
