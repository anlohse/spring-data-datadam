# Library diagnostics

Item 7.1 adds explicit pool configuration, optional health check and optional metrics. Transportation continues RESP2/Jedis, with a command-borrowed connection and no automatic repetition of writes. No C++ server changes are required.

## Connections and pooling

```yaml
datadam:
  host: localhost
  port: 6380
  username: app
  password: ${DATADAM_PASSWORD}
  timeout: 5s
  connect-timeout: 1s
  read-timeout: 3s
  validate-connection: true
  pool:
    max-total: 16
    max-idle: 8
    min-idle: 2
    max-wait: 500ms
    block-when-exhausted: true
    test-while-idle: true
    eviction-interval: 30s
```

| Ownership | Standard | Effect |
|---|---|---|
| `datadam.timeout` | 5s | Compatible pattern used by the three limits below when not reported |
| `datadam.connect-timeout` | `timeout` | Jedis Connection Timeout |
| `datadam.read-timeout` | `timeout` | socket reading timeout |
| `datadam.pool.max-wait` | `timeout` | Waiting limit for a connection available when pool is sold out |
| `datadam.pool.max-total` | 8 | Maximum connection |
| `datadam.pool.max-idle` | 8 | Maximum of idle connections maintained when returning to the pool |
| `datadam.pool.min-idle` | 0 | Desired minimum of idle connections, maintained by the maintenance cycle when enabled |
| `datadam.pool.block-when-exhausted` | True. | Wait until max-wait. False fails immediately if no connection/creation is available |
| `datadam.validate-connection` | True. | Valid with PING before delivering each connection |
| `datadam.pool.test-while-idle` | false | Valid idle connections in the maintenance cycle |
| `datadam.pool.eviction-interval` | 0, deactivated | Maintenance/Eviction cycle interval |

The limits of connection, reading and waiting accept from 1 ms to 2.147.483.647 ms. Zero, negative values, submillissegundo and overflow are refused in the creation of the automatic pool. Requires `max-total >= 1` and `0 <= min-idle <= max-idle <= max-total`. By reducing max-total below 8, also max-idle. Eviction-interval accepts zero to disable or a positive interval at the same limit.

Min-idle does not open connections during the startup itself and is not an exclusive health reserve. With eviction-interval zero there is no periodic maintenance, even if test-while-idle/min-idle are configured. The other eviction rules follow the Commons Pool. The standard minimum time for eviction due to idleness is not changed by the library.

Max-wait limits the waiting of the pool, does not work as a single time of the entire operation. Creating the connection, authentication, validation PING, reading the response and running a repository operation can add time. A method that emits several commands, such as Page IDX, can consume several of these limits. Set the time limit of application request considering this behavior.

Validate-connection adds one go and one return per loan. The automatic pool uses a static endpoint validation that discards disconnected or invalid PING connections without registering the server exception. This preserves the discard of old connections after restarting, without the WARN detail of the Jedis standard validation. Acquisition faults, including exhaustion, are translated into `DataAccessResourceFailureException`. Authentication identified in the acquisition uses `PermissionDeniedDataAccessException` with generic message.

It is possible to replace the bean `datadamPool`. In this case, the creation, validation and limits of this pool belong to the application. The properties do not reconfigure an external pool. Only database 0 continues to be used by the automatic pool. The configuration generates Spring metadata for completion in IDE, with the annotation processor explicitly configured for compilations with JDK recent.

With `datadam.ssl=true`, the automatic pool also validates the identity of the endpoint TLS. CA's trust, SAN/name/IP, validity of certificates and transport failure scenarios are covered in [faults and safety](failures-and-security.md).

## Optional health indicator

The library declares Spring Boot Actuator as optional dependency. The consumer application you want the endpoint can add:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
datadam:
  health:
    enabled: true
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
      show-components: never
```

`datadam.health.enabled` is **false by default**, even with Actuator present. When enabled and with available classes, records `datadamHealthIndicator`. A bean with that name replaces the pattern. The Actuator component is called `datadam`.

The indicator emits PING by `DatadamCommands`, in the same pool and with the same limits of application. It does not create another pool or open a privileged connection. It needs permission PING/connection of the configured user. It does not need INFO, reading documents or DDL. UP only means that PING answered PONG using this path. It does not prove that indexes are READY, that you want to be authorized or that a migration is ready.

In failure, it returns DOWN with a maximum fixed category in `reason`: `permission`, `connection_or_pool`, `command` or `unexpected_response`. It does not associate the exception to the Health object or record its stack trace. Even if the application enables health details, the library contribution does not provide address, user, password, unexpected response PING or server message. There is no periodic collection of its own: each indicator invocation makes a check. With validation-connection connected, there is the PING validation and the PING explicit health.

The health shares the pool pressure: if all connections are busy, it can stay DOWN after the configured wait. This does not necessarily imply that the Datadam process has fallen. The decision to use this component in readiness/liveness groups belongs to the application.

## Optional metrics

Micrometer Core is also an optional dependency. The automatic configuration only records `DatadamMetrics` if there are `MeterRegistry` and `datadam.metrics.enabled=true`. The library does not create register, export or endpoint metrics. The demonstration starter Actuator provides the necessary infrastructure.

```yaml
datadam:
  metrics:
    enabled: true
```

| Enter | Type | Tags | Meaning |
|---|---|---|---|
| `datadam.command.duration` | Time | command, output | Transport control time, including acquisition/validation, response, conversion RESP and return of connection |
| `datadam.command.errors` | Counter | command, output | Calls that failed. No series of errors for success |
| `datadam.pool.acquire` | Time | output=success/failure | Acquisition, including creation/validation. Success indicates connection, even if command fails later |
| `datadam.pool.connections` | Gauge | state=active/idle | Borrowed and idle connections |
| `datadam.pool.waiters` | Gauge | none | Threads waiting for connection |
| `datadam.pool.max` | Gauge | none | Maximum connections configured |

Timers provide counting and cumulative duration. Export units follow the registry. Histograms/percentages are not enabled by the library. They can be configured by the application with Micrometer/Actuator resources.

`outcome` of the command belongs to the closed set `success`, `permission_error`, `duplicate_key`, `command_error`, `connection_error`, `pool_error`, `client_error`. These are operational classifications, not proof that a writing has been applied or undone. A connection error after shipment may result in uncertainty. The instrumentation does not allow automatic repetition.

`command` comes from a list of commands known by the library, such as JSON.GET, HREPLACE, IDX.QUERY and FT.SEARCH Unknown names are grouped into OTHER. Index names, entity properties, keys, filters, values, paths, host/port, user, credentials or dynamic message/class of the exception are not used as tags. Thus, unknown arguments and commands do not extend the cardinality of the series.

The explicit health enters as PING timesrs. Internal Jedis validation PINGs do not generate an event of their own. Their time is included in the acquisition. Internal commands to a EVAL are not observed individually, only the EVAL sent. The entity serialization before calling the transport and the full duration of the repository method do not belong to the timer.

## Manual configuration and extensions

```java
var metrics = new DatadamMetrics(registry, pool);
var commands = new DatadamCommands(pool, metrics);
var health = new DatadamHealthIndicator(commands);
```

The existing constructor `new DatadamCommands(pool)` keeps observation disabled. For a custom bean transport, pass the observer when building it. The configuration does not automatically involve a `DatadamCommands` supplied by the application.

A bean `DatadamCommandObserver` replaces the default Micrometer integration. It receives `CommandObservation` with listed operation/outcome, durations and acquisition indicator, without arguments or Throwable. RuntimeException faults of the observer are ignored without logging and do not replace the original return or exception of the command. Fatal errors of JVM are not deleted.

The automatic configuration represents a single pool `datadamPool` and a registry solved by Spring. The manual creation of several instruments with the same names in the same registry can share series/gauges. Use its own integration when instrumenting several pools. Without Actuator/Micrometer in the classpath, transport and repositories remain available, including if diagnostic flags are connected.

## Logs, sensitive data, and local example

The library does not record arguments, credentials or command exceptions in operational diagnoses. Health and meters are also sanitized on the path of error. Normal exceptions to operations continue to preserve useful causes and messages from the server, which may contain keys/values. Registering them or returning them in a API is an application decision. Logs DEBUG/TRACE of dependencies, exporters, observers and additional consumer-configured endpoints require their own data policy.

The demonstration links health and metrics and exposes only these two Actuator endpoints in the same `127.0.0.1` local blind application. It does not expose env, configprops, loggers or heapdump and keeps details/health components hidden. Enable the resource in the library does not publish endpoints on its own. Exposure depends on the Actuator of the application.

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health'
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/metrics/datadam.pool.connections'
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/metrics/datadam.command.duration'
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/metrics/datadam.command.errors'
```

The series of errors appears after the first observed failure. If there were no errors yet, the specific endpoint may not find it. Unit tests check optional classpath, opt-in, replacement of beans, limits, instrumentation, cardinality and absence of sensitive messages in health/logs. `LibraryDiagnosticsIntegrationIT` valid Actuator and meters in Docker, commands JSON/HASH, native failures, incorrect credentials and controlled exhaustion of a own pool with recovery. Execute `scripts/run-local.ps1 -Restart -IntegrationTests -Background`.

References: [Spring Boot 3.5 Actuator](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html), [Micrometer Timers](https://docs.micrometer.io/micrometer/reference/concepts/timers.html) and [Common Pool](https://commons.apache.org/proper/commons-pool/apidocs/org/apache/commons/pool2/impl/GenericObjectPoolConfig.html).
