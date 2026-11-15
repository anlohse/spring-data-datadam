package com.anlohse.datadam.demo.examples;
import java.time.Duration;
import java.util.List;

import com.anlohse.datadam.demo.examples.books.Book;
import com.anlohse.datadam.demo.examples.catalog.Product;
import com.anlohse.datadam.demo.examples.devices.DeviceState;
import com.anlohse.datadam.demo.examples.documents.ShoppingCart;
import com.anlohse.datadam.demo.examples.documents.UserPreferences;
import com.anlohse.datadam.demo.examples.inventory.StockItem;
import com.anlohse.datadam.demo.examples.lifecycle.ExternalTicket;
import com.anlohse.datadam.demo.examples.lifecycle.SupportTicket;
import com.anlohse.datadam.demo.examples.lifecycle.TemporaryReservation;
import com.anlohse.datadam.demo.examples.operations.ImportJob;
import com.anlohse.datadam.demo.examples.publishing.Article;
import com.anlohse.datadam.spring.DatadamIndexOperations;
import com.anlohse.datadam.demo.examples.catalog.*;
import com.anlohse.datadam.demo.examples.lifecycle.*;
import com.anlohse.datadam.demo.examples.inventory.*;
import com.anlohse.datadam.demo.examples.publishing.*;
import com.anlohse.datadam.demo.examples.documents.*;
import com.anlohse.datadam.demo.examples.devices.*;
import com.anlohse.datadam.demo.examples.books.*;
import com.anlohse.datadam.demo.examples.operations.*;
import com.anlohse.datadam.demo.examples.migration.MigrationProduct;
public final class ExampleSchemas {
    private ExampleSchemas(){}
    public static List<Class<?>> entities(){return List.of(Product.class, TemporaryReservation.class, SupportTicket.class, ExternalTicket.class, Article.class,
        StockItem.class, UserPreferences.class, ShoppingCart.class, DeviceState.class, Book.class, ImportJob.class,MigrationProduct.class);}
    public static void prepare(DatadamIndexOperations indexes){entities().forEach(type->indexes.ensureIndexes(type,Duration.ofSeconds(30)));}
}
