package io.datadam.demo.examples.devices;
import io.datadam.spring.annotation.ProjectionProperty;
public interface DeviceSummary {
    String getName(); String getRegion(); Boolean getOnline();
    @ProjectionProperty("lastSeenEpochMillis") Long getLastSeen();
}
