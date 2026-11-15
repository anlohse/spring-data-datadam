package com.anlohse.datadam.demo.examples.devices;
import com.anlohse.datadam.spring.annotation.ProjectionProperty;
public interface DeviceSummary {
    String getName(); String getRegion(); Boolean getOnline();
    @ProjectionProperty("lastSeenEpochMillis") Long getLastSeen();
}
