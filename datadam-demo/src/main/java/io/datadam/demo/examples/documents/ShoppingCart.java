package io.datadam.demo.examples.documents;
import java.util.*;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
/** Independent partial commands are demonstrations, not a transactional checkout. */
@DatadamDocument(prefix="demo:examples:cart:")
public record ShoppingCart(@Id @DatadamGeneratedId String id,String owner,List<Map<String,Object>> items,int quantity,Map<String,Object> delivery) {}
