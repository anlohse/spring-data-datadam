# Derived queries — operators and limits

Methods without `@IdxQuery`/`@FtQuery` are compiled by `PartTree` Spring Data in native queries. Names use Java properties; Jackson resolves the persistent path and the schema provides the native alias. All filters, limits and ordinations are executed in Datadam. The library does not filter pages in memory.

## Operator matrix

| Spring Data family | Property type | IDX | FT | Contract |
|---|---|---|---|---|
| No suffix, `Is`, `Equals` | String, enum, boolean, number | Yeah. | Yeah. | Equality; TAG exact scaling, NUMERIC according to native numerical representation |
| `Between` | Number | Yeah. | Yeah. | Both inclusive limits |
| `GreaterThan`, `GreaterThanEqual`, `LessThan`, `LessThanEqual` | Number | Yeah. | Yeah. | Corresponding exclusive/inclusive limits |
| `True`, `False` | Boolean | Yeah. | Yeah. | Equality TAG Scalar |
| `In` | Climb | No, I don't. | Yeah. | Union of equality; Collection or array not empty |
| `StartingWith`, `StartsWith`, `IsStartingWith` | String | Yeah. | Yeah. | **New in 5.2:** whole field literal prefix |
| `Not`, `IsNot` | Number | No, I don't. | Yeah. | **New in 5.2:** presence NUMERIC and difference in value |
| `NotIn`, `IsNotIn` | Number | No, I don't. | Yeah. | **New in 5.2:** presence NUMERIC and exclusion of the union of values |
| `Not`, `NotIn` | String, enum, boolean | No, I don't. | Deferred | Missing presence test TAG that preserves empty/absent/null cases |
| `IgnoreCase`, `AllIgnoreCase` | String | No, I don't. | Yeah. | TAG without CASESENSITIVE. Parameters ASCII. Also applies to prefixes |
| `And` | Supported Preachers | Yeah. | Yeah. | Intersection. All preachers need to fit in the same index |
| `Or` | Supported Preachers | No, I don't. | Yeah. | Union. Groups AND/OR preserved with parentheses |
| `IsNull`, `IsNotNull` | Any | Deferred | Deferred | Indices do not uniformly represent null, absence and non-indexable values |
| `Containing`, `EndingWith`, `Like`, `NotLike`, regex | String | No, I don't. | No, I don't. | Substring/suffix/standards have no correct equivalent in the current native subset |
| `Before`, `After` | Dates | No, I don't. | No, I don't. | There is no implicit date conversion in NUMERIC. Use an explicit numerical property |
| `Near`, `Within`, searches TEXT/GEO/VECTOR | Any | No, I don't. | No, I don't. | Out of the capabilities implemented by the server |

A non-compatible method fails to create the repository. Invalid parameters or incompatible dynamic order fail before sending the query. Derived projections, derivative deletion and Limit/scrolling parameters remain rejected. Fixed projections are available in [annotated and programmatic queries API](projections.md).

## Literal prefixes

```java
Page<Customer> findByEmailStartingWithOrderByEmailAsc(String prefix, Pageable pageable);
List<Customer> findByCityStartingWithIgnoreCaseOrderByAgeAsc(String prefix);
Page<Customer> findByCityStartingWithAndAgeNotOrderByAgeAsc(
    String prefix, int excludedAge, Pageable pageable);
```

IDX uses the band TAG `>= prefix` and `< sucessor(prefix)`, with separate arguments RESP. The successor increases the last unbelievable Unicode point and removes the maximum suffix. The order bytes UTF-8 preserves the order of Unicode points. The band ignores the surrogate region. A prefix composed only by U+10FFFF does not need a upper limit. Strings with isolated surrogates are refused.

FT uses `@alias:{$p0*}` with the value separated in `PARAMS`. Only the last `*`, written by the compiler, is operator. Asterisks, bars, keys and pipes received in the parameter remain literal, without interpolation or escape built by the caller.

Rules:

- Invalid or empty prefix is refused. `StartingWith("")` does not mean all. Absent fields, null and empty do not correspond to a non-empty prefix.
- The standard version differs capitals. FT requires `caseSensitive=true`; `IgnoreCase` requires TAG without CASESENSITIVE and values ASCII, according to the server folder.
- The TAG must represent the entire field, without separator. TAGs divided into tokens do not implement `String.startsWith` of the original value.
- IDX HASH without separator supports whole field prefixes, including literal commas. FT HASH divides TAG per comma by default. These fields are refused for scalar derivation, including prefixes and IgnoreCase.
- The FT parser removes initial spaces/tabs from a TAG. Prefixes that begin with these characters are refused when the chosen plane is FT; IDX preserves them. There is no automatic failure after the plan selection. Final spaces of the prefix are preserved.
- The ordination IDX follows the tuple of the index. A prefix does not fix the field by equality: in an index `(city, age)`, ordering only by age after a city prefix requires another plane, such as FT with numerical ordination. FT accepts an ordination field.

## Numeric negation and missing fields

```java
Page<Customer> findByAgeNot(int excludedAge, Pageable pageable);
Page<Customer> findByAgeNotIn(Collection<Integer> excludedAges, Pageable pageable);
```

`Not(25)` compile for `(@age:[-inf +inf] -@age:[$p0 $p0])`. `NotIn([20,30])` compile for `(@age:[-inf +inf] -(@age:[$p0 $p0] | @age:[$p1 $p1]))`.

The presence range is necessary: the isolated FT denial may also include documents without the field. The contract delivered includes only documents with an indexable value NUMERIC different from the excluded values. Absence, JSON null and invalid values do not pass. This rule is applied before pagination and counting, in JSON and HASH.

Null values, null members, empty collection/array and NaN/infinite numbers are refused. `NotIn` accepts Collection and arrays, including primitives. Comparison follows the numerical representation of the Datadam index. Does not add decimal or int64 accuracy to the native representation.

Denial of TAG remains delayed. An equality supplement does not offer the same contract of presence for null, absence and empty String. The library rejects these methods in initialization, without performing complementary queries or filtering results in memory.

## Results, totals, and schema

List, Page, Slice, entity, Optional, count, exist and Top/First keep existing contracts. Count/exists do not materialize entities; Slice searches for an additional item; Top/First uses native limit. Top/First does not match Pageable/count/exists. Unique result does not accept Pageable.

Page IDX uses QUERY and COUNT separately: under competition, content and total can reflect different moments. FT returns content and total in the same command. All fields of a compound IDX need to be required by the predicates in order not to lose documents with missing fields. No derived query creates or migrates indexes.

## Demo application and validation

Repositories `CustomerRepository` and `HashCustomerRepository` display prefixes by email and age denial. `CustomerRepository` also show FT prefixes, IgnoreCase and combination with denial and OR. Endpoints accept Spring pagination:

```powershell
Invoke-RestMethod 'http://localhost:8080/customer-queries/json/prefix?prefix=ana&page=0&size=10'
Invoke-RestMethod 'http://localhost:8080/customer-queries/hash/prefix?prefix=ana&page=0&size=10'
Invoke-RestMethod 'http://localhost:8080/customer-queries/json/age-not-in?excluded=20,30&page=0&size=10'
Invoke-RestMethod 'http://localhost:8080/customer-queries/hash/age-not-in?excluded=20,30&page=0&size=10'
```

`DerivedOperatorsTest` checks plans, parameters, aliases, Unicode limits and refusals before I/O. `DerivedOperatorsIntegrationIT` executes the operators against Datadam real in Docker, covering JSON/HASH, literal characters, combinations, absences, sorting, totals, TTL and HTTP. Execute `scripts/run-local.ps1 -Restart -IntegrationTests -Background`.

This delivery uses existing IDX/FT operators on the server. It does not add a C++ extension. The dependencies of HREPLACE/RETURNJSON of the previous steps are described in [Patches server](../server-patches/README.md).
