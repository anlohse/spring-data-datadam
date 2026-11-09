package com.anlohse.datadam.demo.examples.lifecycle;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import org.springframework.data.annotation.Id;
@DatadamDocument(prefix="demo:examples:ticket:")
public record SupportTicket(@Id @DatadamGeneratedId(generator="ticketIdGenerator") String id, String subject, String status) {}
