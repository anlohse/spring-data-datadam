package com.anlohse.datadam.spring;
import java.util.*;
/** Immutable target type and optional target-property -> source Java root-property bindings. */
public record ProjectionSpec<R>(Class<R> type, Map<String,String> fields) {
    public ProjectionSpec {
        Objects.requireNonNull(type);
        fields.forEach((target,source)->{if(target==null||target.isBlank()||source==null||source.isBlank())throw new IllegalArgumentException("Projection property names must not be blank");});
        fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
    public static <R> ProjectionSpec<R> of(Class<R> type){return new ProjectionSpec<>(type,Map.of());}
    public ProjectionSpec<R> field(String target,String source){
        if(target==null||target.isBlank()||source==null||source.isBlank())throw new IllegalArgumentException("Projection property names must not be blank");
        var copy=new LinkedHashMap<>(fields);if(copy.putIfAbsent(target,source)!=null)throw new IllegalArgumentException("Duplicate projection target: "+target);
        return new ProjectionSpec<>(type,copy);
    }
}
