package io.datadam.demo.examples.lifecycle;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
@DatadamDocument(prefix="demo:examples:ticket:")
public record SupportTicket(@Id @DatadamGeneratedId(generator="ticketIdGenerator") String id,String subject,String status) {}
