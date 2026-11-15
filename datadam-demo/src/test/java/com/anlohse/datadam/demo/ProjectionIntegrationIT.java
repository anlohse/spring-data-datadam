package com.anlohse.datadam.demo;

import java.util.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import com.anlohse.datadam.spring.*;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.DatadamStorage;
import com.anlohse.datadam.spring.annotation.IndexField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anlohse.datadam.spring.callback.AfterConvertCallback;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProjectionIntegrationIT.Fixtures.class)
class ProjectionIntegrationIT {
    @Autowired CustomerRepository json;
    @Autowired HashCustomerRepository hash;
    @Autowired
    DatadamTemplate template;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    @Autowired Probe probe;
    final List<String> keys=new ArrayList<>();String city;
    @BeforeAll static void schema(){try(var pool=DatadamIntegrationIT.pool("schema","local-schema-password")){
        var indexes=new DatadamIndexOperations(new DatadamCommands(pool));
        for(var type:List.of(Customer.class,HashCustomer.class,AliasJson.class,AliasHash.class,RichHash.class))indexes.ensureIndexes(type,Duration.ofSeconds(30));
    }}
    @BeforeEach void setup(){city="projection-"+UUID.randomUUID();probe.keys.clear();}
    @AfterEach void cleanup(){keys.forEach(key->template.commands().execute("DEL",key));probe.keys.clear();}
    Customer customer(String name,int age){String id=UUID.randomUUID().toString();keys.add("demo:customer:"+id);return json.save(new Customer(id,name,id+"@example.test",city,age,List.of("java","datadam")));}
    HashCustomer hashCustomer(String name,Integer age){String id=UUID.randomUUID().toString();keys.add("demo:hash:customer:"+id);return hash.save(new HashCustomer(id,name,id+"@example.test",city,age,"Java;Spring",List.of("java","datadam"),Map.of("nested",List.of(1,2))));}
    @Test void declaredIdxRecordProjectionPreservesOrderPageAndTotalForBothFormats(){
        customer("young",20);customer("older",30);hashCustomer("young",20);hashCustomer("older",30);
        var page=PageRequest.of(0,1);
        var a=json.summariesByCity(city,page);var b=hash.summariesByCity(city,page);
        assertThat(a.getContent()).containsExactly(new CustomerSummary("older",city,30));assertThat(b.getContent()).isEqualTo(a.getContent());
        assertThat(a.getTotalElements()).isEqualTo(2);assertThat(b.getTotalElements()).isEqualTo(2);
        assertThat(json.summariesByCity(city,PageRequest.of(1,1)).getContent()).containsExactly(new CustomerSummary("young",city,20));
        assertThat(hash.summariesByCity(city,PageRequest.of(1,1)).getContent()).containsExactly(new CustomerSummary("young",city,20));
    }
    @Test void declaredFtClosedInterfacesUseOriginalFieldsAndNativeAliases(){
        customer("Ana",25);hashCustomer("Ana",25);
        var a=json.viewsByCity(city,PageRequest.of(0,10)).getContent().get(0);var b=hash.viewsByCity(city,PageRequest.of(0,10)).getContent().get(0);
        assertThat(a.getName()).isEqualTo("Ana");assertThat(a.getCity()).isEqualTo(city);assertThat(a.getAge()).isEqualTo(25);
        assertThat(b.getName()).isEqualTo(a.getName());assertThat(b.getCity()).isEqualTo(city);assertThat(b.getAge()).isEqualTo(25);
    }
    @Test void declaredListProjectionsKeepNativeLimitsForBothIndexFamiliesAndFormats(){
        customer("young",20);customer("older",30);hashCustomer("young",20);hashCustomer("older",30);
        assertThat(json.summaryListByCity(city,PageRequest.of(0,1))).containsExactly(new CustomerSummary("older",city,30));
        assertThat(hash.summaryListByCity(city,PageRequest.of(0,1))).containsExactly(new CustomerSummary("older",city,30));
        assertThat(json.viewListByCity(city,PageRequest.of(0,1,Sort.by("age")))).extracting(CustomerView::getName).containsExactly("young");
        assertThat(hash.viewListByCity(city,PageRequest.of(0,1,Sort.by("years")))).extracting(CustomerView::getName).containsExactly("young");
    }
    @Test void jsonNullAndLiteralNullStringRemainDistinct(){
        customer("null",20);customer(null,30);
        var rows=json.summariesByCity(city,PageRequest.of(0,10)).getContent();
        assertThat(rows).containsExactly(new CustomerSummary(null,city,30),new CustomerSummary("null",city,20));
        var views=json.viewsByCity(city,PageRequest.of(0,10)).getContent();
        assertThat(views).extracting(CustomerView::getName).containsExactlyInAnyOrder(null,"null");
    }
    @Test void missingJsonAndHashFieldsMapToNullWithoutPrimitiveDefaults(){
        var first=customer("remove",20);template.json(Customer.class).remove(first.id(),JsonPath.of("$.name",String.class));
        hashCustomer(null,null);
        assertThat(json.summariesByCity(city,PageRequest.of(0,10)).getContent()).containsExactly(new CustomerSummary(null,city,20));
        var view=hash.viewsByCity(city,PageRequest.of(0,10)).getContent().get(0);
        assertThat(view.getName()).isNull();assertThat(view.getAge()).isNull();assertThat(view.getCity()).isEqualTo(city);
    }
    @Test void hashLiteralNullTextIsNotAnAbsentField(){
        hashCustomer("null",20);hashCustomer(null,30);
        assertThat(hash.summariesByCity(city,PageRequest.of(0,10)).getContent()).containsExactly(new CustomerSummary(null,city,30),new CustomerSummary("null",city,20));
    }
    @Test void programmaticProjectionSpecAliasesAndCollectionsWorkOnBothFormats(){
        customer("Ana",25);hashCustomer("Ana",25);
        var projection=ProjectionSpec.of(TagSummary.class).field("display","name");
        var a=json.projectQuery("demo:customers:city-age", CompoundQuery.all().where("city","=",city),PageRequest.of(0,10),projection);
        var b=hash.projectQuery("demo:hash:customers:city-age",CompoundQuery.all().where("city","=",city),PageRequest.of(0,10),projection);
        assertThat(a.getContent()).containsExactly(new TagSummary("Ana",List.of("java","datadam")));assertThat(b.getContent()).isEqualTo(a.getContent());
    }
    @Test void programmaticFtProjectionPreservesNativeSortingAndTotal(){
        hashCustomer("young",20);hashCustomer("older",30);
        var query=new SearchQuery("@home:{$c}",Map.of("c",city),Sort.by(Sort.Direction.DESC,"years"));
        var page=hash.projectSearch("demo:hash:customers:search",query,PageRequest.of(0,1),ProjectionSpec.of(CustomerSummary.class));
        assertThat(page.getContent()).containsExactly(new CustomerSummary("older",city,30));assertThat(page.getTotalElements()).isEqualTo(2);
    }
    @Test void projectionsDoNotFireEntityCallbacksOrChangeStoredContentOrTtl(){
        var a=customer("Ana",20);var b=hashCustomer("Ana",20);String ak="demo:customer:"+a.id();String bk="demo:hash:customer:"+b.id();
        template.commands().execute("PEXPIRE",ak,120000);template.commands().execute("PEXPIRE",bk,120000);
        Object aj=template.commands().execute("JSON.GET",ak),bh=template.commands().execute("HGETALL",bk);
        Object at=template.commands().execute("PEXPIRETIME",ak),bt=template.commands().execute("PEXPIRETIME",bk);probe.keys.clear();
        json.summariesByCity(city,PageRequest.of(0,10));hash.summariesByCity(city,PageRequest.of(0,10));json.viewsByCity(city,PageRequest.of(0,10));hash.viewsByCity(city,PageRequest.of(0,10));
        assertThat(probe.keys).isEmpty();assertThat(template.commands().execute("JSON.GET",ak)).isEqualTo(aj);assertThat(template.commands().execute("HGETALL",bk)).isEqualTo(bh);
        assertThat(template.commands().execute("PEXPIRETIME",ak)).isEqualTo(at);assertThat(template.commands().execute("PEXPIRETIME",bk)).isEqualTo(bt);
        json.findById(a.id());assertThat(probe.keys).containsExactly(ak);
    }
    @Test void readerWithoutJsonGetOrHgetallGetsOnlyReadableProjectionRowsAndTotals(){
        for(String prefix:List.of("visible-","hidden-")){
            String id=prefix+UUID.randomUUID();keys.add("demo:customer:"+id);keys.add("demo:hash:customer:"+id);
            json.save(new Customer(id,"ACL",id+"@json.test",city,20,List.of()));hash.save(new HashCustomer(id,"ACL",id+"@hash.test",city,20,"acl",List.of(),Map.of()));
        }
        try(var pool=DatadamIntegrationIT.pool("projection-reader","local-projection-reader-password")){
            var reader=new DatadamTemplate(new DatadamCommands(pool),mapper);
            var a=reader.projectQuery("demo:customers:city-age",CompoundQuery.all().where("city","=",city),PageRequest.of(0,10),new EntityModel<>(Customer.class),ProjectionSpec.of(CustomerSummary.class));
            var b=reader.projectSearch("demo:hash:customers:search",new SearchQuery("@home:{$c}",Map.of("c",city),Sort.unsorted()),PageRequest.of(0,10),new EntityModel<>(HashCustomer.class),ProjectionSpec.of(CustomerSummary.class));
            assertThat(a.getTotalElements()).isEqualTo(1);assertThat(b.getTotalElements()).isEqualTo(1);assertThat(a.getContent()).containsExactly(new CustomerSummary("ACL",city,20));assertThat(b.getContent()).isEqualTo(a.getContent());
            assertThatThrownBy(()->reader.commands().execute("JSON.GET",keys.get(0))).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThatThrownBy(()->reader.commands().execute("HGETALL",keys.get(1))).isInstanceOf(PermissionDeniedDataAccessException.class);
        }
    }
    @Test void expiredDocumentsDoNotProduceStaleProjectionRows(){
        var a=customer("Ana",20);var b=hashCustomer("Ana",20);
        template.commands().execute("EXPIRE","demo:customer:"+a.id(),0);template.commands().execute("EXPIRE","demo:hash:customer:"+b.id(),0);
        assertThat(json.summariesByCity(city,PageRequest.of(0,10)).getContent()).isEmpty();assertThat(hash.viewsByCity(city,PageRequest.of(0,10)).getContent()).isEmpty();
    }
    @Test void rootJacksonNamesAndIndexAliasesPreserveProjectionBindings(){
        var a=new AliasJson(UUID.randomUUID().toString(),"Ana",city,25);var b=new AliasHash(UUID.randomUUID().toString(),"Ana",city,25);
        var am=new EntityModel<>(AliasJson.class);var bm=new EntityModel<>(AliasHash.class);keys.add(am.key(a.id()));keys.add(bm.key(b.id()));template.save(a,am);template.save(b,bm);
        var query=new SearchQuery("@home:{$c}",Map.of("c",city),Sort.unsorted());
        var ar=template.projectSearch("demo:projection:json",query,PageRequest.of(0,10),am,ProjectionSpec.of(CustomerSummary.class));
        var br=template.projectSearch("demo:projection:hash",query,PageRequest.of(0,10),bm,ProjectionSpec.of(CustomerSummary.class));
        assertThat(ar.getContent()).containsExactly(new CustomerSummary("Ana",city,25));assertThat(br.getContent()).isEqualTo(ar.getContent());
    }
    @Test void nestedHashValuesRemainTyped(){
        var b=hashCustomer("Ana",20);
        var page=hash.projectSearch("demo:hash:customers:search",new SearchQuery("@home:{$c}",Map.of("c",city),Sort.unsorted()),PageRequest.of(0,10),ProjectionSpec.of(Details.class));
        assertThat(page.getContent()).containsExactly(new Details(b.details()));
    }
    @Test void hashProjectionsPreserveExactDecimalScaleAndInt64Values(){
        var source=new RichHash(UUID.randomUUID().toString(),new BigDecimal("1234567890.123456789012300"),9007199254740993L,List.of("x","y"));
        var model=new EntityModel<>(RichHash.class);keys.add(model.key(source.id()));template.save(source,model);
        var page=template.projectSearch("demo:projection:rich",new SearchQuery("@identifier:{$id}",Map.of("id",source.id()),Sort.unsorted()),PageRequest.of(0,10),model,ProjectionSpec.of(RichSummary.class));
        assertThat(page.getContent()).containsExactly(new RichSummary(source.balance(),source.large(),source.tags()));
    }
    @Test void httpSerializesOnlyRecordAndInterfaceProjectionProperties(){
        var a=customer("Ana",25);var b=hashCustomer("Ana",25);
        for(String path:List.of("/json","/hash","/json/search","/hash/search")){
            String response=http.getForObject("/customer-projections"+path+"?city="+city,String.class);
            assertThat(response).contains("Ana","25").doesNotContain(a.email(),b.email(),"\"email\"","\"tags\"","\"details\"","\"target\"","\"id\"");
        }
    }
    @Test void projectionTargetsCannotBeEntitiesAndInvalidBindingFailsBeforeQuery(){
        assertThatIllegalArgumentException().isThrownBy(()->json.projectQuery("demo:customers:city-age",CompoundQuery.all(),PageRequest.of(0,10),ProjectionSpec.of(Customer.class)));
        assertThatIllegalArgumentException().isThrownBy(()->hash.projectSearch("demo:hash:customers:search",new SearchQuery("*",Map.of(),Sort.unsorted()),PageRequest.of(0,10),ProjectionSpec.of(CustomerSummary.class).field("town","missing")));
    }
    public record TagSummary(String display,List<String> tags){}
    public record Details(Map<String,Object> details){}
    public record RichSummary(BigDecimal balance,Long large,List<String> tags){}
    static class Probe implements AfterConvertCallback<Object>{final List<String>keys=new CopyOnWriteArrayList<>();public Object onAfterConvert(Object entity,String key){keys.add(key);return entity;}}
    @TestConfiguration static class Fixtures{@Bean Probe projectionEntityProbe(){return new Probe();}}
    @DatadamDocument(prefix="demo:hash:projection-json:")
    @DatadamIndex(name="demo:projection:json",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="$.home_city",alias="home"))
    record AliasJson(@Id String id,String name,@com.fasterxml.jackson.annotation.JsonProperty("home_city")String city,Integer age){}
    @DatadamDocument(prefix="demo:hash:projection-hash:",storage= DatadamStorage.HASH)
    @DatadamIndex(name="demo:projection:hash",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="home_city",alias="home"))
    record AliasHash(@Id String id,String name,@com.fasterxml.jackson.annotation.JsonProperty("home_city")String city,Integer age){}
    @DatadamDocument(prefix="demo:hash:projection-rich:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="demo:projection:rich",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="id",alias="identifier",caseSensitive=true))
    record RichHash(@Id String id,BigDecimal balance,Long large,List<String> tags){}
}
