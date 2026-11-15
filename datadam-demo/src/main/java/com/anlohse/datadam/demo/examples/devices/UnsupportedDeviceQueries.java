package com.anlohse.datadam.demo.examples.devices;
import java.util.List;
import org.springframework.data.repository.NoRepositoryBean;
import com.anlohse.datadam.spring.repository.DatadamRepository;
/** Used only for compiler diagnostics: semicolon-split labels cannot mean whole-field equality. */
@NoRepositoryBean
public interface UnsupportedDeviceQueries extends DatadamRepository<DeviceState,String> {
    List<DeviceState> findByLabels(String labels);
}
