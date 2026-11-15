package com.anlohse.datadam.demo.examples.lifecycle;
import java.util.List;
import com.anlohse.datadam.spring.repository.DatadamRepository;
public interface ReservationRepository extends DatadamRepository<TemporaryReservation,String> { List<TemporaryReservation> findByResource(String resource); }
