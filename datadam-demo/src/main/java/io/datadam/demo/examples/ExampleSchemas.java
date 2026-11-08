package io.datadam.demo.examples;
import java.time.Duration;
import java.util.List;
import io.datadam.spring.DatadamIndexOperations;
import io.datadam.demo.examples.catalog.*;
import io.datadam.demo.examples.lifecycle.*;
import io.datadam.demo.examples.inventory.*;
import io.datadam.demo.examples.publishing.*;
import io.datadam.demo.examples.documents.*;
import io.datadam.demo.examples.devices.*;
import io.datadam.demo.examples.books.*;
import io.datadam.demo.examples.operations.*;
import io.datadam.demo.examples.migration.MigrationProduct;
public final class ExampleSchemas {
    private ExampleSchemas(){}
    public static List<Class<?>> entities(){return List.of(Product.class,TemporaryReservation.class,SupportTicket.class,ExternalTicket.class,Article.class,
        StockItem.class,UserPreferences.class,ShoppingCart.class,DeviceState.class,Book.class,ImportJob.class,MigrationProduct.class);}
    public static void prepare(DatadamIndexOperations indexes){entities().forEach(type->indexes.ensureIndexes(type,Duration.ofSeconds(30)));}
}
