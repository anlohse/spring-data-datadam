package io.datadam.demo.examples.lifecycle;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.datadam.spring.annotation.DatadamDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
/** Immutable external imports request exclusive creation, including for an assigned ID. */
@DatadamDocument(prefix="demo:examples:external-ticket:")
public record ExternalTicket(@Id String id,String subject) implements Persistable<String> {
    @Override public String getId(){return id;}
    @Override @JsonIgnore public boolean isNew(){return true;}
}
