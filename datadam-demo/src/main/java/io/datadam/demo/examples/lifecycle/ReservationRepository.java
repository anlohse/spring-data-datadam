package io.datadam.demo.examples.lifecycle;
import java.util.List;
import io.datadam.spring.repository.DatadamRepository;
public interface ReservationRepository extends DatadamRepository<TemporaryReservation,String> { List<TemporaryReservation> findByResource(String resource); }
