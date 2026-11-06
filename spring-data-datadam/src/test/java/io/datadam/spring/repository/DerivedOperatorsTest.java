package io.datadam.spring.repository;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.data.repository.core.support.DefaultRepositoryMetadata;
import static org.assertj.core.api.Assertions.*;

class DerivedOperatorsTest {
    final Fake commands=new Fake();final DatadamTemplate template=new DatadamTemplate(commands,new ObjectMapper());
    DerivedDatadamQuery query(String name){return query(Repo.class,name);}
    DerivedDatadamQuery query(Class<?> repo,String name){
        var method=Arrays.stream(repo.getMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
        return new DerivedDatadamQuery(method,new DefaultRepositoryMetadata(repo),new SpelAwareProxyProjectionFactory(),template);
    }
    @Test void idxPrefixUsesBoundUtf8RangeAndNativeAlias(){
        query("findByNameStartingWith").execute(new Object[]{"a*}|x"});
        assertThat(commands.name).isEqualTo("IDX.QUERY");
        assertThat(commands.args).containsSequence("label",">=","a*}|x","label","<","a*}|y");
    }
    @Test void ftPrefixKeepsUserDataInParamsAndOnlyAddsTheOperatorInSyntax(){
        query("findByNameStartingWithAndAgeGreaterThan").execute(new Object[]{"a*}|x",10});
        assertThat(commands.name).isEqualTo("FT.SEARCH");
        assertThat(commands.args[1].toString()).contains("@by_name:{$p0*}","@years:[$p1 +inf]").doesNotContain("a*}|x");
        assertThat(commands.args).containsSequence("p0","a*}|x").containsSequence("p1","(10");
    }
    @Test void unicodePrefixBoundsHandleSupplementaryMaxAndSurrogateGap(){
        for(var pair:List.of(new String[]{"🚀","🚁"},new String[]{"\uD7FF","\uE000"},new String[]{"a"+new String(Character.toChars(0x10ffff)),"b"})){
            query("findByNameStartingWith").execute(new Object[]{pair[0]});
            assertThat(commands.args).containsSequence("label","<",pair[1]);
        }
        query("findByNameStartingWith").execute(new Object[]{new String(Character.toChars(0x10ffff))});
        assertThat(Arrays.asList(commands.args)).doesNotContain("<");
    }
    @Test void prefixWhitespaceIsPreservedByIdxAndFtLeadingWhitespaceIsRejected(){
        query("findByNameStartingWith").execute(new Object[]{" x"});
        assertThat(commands.args).containsSequence("label",">="," x","label","<"," y");commands.calls=0;
        assertThatIllegalArgumentException().isThrownBy(()->query("findByNameStartingWithAndAgeGreaterThan").execute(new Object[]{" x",10})).withMessageContaining("leading spaces/tabs");
        assertThat(commands.calls).isZero();
        query("findByNameStartingWithAndAgeGreaterThan").execute(new Object[]{"x ",10});
        assertThat(commands.args).containsSequence("p0","x ");
    }
    @Test void ignoreCaseRequiresFoldedFtAndAsciiValues(){
        query("findByNameStartingWithIgnoreCase").execute(new Object[]{"AbC"});
        assertThat(commands.name).isEqualTo("FT.SEARCH");assertThat(commands.args[0]).isEqualTo("ft-fold");
        commands.calls=0;
        assertThatIllegalArgumentException().isThrownBy(()->query("findByNameStartingWithIgnoreCase").execute(new Object[]{"ação"})).withMessageContaining("ASCII");
        assertThat(commands.calls).isZero();
    }
    @Test void prefixRejectsNullEmptyMalformedAndNonStringInputsBeforeIo(){
        for(Object value:Arrays.asList(null,"","\uD800",12))
            assertThatIllegalArgumentException().isThrownBy(()->query("findByNameStartingWith").execute(new Object[]{value}));
        assertThat(commands.calls).isZero();
    }
    @Test void numericNotHasAnExplicitPresenceGuard(){
        query("findByAgeNot").execute(new Object[]{25});
        assertThat(commands.name).isEqualTo("FT.SEARCH");
        assertThat(commands.args[1].toString()).contains("@years:[-inf +inf] -@years:[$p0 $p0]");
        assertThat(commands.args).containsSequence("p0","25");
    }
    @Test void numericNotInUsesOneGuardedNativeUnionAndSupportsArrays(){
        query("findByAgeNotIn").execute(new Object[]{new int[]{20,30}});
        assertThat(commands.args[1].toString()).contains("@years:[-inf +inf] -(@years:[$p0 $p0] | @years:[$p1 $p1])");
        assertThat(commands.args).containsSequence("PARAMS",4).containsSequence("p0","20").containsSequence("p1","30");
    }
    @Test void numericNotInRejectsEmptyNullAndNonFiniteMembersBeforeIo(){
        for(Object values:List.of(List.of(),Arrays.asList(10,null),List.of(Double.NaN)))
            assertThatIllegalArgumentException().isThrownBy(()->query("findByAgeNotIn").execute(new Object[]{values}));
        assertThat(commands.calls).isZero();
    }
    @Test void countAndExistsUseNativeTotalsWithoutMaterialization(){
        assertThat(query("countByAgeNot").execute(new Object[]{25})).isEqualTo(0L);
        assertThat(commands.args).containsSequence("LIMIT",0L,0);
        assertThat(query("existsByAgeNotIn").execute(new Object[]{List.of(20)})).isEqualTo(false);
        assertThat(commands.args).containsSequence("LIMIT",0L,0);
    }
    @Test void unsupportedFamiliesAndWrongPropertyKindsFailAtBootstrap(){
        for(String name:List.of("findByNameNot","findByNameNotIn"))
            assertThatIllegalArgumentException().isThrownBy(()->query(name)).withMessageContaining("numeric properties");
        assertThatIllegalArgumentException().isThrownBy(()->query("findByAgeStartingWith")).withMessageContaining("String property");
        for(String name:List.of("findByNameEndingWith","findByNameContaining","findByNameIsNull"))
            assertThatIllegalArgumentException().isThrownBy(()->query(name)).withMessageContaining("Unsupported predicate");
        assertThat(commands.calls).isZero();
    }
    @Test void hashPrefixUsesIdxWhileSplitOnlySchemasAreRefused(){
        query(HashRepo.class,"findByNameStartingWith").execute(new Object[]{"Ab"});
        assertThat(commands.name).isEqualTo("IDX.QUERY");assertThat(commands.args).containsSequence("name",">=","Ab","name","<","Ac");
        assertThatIllegalArgumentException().isThrownBy(()->query(SplitRepo.class,"findByNameStartingWith")).withMessageContaining("No compatible native index");
    }
    static class Fake extends DatadamCommands{String name;Object[]args;int calls;Fake(){super(null);}
        @Override public Object execute(String name,Object...args){this.name=name;this.args=args;calls++;if(name.equals("FT.SEARCH"))return List.of(0L);if(name.equals("IDX.QUERY"))return List.of();throw new AssertionError("Unexpected command "+name);}}
    @DatadamDocument(prefix="op:")
    @DatadamIndex(name="idx-name",fields=@IndexField(path="$.display_name",alias="label"))
    @DatadamIndex(name="ft-exact",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="$.display_name",alias="by_name",caseSensitive=true),@IndexField(path="$.age",alias="years",type=IndexField.Type.NUMERIC)})
    @DatadamIndex(name="ft-fold",kind=DatadamIndex.Kind.SEARCH,fields={@IndexField(path="$.display_name",alias="by_name"),@IndexField(path="$.age",alias="years",type=IndexField.Type.NUMERIC)})
    record Doc(@Id String id,@JsonProperty("display_name")String name,Integer age){}
    interface Repo extends DatadamRepository<Doc,String>{
        List<Doc> findByNameStartingWith(String prefix);List<Doc> findByNameStartingWithIgnoreCase(String prefix);
        List<Doc> findByNameStartingWithAndAgeGreaterThan(String prefix,int minimum);List<Doc> findByAgeNot(int value);
        List<Doc> findByAgeNotIn(Collection<Integer> values);long countByAgeNot(int value);boolean existsByAgeNotIn(Collection<Integer> values);
        List<Doc> findByNameNot(String value);List<Doc> findByNameNotIn(Collection<String> values);List<Doc> findByAgeStartingWith(String value);
        List<Doc> findByNameEndingWith(String value);List<Doc> findByNameContaining(String value);List<Doc> findByNameIsNull();
    }
    @DatadamDocument(prefix="hop:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="hidx",fields=@IndexField(path="name",alias="name")) record HashDoc(@Id String id,String name){}
    interface HashRepo extends DatadamRepository<HashDoc,String>{List<HashDoc> findByNameStartingWith(String prefix);}
    @DatadamDocument(prefix="split:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="hft",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="name",alias="name",caseSensitive=true)) record Split(@Id String id,String name){}
    interface SplitRepo extends DatadamRepository<Split,String>{List<Split> findByNameStartingWith(String prefix);}
}
