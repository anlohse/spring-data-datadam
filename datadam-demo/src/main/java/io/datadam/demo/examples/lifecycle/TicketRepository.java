package io.datadam.demo.examples.lifecycle;
import io.datadam.spring.repository.DatadamRepository;
public interface TicketRepository extends DatadamRepository<SupportTicket,String> {}
