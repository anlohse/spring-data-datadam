package io.datadam.spring;

import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import org.springframework.dao.*;
import static org.assertj.core.api.Assertions.*;

class HashIndexContractTest {
    @Test void hashCompoundHasNoAsAndVerifiesItsSeparator() {
        var fake=new Schema(); fake.info=info("compound",List.of(List.of("city","TAG","SEPARATOR",";")));
        new DatadamIndexOperations(fake).ensureIndexes(Compound.class,Duration.ofSeconds(1));
        assertThat(fake.create).containsExactly("idx","ON","HASH","PREFIX","1","hash:","SCHEMA","city","TAG","SEPARATOR",";");
    }
    @Test void hashFtEmitsAliasesAndAccountsForDefaultComma() {
        var fake=new Schema(); fake.info=info("search",List.of(List.of("home","TAG","city","SEPARATOR",",")));
        fake.attributes=List.of(List.of("identifier","city","attribute","home","type","TAG","SEPARATOR",","));
        new DatadamIndexOperations(fake).ensureIndexes(Search.class,Duration.ofSeconds(1));
        assertThat(fake.create).containsSequence("city","AS","home","TAG");
    }
    @Test void hashIdxAliasAndInvalidSeparatorFailBeforeDdl() {
        for(var type:List.of(BadAlias.class,BadNumericSeparator.class,BadWideSeparator.class)) {
            var fake=new Schema();
            assertThatIllegalArgumentException().isThrownBy(()->new DatadamIndexOperations(fake).ensureIndexes(type,Duration.ofSeconds(1)));
            assertThat(fake.create).isNull();
        }
    }
    @Test void persistedSeparatorAndFtIdentifierDriftAreRejected() {
        var fake=new Schema(); fake.info=info("compound",List.of(List.of("city","TAG","SEPARATOR",",")));
        assertThatThrownBy(()->new DatadamIndexOperations(fake).ensureIndexes(Compound.class,Duration.ofSeconds(1))).isInstanceOf(DataIntegrityViolationException.class);
        fake.info=info("search",List.of(List.of("home","TAG","wrong","SEPARATOR",",")));
        fake.attributes=List.of(List.of("identifier","wrong","attribute","home","type","TAG","SEPARATOR",","));
        assertThatThrownBy(()->new DatadamIndexOperations(fake).ensureIndexes(Search.class,Duration.ofSeconds(1))).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void idxAndFtMaterializeFullHashContentWithoutExtraReads() {
        var fake=new Schema(); fake.reply=List.of("hash:1",List.of("id","1","city","SP","tags","[\"a\",\"b\"]"));
        var template=new DatadamTemplate(fake,new ObjectMapper()); var model=new EntityModel<>(Compound.class);
        assertThat(template.queryContent("idx",CompoundQuery.all(),0,10,model)).containsExactly(new Compound("1","SP",List.of("a","b")));
        assertThat(fake.commands).containsExactly("IDX.QUERY");
        fake.reply=List.of(1L,"hash:1",List.of("id","1","city","SP")); fake.commands.clear();
        assertThat(template.searchResults("ft",new SearchQuery("*",Map.of(),Sort.unsorted()),0,10,new EntityModel<>(Search.class)).content()).containsExactly(new Search("1","SP"));
        assertThat(fake.commands).containsExactly("FT.SEARCH");
    }
    @Test void queryResultsFromOtherPrefixesAreRejectedBeforeMaterialization() {
        var fake=new Schema(); fake.reply=List.of("other:1",List.of("id","1","city","SP"));
        assertThatThrownBy(()->new DatadamTemplate(fake,new ObjectMapper()).queryContent("idx",CompoundQuery.all(),0,10,new EntityModel<>(Compound.class)))
            .isInstanceOf(DataRetrievalFailureException.class).hasMessageContaining("prefix");
    }
    static List<Object> info(String kind,List<?> fields) { return List.of("kind",kind,"on","HASH","state","ready","prefixes",List.of("hash:"),"fields",fields,"unique",0L,"keys_scanned",0L); }
    static class Schema extends DatadamCommands {
        List<Object> info; List<?> attributes; List<String> create; List<String> commands=new ArrayList<>(); Object reply;
        Schema(){super(null);}
        @Override public Object execute(String command,Object...args){
            commands.add(command);
            if(command.equals("IDX.CREATE") || command.equals("FT.CREATE")){create=Arrays.stream(args).map(Object::toString).toList();return "OK";}
            if(command.equals("IDX.INFO"))return info;
            if(command.equals("FT.INFO"))return List.of("attributes",attributes,"num_docs",0L,"num_records",0L);
            return reply;
        }
    }
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="idx",fields=@IndexField(path="city",alias="city",separator=";"))
    record Compound(@Id String id,String city,List<String> tags){}
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="ft",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="city",alias="home"))
    record Search(@Id String id,String city){}
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="idx",fields=@IndexField(path="city",alias="home")) record BadAlias(@Id String id){}
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="idx",fields=@IndexField(path="age",alias="age",type=IndexField.Type.NUMERIC,separator=";")) record BadNumericSeparator(@Id String id){}
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="idx",fields=@IndexField(path="city",alias="city",separator="é")) record BadWideSeparator(@Id String id){}
}
