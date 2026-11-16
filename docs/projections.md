# Native IDX/FT projections

Item 5.1 selects only the properties requested in the native query and materializes another type of return. It does not load complete entities to then reduce them in the client and does not execute JSON.GET/HGETALL per result.

## Supported projection types

- DTOs in records, by the canonical constructor.
- Closed interfaces with JavaBean getters without parameters, including inherited getters.
- Consultations annotated with @IdxQuery/@FtQuery returning List or Page.
- Programmatic queries by ProjectionSpec, in the repositories and in the template.
- Serializable properties of the entity's root, including Jackson names and scalar types/collections/complete objects stored on those properties.

Designed properties must use nullifiable types, for example Integer instead of int. Primitives, Object and Optional at the root of DTO/getter are refused before consulting. This prevents the field from being silently zero/false. The type must be compatible with the origin property. Containers need the same declared parameterized type. Jackson global modules/converters are respected, but the projection does not reproduce the entire context of deserialization annotations of each entity property. Customized contextual conversions need format compatible with the requested type.

Interfaces with SpEL (@Value directly or by meta-annotation), default methods, sets or other methods other than getters are refused. Movable class DTOs, dynamic projections via Class, projections nested by member paths and derivation of names with projected return stay out of this first set. Selecting a root property that contains a list/Map/object returns the full content of that value. It is not selection of its internal members.

## Annotated queries

```java
public record CustomerSummary(String name, @ProjectionProperty("city") String town, Integer age) {}

public interface CustomerView {
    String getName();
    String getCity();
    Integer getAge();
}

public interface CustomerRepository extends DatadamRepository<Customer, String> {
    @IdxQuery(index = "demo:customers:city-age", value = "city = ?0", descending = true)
    Page<CustomerSummary> summariesByCity(String city, Pageable page);

    @FtQuery(index = "demo:customers:search", value = "@city:{?0}")
    Page<CustomerView> viewsByCity(String city, Pageable page);
}
```

The generic type of return defines the projection: if it is the entity, it maintains the full load. If it is another type, the definition is validated and the native selection is created. A DTO that is @DatadamDocument or the entity's own class is refused as ProjectionSpec's target. There is no incomplete entity presented as fully materialized.

The name of each component/getter is, by default, the Java name of the original property. ProjectionProperty allows another mapping, such as town → city. Do not report native aliases or JSONPath in this annotation. It points to the Java property. The plan then resolves the Jackson name and the native identifier/alias.

List and Page use the same native order and limit. Page IDX runs IDX.COUNT and IDX.QUERY; List IDX uses only IDX.QUERY. FT returns content and total on the same call. Without Pageable, annotated queries use the existing standard limit of 100; IDX allows Pageable.unpaged for unlimited selection, while FT requires limited page.

## Programmatic queries

```java
ProjectionSpec<CustomerSummary> summary = ProjectionSpec.of(CustomerSummary.class);
Page<CustomerSummary> page = repository.projectQuery(
    "demo:customers:city-age", CompoundQuery.all().where("city", "=", "SP").desc(),
    PageRequest.of(0, 20), summary);

public record NameAndTags(String display, List<String> tags) {}
var fields = ProjectionSpec.of(NameAndTags.class).field("display", "name");
Page<NameAndTags> result = repository.projectSearch(
    "demo:customers:search", new SearchQuery("@city:{$c}", Map.of("c", "SP"), Sort.by("age")),
    PageRequest.of(0, 20), fields);
```

ProjectionSpec is immutable. field receives name of target property and Java name of origin. Explicit mapping prevails over ProjectionProperty. Unknown targets, non-serialized properties, nested sources, incompatible types and repeated bindings are refused. When two fields of DTO use the same origin, it is requested only once and the value is made available for both components.

The template exposes projectQuery/projectSearch with EntityModel and ProjectionSpec. projectQueryContent and projectSearchResults offer the form of content/total used by the annotated methods. Sorting IDX follows the index and CompoundQuery.desc; FT uses the native names of the scheme in Sort, as years in the example HASH. Conflicting spells cannot be combined or unlimited FT requests.

## JSON, HASH, and aliases

In JSON, the selection uses root limb literal paths, solved by Jackson, and **RETURNJSON**. The response of each field contains JSON typed: the string `"null"` continues a string and the value JSON null continues null. Arrays and objects maintain its structure and int64 is not converted to Moon number. The precision of numbers JSON follows the value that the server stores/returns.

In HASH, the selection uses RETURN and the declared types of mapping HASH: native strings remain strings. Numbers, dates, bytes and JSON of containers are converted by codec. BigDecimal and int64 maintain their accuracy, including the decimal scale. Dynamic values within Map follow the Jackson configuration of the application. The internal passage required to rebuild the field does not change that configuration.

FT aliases and Jackson names are treated separately. For example, city can be stored in home_city and indexed as home. Projection can still declare `@ProjectionProperty("city") String town`. The right field is requested and renamed in DTO, without an extra query. An index alias that hides another origin and prevents unequivocal selection is refused.

Absent field and JSON null are represented as null in the null component/getter. The selection does not rule out the line or invent defaults. DTO builders who refuse these values cause DataRetrievalFailureException. The library does not omit this line to produce an apparently valid page. Native values incompatible with the type designed also explicitly fail, without common string coercion for number or float truncation for whole.

## Lifecycle and limits

Projections are reading models: they do not go through BeforeConvert/BeforeSave/AfterSave/AfterConvert entity, do not complete audit, do not increase version and do not change TTL. The record canonical constructor is executed to create DTO. Returned lists/maps are local values, not a managed entity session or a writing operation.

Full entity queries continue to use the previous path and run AfterConvert. API rejects entity targets to avoid saving a partial version as if it were complete. The projection does not automatically bring ID, version, email or other unsolicited fields.

Filters, order, limits and counts are executed by the server. Key reading permissions filter content and total, as in normal queries. The example projection-reader account does not have JSON.GET or HGETALL and can consult the selected fields of the visible logs. This does not constitute ACL per field: an account with access to the index/key can execute other queries allowed by the server.

No transactions are added between IDX.COUNT and IDX.QUERY: under concurrent changes, they maintain the existing consistency/page limits. There is no automatic repetition, page filtering in the client or failure to load the entire document. The server still needs to read/interpret the document JSON to extract the fields. The selection reduces traffic and materialization in the application, without promising internal cost proportional only to the returned fields. Projections derived by name, joints, computerized expressions, multivalued paths and JSONPath arbitrary selectors are not part of this delivery.

## RETURNJSON server dependency

RETURN existing returns strings JSON without quotes, making indistinguishable a literal string `null` and a value JSON null. RETURNJSON was added to Datadam C++ to resolve this without changing RETURN or making a reading per document. The syntax follows RETURN. In FT, the count also includes AS/alias tokens. Returns the first value of each path and omits missing paths. In HASH, the extension maintains the native representation of RETURN. The library uses RETURN normal for this format.

Projections JSON need the [patch typed-return.patch](../server-patches/typed-return.patch) already applied and tested in the local image. Projections HASH use RETURN existing. The writes HASH continue to need the patch HREPLACE. The application instructions and tests are in [Patches server/README.md](../server-patches/README.md). Ancient servers explicitly fail in the option not supported, without looking for the whole entity as an alternative.

## HTTP example

CustomerProjectionController uses the same data from the Customer and HashCustomer endpoints:

```powershell
# After creating customers in /customers and /hash-customers:
Invoke-RestMethod 'http://127.0.0.1:8080/customer-projections/json?city=SP&page=0&size=10'
Invoke-RestMethod 'http://127.0.0.1:8080/customer-projections/hash?city=SP&page=0&size=10'
Invoke-RestMethod 'http://127.0.0.1:8080/customer-projections/json/search?city=SP&page=0&size=10'
Invoke-RestMethod 'http://127.0.0.1:8080/customer-projections/hash/search?city=SP&page=0&size=10'
```

The first two routes return the record name/town/age. The search routes return the interface name/city/age. Email, ID, tags and details are not selected or serialized in these responses. Tests HTTP also verify that the proxy internal details do not appear.

## Validation

ProjectionContractTest validates the selection protocol, DTOs/interfaces, mappings, types, nulls, collections, accuracy/converters, limits and absence of extra readings. ProjectionIntegrationIT uses Real Datadam for annotated queries List/Page, programmatic queries, JSON/HASH aliases, missing/null fields, containers, BigDecimal/int64, callbacks, TTL/unaltered contents, ACL without complete reading, expiration and HTTP.

The Docker test stage of the server passed with 186 native tests and integration/persistence. The Java suite passed with 262 tests (120 units and 142 integration), including 12 unit tests and 16 new integration of projections. Run `./scripts/run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background` after applying the server patches that are still missing in your checkout.
