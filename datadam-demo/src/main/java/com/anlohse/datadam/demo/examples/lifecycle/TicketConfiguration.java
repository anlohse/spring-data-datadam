package com.anlohse.datadam.demo.examples.lifecycle;
import com.anlohse.datadam.spring.DatadamIdGenerator;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class TicketConfiguration {
    @Bean public DatadamIdGenerator ticketIdGenerator(){return context->"TKT-"+java.util.UUID.randomUUID();}
}
