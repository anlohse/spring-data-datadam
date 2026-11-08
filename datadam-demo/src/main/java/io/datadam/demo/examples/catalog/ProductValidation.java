package io.datadam.demo.examples.catalog;
import io.datadam.spring.callback.*;
import org.springframework.stereotype.Component;
@Component
public class ProductValidation implements BeforeSaveCallback<Product> {
    public Product onBeforeSave(Product value,DatadamSaveContext context) {
        if(value.sku()==null||value.sku().isBlank()||value.name()==null||value.name().isBlank()||value.category()==null||value.category().isBlank()
            ||value.price()==null||value.price().signum()<0||!Double.isFinite(value.price().doubleValue()))
            throw new IllegalArgumentException("SKU, name, category and finite nonnegative price are required");
        return value;
    }
}
