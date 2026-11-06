package io.datadam.spring;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import static org.assertj.core.api.Assertions.*;

class ProjectionContractTest {
    final Fake commands=new Fake();final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final DatadamTemplate template=new DatadamTemplate(commands,mapper);
    final EntityModel<JsonDoc> model=new EntityModel<>(JsonDoc.class);
    @Test void idxUsesTypedReturnForSelectedFieldsOnlyAndPreservesNullLiteral(){
        commands.reply=List.of("json:1",List.of("$['name']","\"null\"","$['home_city']","null","$['age']","25"));
        var result=template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,ProjectionSpec.of(Summary.class));
        assertThat(result).containsExactly(new Summary("null",null,25));
        assertThat(commands.names).containsExactly("IDX.QUERY");
        assertThat(commands.args).containsSequence("RETURNJSON",3,"$['name']","$['home_city']","$['age']");
        assertThat(Arrays.asList(commands.args)).doesNotContain("$","$['id']","$['secret']");
    }
    @Test void ftUsesReturnedTotalOrderSortAndBoundArguments(){
        commands.reply=List.of(5L,"json:2",List.of("$['name']","\"B\"","$['home_city']","\"SP\"","$['age']","30"));
        var page=template.projectSearch("ft",new SearchQuery("@home:{$c}",Map.of("c","SP"),Sort.by("years")),PageRequest.of(1,1),model,ProjectionSpec.of(Summary.class));
        assertThat(page.getTotalElements()).isEqualTo(5);assertThat(page.getContent()).containsExactly(new Summary("B","SP",30));
        assertThat(commands.args).containsSequence("SORTBY","years",Sort.Direction.ASC,"LIMIT",1L,1);
        assertThat(commands.args).containsSequence("PARAMS",2,"c","SP","DIALECT",2);
    }
    @Test void hashProjectionUsesIndexAliasesAndFieldTypeConversions(){
        commands.reply=List.of(1L,"hash:1",List.of("home","null","age","25","tags","[\"a\",null]"));
        var result=template.projectSearchResults("hft",new SearchQuery("*",Map.of(),Sort.unsorted()),0,10,new EntityModel<>(HashDoc.class),ProjectionSpec.of(HashView.class));
        assertThat(result.content()).containsExactly(new HashView("null",25,Arrays.asList("a",null)));
        assertThat(commands.args).containsSequence("RETURN",3,"home","age","tags");
        assertThat(commands.names).containsExactly("FT.SEARCH");
    }
    @Test void missingFieldsRemainNullInsteadOfPrimitiveDefaults(){
        commands.reply=List.of("json:1",List.of("$['name']","\"A\""));
        assertThat(template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,ProjectionSpec.of(Summary.class)))
            .containsExactly(new Summary("A",null,null));
    }
    @Test void closedInterfacesReceiveAlreadyConvertedValues(){
        commands.reply=List.of("json:1",List.of("$['age']","25","$['name']","\"Ana\""));
        var view=template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,ProjectionSpec.of(View.class)).get(0);
        assertThat(view.getName()).isEqualTo("Ana");assertThat(view.getAge()).isEqualTo(25);
    }
    @Test void aliasesCanBeBoundProgrammaticallyAndDuplicateSourcesAreSelectedOnce(){
        commands.reply=List.of("json:1",List.of("$['name']","\"Ana\""));
        var projection=ProjectionSpec.of(Duplicate.class).field("first","name").field("second","name");
        assertThat(template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,projection)).containsExactly(new Duplicate("Ana","Ana"));
        assertThat(commands.args).containsSequence("RETURNJSON",1,"$['name']");
    }
    @Test void invalidTargetsSourcesTypesAndEntityTargetsFailBeforeIo(){
        for(var type:List.of(JsonDoc.class,Primitive.class,Unknown.class,WrongType.class,Open.class,MetaOpen.class,Setter.class,DefaultView.class))
            assertThatIllegalArgumentException().isThrownBy(()->template.validateProjection("idx",model,ProjectionSpec.of(type),DatadamIndex.Kind.COMPOUND));
        assertThatIllegalArgumentException().isThrownBy(()->template.validateProjection("idx",model,ProjectionSpec.of(Summary.class).field("absent","name"),DatadamIndex.Kind.COMPOUND));
        assertThatIllegalArgumentException().isThrownBy(()->template.validateProjection("idx",model,ProjectionSpec.of(Summary.class).field("town","address.city"),DatadamIndex.Kind.COMPOUND));
        assertThat(commands.names).isEmpty();
    }
    @Test void incompatibleNativeValuesAndMalformedRepliesRaiseRetrievalErrors(){
        commands.reply=List.of("json:1",List.of("$['age']","\"25\""));
        assertThatThrownBy(()->template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,ProjectionSpec.of(Summary.class))).isInstanceOf(DataRetrievalFailureException.class);
        commands.reply=List.of("wrong:1",List.of());
        assertThatThrownBy(()->template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,ProjectionSpec.of(Summary.class))).isInstanceOf(DataRetrievalFailureException.class).hasMessageContaining("prefix");
        commands.reply=List.of("json:1");
        assertThatThrownBy(()->template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,ProjectionSpec.of(Summary.class))).isInstanceOf(DataRetrievalFailureException.class);
    }
    @Test void pageValidationPreventsUnboundedFtAndConflictingSorts(){
        assertThatIllegalArgumentException().isThrownBy(()->template.projectSearch("ft",new SearchQuery("*",Map.of(),Sort.unsorted()),Pageable.unpaged(),model,ProjectionSpec.of(Summary.class)));
        assertThatIllegalArgumentException().isThrownBy(()->template.projectSearch("ft",new SearchQuery("*",Map.of(),Sort.by("years")),PageRequest.of(0,10,Sort.by("other")),model,ProjectionSpec.of(Summary.class)));
        assertThat(commands.names).isEmpty();
    }
    @Test void projectedCollectionsKeepNestedJsonValuesAndInt64Precision(){
        commands.reply=List.of("json:1",List.of("$['tags']","[\"x\",null]","$['large']","9007199254740993"));
        assertThat(template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,ProjectionSpec.of(Values.class)))
            .containsExactly(new Values(Arrays.asList("x",null),9007199254740993L));
    }
    @Test void projectionSpecIsImmutableAndRejectsDuplicateBindings(){
        var original=ProjectionSpec.of(Summary.class);var mapped=original.field("town","city");
        assertThat(original.fields()).isEmpty();assertThat(mapped.fields()).containsEntry("town","city");
        assertThatIllegalArgumentException().isThrownBy(()->mapped.field("town","city"));
        assertThatThrownBy(()->mapped.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void untypedMapNumbersRetainTheApplicationsJacksonConversion(){
        commands.reply=List.of("json:1",List.of("$['details']","{\"rate\":1.25}"));
        var json=template.projectQueryContent("idx",CompoundQuery.all(),0,10,model,ProjectionSpec.of(MapView.class)).get(0);
        assertThat(json.details().get("rate")).isInstanceOf(Double.class).isEqualTo(1.25);
        commands.reply=List.of(1L,"hash:1",List.of("details","{\"rate\":1.25}"));
        var hash=template.projectSearchResults("hft",new SearchQuery("*",Map.of(),Sort.unsorted()),0,10,new EntityModel<>(HashDoc.class),ProjectionSpec.of(MapView.class)).content().get(0);
        assertThat(hash.details().get("rate")).isInstanceOf(Double.class).isEqualTo(1.25);
    }
    static class Fake extends DatadamCommands{Object reply;Object[] args;List<String> names=new ArrayList<>();Fake(){super(null);}
        @Override public Object execute(String name,Object...args){this.args=args;names.add(name);return name.equals("IDX.COUNT")?5L:reply;}}
    record Summary(String name,@ProjectionProperty("city")String town,Integer age){}
    record HashView(@ProjectionProperty("city")String town,Integer age,List<String> tags){}
    record Duplicate(String first,String second){}
    record Values(List<String> tags,Long large){}
    record MapView(Map<String,Object> details){}
    public interface View{String getName();Integer getAge();}
    interface Open{@org.springframework.beans.factory.annotation.Value("#{target.name}")String getName();}
    @org.springframework.beans.factory.annotation.Value("#{target.name}") @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    @interface Expression{}
    interface MetaOpen{@Expression String getName();}
    interface Setter{void setName(String value);}
    interface DefaultView{default String getName(){return "computed";}}
    record Primitive(int age){} record Unknown(String absent){} record WrongType(String age){}
    @DatadamDocument(prefix="json:")
    @DatadamIndex(name="idx",fields=@IndexField(path="$.age",alias="age",type=IndexField.Type.NUMERIC))
    @DatadamIndex(name="ft",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="$.age",alias="years",type=IndexField.Type.NUMERIC))
    record JsonDoc(@Id String id,String name,@JsonProperty("home_city")String city,int age,List<String> tags,Long large,String secret,Map<String,Object> details){}
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="hft",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="city",alias="home"))
    record HashDoc(@Id String id,String city,Integer age,List<String> tags,Map<String,Object> details){}
}
