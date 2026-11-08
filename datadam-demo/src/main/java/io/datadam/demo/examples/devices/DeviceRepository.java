package io.datadam.demo.examples.devices;
import java.util.*;
import io.datadam.spring.annotation.*;
import io.datadam.spring.repository.DatadamRepository;
import org.springframework.data.domain.*;
public interface DeviceRepository extends DatadamRepository<DeviceState,String> {
    Page<DeviceState> findByRegionAndLastSeenEpochMillisGreaterThanEqualOrderByLastSeenEpochMillisDesc(String region,long since,Pageable page);
    @FtQuery(index="demo:examples:devices:search",value="@tag:{?0}") List<DeviceState> byLabel(String label,Pageable page);
    @FtQuery(index="demo:examples:devices:search",value="@tag:{?0} @zone:{?1}") List<DeviceState> byLabelInRegion(String label,String regionToken,Pageable page);
    @FtQuery(index="demo:examples:devices:search",value="@zone:{?0} @seen:[?1 +inf]") Page<DeviceSummary> summaries(String region,long since,Pageable page);
    @IdxQuery(index="demo:examples:devices:region-seen",value="region = ?0 lastSeenEpochMillis >= ?1") Page<DeviceSummary> exactRegionSummaries(String region,long since,Pageable page);
}
