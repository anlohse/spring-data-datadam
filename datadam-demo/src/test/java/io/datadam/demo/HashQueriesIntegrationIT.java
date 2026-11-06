package io.datadam.demo;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import io.datadam.spring.callback.AfterConvertCallback;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HashQueriesIntegrationIT.Fixtures.class)
class HashQueriesIntegrationIT {
    @Autowired HashCustomerRepository repository;
    @Autowired DatadamTemplate template;
    @Autowired TestRestTemplate http;
    @Autowired ReadProbe probe;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    private String city;
    private final List<String> keys=new ArrayList<>();
    @BeforeAll static void schema() {
        try(var pool=DatadamIntegrationIT.pool("schema","local-schema-password")) {
            var indexes=new DatadamIndexOperations(new DatadamCommands(pool));
            indexes.ensureIndexes(HashCustomer.class,Duration.ofSeconds(30));
            indexes.ensureIndexes(HashCustomer.class,Duration.ofSeconds(30));
            indexes.ensureIndexes(AliasDoc.class,Duration.ofSeconds(30));
        }
    }
    @BeforeEach void setup(){city="hash-query-"+UUID.randomUUID();probe.keys.clear();}
    @AfterEach void cleanup(){keys.forEach(key->template.commands().execute("DEL",key));probe.keys.clear();}
    HashCustomer customer(int age){return customer(city,age);}
    HashCustomer customer(String city,Integer age){
        String id=UUID.randomUUID().toString();keys.add("demo:hash:customer:"+id);
        return repository.save(new HashCustomer(id,"Ana",id+"@example.test",city,age,"Java; Spring",List.of("java","database"),Map.of("nested",List.of(1,2))));
    }
    HashCustomer changed(HashCustomer input,String city,Integer age,String labels){return new HashCustomer(input.id(),input.name(),input.email(),city,age,labels,input.tags(),input.details());}
    @Test void declaredIdxQueriesReturnFullEntitiesWithNativeRangeOrderAndTotals(){
        var first=customer(19);var second=customer(25);customer(40);
        var page=repository.byCityAndAge(city,18,40,PageRequest.of(0,1));
        assertThat(page.getContent()).containsExactly(first);assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(repository.byCityAndAge(city,18,40,PageRequest.of(1,1)).getContent()).containsExactly(second);
        var descending=repository.query("demo:hash:customers:city-age",CompoundQuery.all().where("city","=",city).desc(),PageRequest.of(0,10));
        assertThat(descending.getContent()).extracting(HashCustomer::age).containsExactly(40,25,19);
        assertThat(first.tags()).containsExactly("java","database");assertThat(page.getContent().get(0).details()).isEqualTo(first.details());
    }
    @Test void derivedCrudLookupCountExistsPageSliceAndTopWorkForHash(){
        var first=customer(19);var second=customer(25);customer(40);
        assertThat(repository.findByEmail(first.email())).contains(first);assertThat(repository.existsByEmail(first.email())).isTrue();
        assertThat(repository.countByCityAndAgeLessThan(city,40)).isEqualTo(2);
        var page=repository.findByCityAndAgeBetweenOrderByAgeDesc(city,18,39,PageRequest.of(0,1));
        assertThat(page.getContent()).containsExactly(second);assertThat(page.getTotalElements()).isEqualTo(2);
        var slice=repository.findByCityAndAgeGreaterThanEqualOrderByAgeAsc(city,18,PageRequest.of(0,1));
        assertThat(slice.getContent()).containsExactly(first);assertThat(slice.hasNext()).isTrue();
        assertThat(repository.findTop2ByCityAndAgeGreaterThanOrderByAgeDesc(city,18)).extracting(HashCustomer::age).containsExactly(40,25);
    }
    @Test void ftAliasesNumericRangeAndSortReturnOriginalHashFields(){
        var first=customer(19);var second=customer(25);
        var page=repository.searchCityAndAge(city.toUpperCase(Locale.ROOT),18,40,PageRequest.of(0,1,Sort.by(Sort.Direction.DESC,"years")));
        assertThat(page.getContent()).containsExactly(second);assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(repository.searchCityAndAge(city,18,40,PageRequest.of(1,1,Sort.by(Sort.Direction.DESC,"years"))).getContent()).containsExactly(first);
        assertThat(page.getContent().get(0).city()).isEqualTo(city);
    }
    @Test void numericOnlyDerivedQueriesUseFtWithAliasMappedSorting(){
        var first=customer(Integer.MAX_VALUE-1);var second=customer(Integer.MAX_VALUE);
        var page=repository.findByAgeGreaterThanEqual(Integer.MAX_VALUE-1,PageRequest.of(0,100,Sort.by(Sort.Direction.DESC,"age")));
        assertThat(page.getContent()).contains(first,second);
        assertThat(page.getContent()).extracting(HashCustomer::age).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }
    @Test void explicitSemicolonTagsTrimDeduplicateAndRespectCase(){
        var first=customer(20);String token="Java-"+first.id();
        var updated=repository.save(changed(first,city,20,token+"; Spring; ;"+token));
        assertThat(repository.byLabel(token,PageRequest.of(0,10))).containsExactly(updated);
        assertThat(repository.byLabel(token.toLowerCase(Locale.ROOT),PageRequest.of(0,10))).isEmpty();
        assertThat(repository.byLabel(token+",Spring",PageRequest.of(0,10))).isEmpty();
        assertThat(updated.labels()).contains("; ;");
    }
    @Test void defaultCommaFtTokenMatchingDiffersFromWholeFieldIdxEquality(){
        String left="Left-"+UUID.randomUUID();String right="Right-"+UUID.randomUUID();var input=customer(left+","+right,20);
        assertThat(repository.searchCityAndAge(left.toLowerCase(Locale.ROOT),0,100,PageRequest.of(0,10)).getContent()).containsExactly(input);
        assertThat(repository.byCityAndAge(left,0,100,PageRequest.of(0,10)).getContent()).isEmpty();
        assertThat(repository.findByCityAndAgeBetweenOrderByAgeDesc(left,0,100,PageRequest.of(0,10)).getContent()).isEmpty();
        assertThat(repository.findByCityAndAgeBetweenOrderByAgeDesc(input.city(),0,100,PageRequest.of(0,10)).getContent()).containsExactly(input);
    }
    @Test void hashJsonArrayFieldDoesNotBecomeIndividualTagElements(){
        var first=customer(20);
        var individual=repository.search("demo:hash:customers:search",new SearchQuery("@storedTags:{$t} @home:{$c}",Map.of("t","java","c",city),Sort.unsorted()),PageRequest.of(0,10));
        assertThat(individual.getContent()).isEmpty();
        assertThat(repository.findByEmail(first.email()).orElseThrow().tags()).containsExactly("java","database");
    }
    @Test void absentAndInvalidNumericFieldsAreNotNumericMatches(){
        var missing=customer(city,null);var present=customer(30);
        assertThat(repository.byCityAndAge(city,0,100,PageRequest.of(0,10)).getContent()).containsExactly(present);
        String invalid=UUID.randomUUID().toString();keys.add("demo:hash:customer:"+invalid);
        template.commands().execute("HREPLACE","demo:hash:customer:"+invalid,"id",invalid,"city",city,"age","not-a-number");
        assertThat(repository.searchCityAndAge(city,0,100,PageRequest.of(0,10)).getContent()).containsExactly(present);
        var allCity=template.searchResults("demo:hash:customers:search",new SearchQuery("@home:{$c}",Map.of("c",city),Sort.unsorted()),0,0,new EntityModel<>(HashCustomer.class));
        assertThat(allCity.total()).isEqualTo(3);assertThat(allCity.content()).isEmpty();
        assertThat(repository.findByEmail(missing.email())).contains(missing);
    }
    @Test void writesAndFieldRemovalUpdateBothNativeIndexFamilies(){
        var first=customer(20);String newCity="changed-"+first.id();
        var updated=repository.save(changed(first,newCity,30,"changed-"+first.id()));
        assertThat(repository.byCityAndAge(city,0,100,PageRequest.of(0,10)).getContent()).isEmpty();
        assertThat(repository.searchCityAndAge(city,0,100,PageRequest.of(0,10)).getContent()).isEmpty();
        assertThat(repository.byCityAndAge(newCity,0,100,PageRequest.of(0,10)).getContent()).containsExactly(updated);
        assertThat(repository.searchCityAndAge(newCity,0,100,PageRequest.of(0,10)).getContent()).containsExactly(updated);
        var removed=repository.save(changed(updated,null,30,null));
        assertThat(repository.searchCityAndAge(newCity,0,100,PageRequest.of(0,10)).getContent()).isEmpty();
        assertThat(repository.byCityAndAge(newCity,0,100,PageRequest.of(0,10)).getContent()).isEmpty();
        assertThat(repository.findByEmail(first.email())).contains(removed);
    }
    @Test void uniqueFailurePreservesFullDocumentTtlAndQueryOwners(){
        var owner=customer(20);var other=customer(30);String key="demo:hash:customer:"+other.id();
        template.commands().execute("PEXPIRE",key,120000);Object expiry=template.commands().execute("PEXPIRETIME",key);Object before=template.commands().execute("HGETALL",key);
        assertThatThrownBy(()->repository.save(new HashCustomer(other.id(),other.name(),owner.email(),city,99,null,null,null))).isInstanceOf(DuplicateKeyException.class);
        assertThat(template.commands().execute("HGETALL",key)).isEqualTo(before);assertThat(template.commands().execute("PEXPIRETIME",key)).isEqualTo(expiry);
        assertThat(repository.findByEmail(owner.email())).contains(owner);assertThat(repository.findByEmail(other.email())).contains(other);
        assertThat(repository.searchCityAndAge(city,0,100,PageRequest.of(0,10)).getContent()).containsExactlyInAnyOrder(owner,other);
    }
    @Test void expiredHashDisappearsFromIdxAndFt(){
        var first=customer(20);template.commands().execute("EXPIRE","demo:hash:customer:"+first.id(),0);
        assertThat(repository.findByEmail(first.email())).isEmpty();
        assertThat(repository.byCityAndAge(city,0,100,PageRequest.of(0,10)).getContent()).isEmpty();
        assertThat(repository.searchCityAndAge(city,0,100,PageRequest.of(0,10)).getContent()).isEmpty();
    }
    @Test void rootJacksonNamesAndNativeFtAliasesAreIndependent(){
        String id=UUID.randomUUID().toString();keys.add("demo:hash:alias:"+id);
        var model=new EntityModel<>(AliasDoc.class);var saved=template.save(new AliasDoc(id,city,21),model);
        assertThat(template.lookup("demo:hash:alias:city",model,city)).containsExactly(saved);
        assertThat(template.search("demo:hash:alias:search",new SearchQuery("@home:{$c}",Map.of("c",city),Sort.by("years")),PageRequest.of(0,10),model).getContent()).containsExactly(saved);
    }
    @Test void afterConvertRunsForHashQueryMaterializationButNotCounts(){
        var first=customer(20);probe.keys.clear();
        repository.findByEmail(first.email());repository.byCityAndAge(city,0,100,PageRequest.of(0,10));repository.searchCityAndAge(city,0,100,PageRequest.of(0,10));
        assertThat(probe.keys).containsExactly("demo:hash:customer:"+first.id(),"demo:hash:customer:"+first.id(),"demo:hash:customer:"+first.id());
        probe.keys.clear();repository.countByCityAndAgeLessThan(city,100);assertThat(probe.keys).isEmpty();
    }
    @Test void nativeSchemaMismatchIsRejectedWithoutDroppingAnExistingIndex(){
        try(var pool=DatadamIntegrationIT.pool("schema","local-schema-password")){
            var commands=new DatadamCommands(pool);var indexes=new DatadamIndexOperations(commands);
            String index="demo:hash:schema:drift";
            try{commands.execute("IDX.CREATE",index,"ON","HASH","PREFIX",1,"demo:hash:drift:","SCHEMA","labels","TAG","SEPARATOR",",");}
            catch(InvalidDataAccessResourceUsageException e){if(!e.getMessage().equals("ERR Index already exists"))throw e;}
            assertThatThrownBy(()->indexes.ensureIndexes(Drift.class,Duration.ofSeconds(30))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(DatadamCommands.map(commands.execute("IDX.INFO",index)).get("fields")).isEqualTo(List.of(List.of("labels","TAG","SEPARATOR",",")));
        }
    }
    @Test void httpExampleSupportsIdxDerivedAndFtRoutes(){
        var input=customer(25);String query="?city="+city+"&minimum=0&maximum=100";
        assertThat(http.getForObject("/hash-customers"+query,String.class)).contains(input.id(),"java","nested");
        assertThat(http.getForObject("/hash-customers/derived"+query,String.class)).contains(input.id());
        assertThat(http.getForObject("/hash-customers/search"+query,String.class)).contains(input.id());
        assertThat(http.getForEntity("/hash-customers?city="+city+"&size=101",String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void hashQueryContentsAndTotalsRespectReadableKeyPatternsWithoutHgetallPermission(){
        String visible="visible-"+UUID.randomUUID();String hidden="hidden-"+UUID.randomUUID();
        for(String id:List.of(visible,hidden)){
            keys.add("demo:hash:customer:"+id);
            repository.save(new HashCustomer(id,"ACL",id+"@example.test",city,20,"acl",List.of("hash"),Map.of()));
        }
        try(var pool=DatadamIntegrationIT.pool("reader","local-reader-password")){
            var restricted=new DatadamTemplate(new DatadamCommands(pool),mapper);var model=new EntityModel<>(HashCustomer.class);
            var idx=restricted.query("demo:hash:customers:city-age",CompoundQuery.all().where("city","=",city),PageRequest.of(0,10),model);
            assertThat(idx.getTotalElements()).isEqualTo(1);assertThat(idx.getContent()).extracting(HashCustomer::id).containsExactly(visible);
            var ft=restricted.search("demo:hash:customers:search",new SearchQuery("@home:{$c}",Map.of("c",city),Sort.unsorted()),PageRequest.of(0,10),model);
            assertThat(ft.getTotalElements()).isEqualTo(1);assertThat(ft.getContent()).extracting(HashCustomer::id).containsExactly(visible);
            assertThatThrownBy(()->restricted.commands().execute("HGETALL","demo:hash:customer:"+visible)).isInstanceOf(PermissionDeniedDataAccessException.class);
        }
    }

    static class ReadProbe implements AfterConvertCallback<HashCustomer>{
        final List<String> keys=new CopyOnWriteArrayList<>();
        public HashCustomer onAfterConvert(HashCustomer entity,String key){keys.add(key);return entity;}
    }
    @TestConfiguration static class Fixtures{@Bean ReadProbe hashQueryReadProbe(){return new ReadProbe();}}
    @DatadamDocument(prefix="demo:hash:alias:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="demo:hash:alias:city",fields=@IndexField(path="home_city",alias="home_city"))
    @DatadamIndex(name="demo:hash:alias:search",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="home_city",alias="home"),@IndexField(path="age",alias="years",type=IndexField.Type.NUMERIC)})
    record AliasDoc(@Id String id,@com.fasterxml.jackson.annotation.JsonProperty("home_city") String city,int age){}
    @DatadamDocument(prefix="demo:hash:drift:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="demo:hash:schema:drift",fields=@IndexField(path="labels",alias="labels",separator=";")) record Drift(@Id String id,String labels){}
}
