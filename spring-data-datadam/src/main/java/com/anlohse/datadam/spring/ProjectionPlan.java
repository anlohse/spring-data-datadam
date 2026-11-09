package com.anlohse.datadam.spring;

import java.beans.Introspector;
import java.lang.reflect.*;
import java.util.*;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.ProjectionProperty;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.anlohse.datadam.spring.annotation.*;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.util.ClassUtils;

/** Validated native field selection; never constructs or invokes callbacks on a source entity. */
final class ProjectionPlan<R> {
    record Column(String target,String stored,String selector,JavaType type){}
    private final EntityModel<?> model;
    private final ProjectionSpec<R> spec;
    private final List<Column> columns=new ArrayList<>();
    private final ObjectMapper mapper;
    private final ObjectMapper treeMapper;
    private Constructor<R> constructor;
    @SuppressWarnings("deprecation")
    ProjectionPlan(ObjectMapper original, EntityModel<?> model, DatadamIndex index, ProjectionSpec<R> spec){
        this.model=model;this.spec=spec;
        mapper=original.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        treeMapper=mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer,CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float,CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail);
        Class<R> type=spec.type();
        if(type==model.type()||type.isAnnotationPresent(DatadamDocument.class))throw invalid("A projection cannot be an entity type");
        if(type.isRecord()){
            var components=type.getRecordComponents();
            for(var component:components)add(component.getName(),component.getGenericType(),component.getAnnotation(ProjectionProperty.class),index);
            try{constructor=type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));constructor.setAccessible(true);}
            catch(ReflectiveOperationException e){throw new IllegalArgumentException("Cannot access projection record constructor",e);}
        }else if(type.isInterface()){
            var methods=Arrays.stream(type.getMethods()).sorted(Comparator.comparing(Method::getName)).toList();
            for(var method:methods){
                if(method.getDeclaringClass()==Object.class||Modifier.isStatic(method.getModifiers()))continue;
                if(method.isDefault()||method.getParameterCount()!=0||org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(method,org.springframework.beans.factory.annotation.Value.class)!=null)
                    throw invalid("Closed projection interfaces require getters only, without default methods or SpEL");
                String name=method.getName();
                if(name.startsWith("get")&&name.length()>3)name=Introspector.decapitalize(name.substring(3));
                else if(name.startsWith("is")&&name.length()>2&&(method.getReturnType()==Boolean.class||method.getReturnType()==boolean.class))name=Introspector.decapitalize(name.substring(2));
                else throw invalid("Closed projection interfaces require JavaBean getters");
                final String target=name;
                if(columns.stream().anyMatch(c->c.target().equals(target)))throw invalid("Duplicate projection getter: "+name);
                add(name,method.getGenericReturnType(),method.getAnnotation(ProjectionProperty.class),index);
            }
        }else throw invalid("Projections support records and closed getter interfaces only");
        if(columns.isEmpty())throw invalid("A projection must select at least one property");
        for(String target:spec.fields().keySet())if(columns.stream().noneMatch(c->c.target().equals(target)))throw invalid("Unknown projection target: "+target);
    }
    private void add(String target,Type targetType,ProjectionProperty binding,DatadamIndex index){
        JavaType resultType=mapper.constructType(targetType);
        if(resultType.isPrimitive()||resultType.hasRawClass(Object.class)||resultType.hasRawClass(Optional.class))throw invalid("Projection properties require nullable, declared types: "+target);
        String source=spec.fields().getOrDefault(target,binding==null?target:binding.value());
        if(source.isBlank()||source.contains("."))throw invalid("Projection sources must be Java root properties: "+source);
        var property=mapper.getSerializationConfig().introspect(mapper.constructType(model.type())).findProperties().stream()
            .filter(p->p.getInternalName().equals(source)&&p.couldSerialize()).findFirst().orElseThrow(()->invalid("Unmapped projection source: "+source));
        JavaType sourceType=property.getPrimaryType();Class<?> sourceRaw=ClassUtils.resolvePrimitiveIfNecessary(sourceType.getRawClass());
        if(!resultType.getRawClass().isAssignableFrom(sourceRaw)||resultType.isContainerType()&&!resultType.equals(sourceType))throw invalid("Projection property type differs from source: "+target);
        String stored=property.getName();
        String selector=model.isHash()?stored:"$['"+stored.replace("\\","\\\\").replace("'","\\'")+"']";
        if(model.isHash()){
            var indexed=Arrays.stream(index.fields()).filter(f->f.path().equals(stored)).findFirst();
            if(indexed.isPresent())selector=indexed.get().alias();
        }
        final String chosen=selector;
        if(Arrays.stream(index.fields()).anyMatch(f->f.alias().equals(chosen)&&!f.path().equals(model.isHash()?stored:chosen)))
            throw invalid("Index alias shadows a projection source: "+chosen);
        columns.add(new Column(target,stored,selector,resultType));
    }
    List<String> selectors(){return columns.stream().map(Column::selector).distinct().toList();}
    String option(){return model.isHash()?"RETURN":"RETURNJSON";}
    R read(String key,Object response){
        if(!key.startsWith(model.prefix()))throw new DataRetrievalFailureException("Projection result has a different entity prefix");
        var fields=DatadamCommands.map(response);var values=new LinkedHashMap<String,Object>();
        JsonNode hash=null;
        if(model.isHash()){
            var pairs=new ArrayList<Object>();
            for(var column:columns)if(fields.containsKey(column.selector())){pairs.add(column.stored());pairs.add(fields.get(column.selector()));}
            try{hash=treeMapper.readTree(new HashEntityCodec(mapper).decode(pairs,model.type()));}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new DataRetrievalFailureException("Cannot decode projected HASH fields",e);}
        }
        try{
            for(var column:columns){
                String json=model.isHash()?hash.has(column.stored())?hash.get(column.stored()).toString():null
                    :fields.containsKey(column.selector())?Objects.requireNonNull(fields.get(column.selector()),"Projected JSON value").toString():null;
                values.put(column.target(),json==null?null:mapper.readValue(json,column.type()));
            }
            if(constructor!=null)return constructor.newInstance(columns.stream().map(c->values.get(c.target())).toArray());
            return new SpelAwareProxyProjectionFactory().createProjection(spec.type(),Collections.unmodifiableMap(values));
        }catch(Exception e){throw new DataRetrievalFailureException("Cannot materialize projection "+spec.type().getName()+" for "+key,e);}
    }
    private IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
