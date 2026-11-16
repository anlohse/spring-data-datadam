# Transport failures, TLS, and ACLs

Delivery 7.2 expands validation with actual Datadam servers and disposable containers. Tests do not pause/restart the service used by the application, do not use FLUSHALL and do not alter ACLs or host certificates. The production correction of this step enables endpoint identity verification TLS in the automatic pool.

## Uncertain outcomes and retry behavior

When a connection falls or the answer exceeds the timeout, the exception may have the same type in different situations:

| Test failure point | What the server received | Persistent outcome |
|---|---|---|
| Proxy closes before forwarding JSON.NUMINCRBY | No mutations | Counter remains 0 |
| Proxy receives the answer and discards | A completed mutation | Counter goes to 1 |
| Proxy retains response after execution and client suffers timeout | A completed mutation | Counter goes to 1 |

The three scenarios result in `DataAccessResourceFailureException` for the consumer. The customer **can not infer, only by the exception, if the writing was applied** `connection_error` in the metrics also does not resolve this ambiguity.

The library does not repeat writes automatically. The test counts the requests actually forwarded and confirms a single attempt. Manually repeating an increase after loss of response can increase it twice. The same care applies to append/pop of arrays, scripts, batch operations and commands with external callback effects.

After a mistake:

1. Keep identification of the operation and avoid blind repetition in the customer HTTP, job or generic retry policy.
2. Consult the current state for a usable connection and make reconciliation according to the rules of the domain. Under competition, the simple reading of an accountant does not necessarily identify which operation was applied.
3. If the domain requires secure repetition, model an idempotence/deduplication key and the required atomity on the server. The library does not add this warranty on its own.
4. For lots, consider partial success: previous operations may have been confirmed before failure.

In the failure to purchase before sending a command, this command has not yet been sent by this transport call. However, a repository operation may have already executed previous commands. Do not generalize this observation for rollback of the entire operation.

## Versioning and lost acknowledgments

The save test with `@Version` drops the response of EVAL **later** from Datadam to confirm the execution. The document on the server goes from version 0 to 1. The object received by the client continues in the previous version because there was no confirmation of success. The recording is not repeated internally.

An explicit repetition with the old version fails by optimistic localing, and a new reading finds the original recording. This helps to detect outdated state. It does not transform all operations into idempotents or undo calls already executed. Reconcile the object and intent of the operation before deciding whether to record again.

## Restarts, broken connections, and pooling

`TransportFailuresIT` warms a pool, restarts your disposable server and executes business commands again. With `validate-connection=true`, the loan tests the idle connection, discards the connection closed by restart and uses another. This occurs before sending the business command. It is not a repeat of a recording already sent.

After error while reading the response, the tests also check that the broken connection does not prevent the next PING. This connectivity recovery does not inform the result of the mutation that lost the response.

The concurrent saturation test occupies the only connection of a pool of its own. One call waits and continues when it is returned. Another, without return, fails within the set limit. In the end, the waiting counter returns to zero and the pool remains usable. The health check under saturation is already covered by the delivery 7.1.

Configure connection, reading and waiting according to [operational diagnosis](library-diagnostics.md). Max-wait limits waiting for the pool. It is not a single time frame for authentication, validation, command and entire repository operation. Tests use time slot for SO scaling. They are neither benchmarks nor latency guarantees.

## TLS chain, validity, and identity

```yaml
datadam:
  host: datadam.internal.example
  port: 6380
  ssl: true
```

The automatic pool uses the JVM truststore JSSE and configures `SSLParameters.endpointIdentificationAlgorithm=HTTPS`. The configured name/IP needs to match the server certificate. Despite the algorithm name, the transported protocol remains RESP over TLS, not HTTP.

**Behaviour change:** enable encryption only without validating the name of the endpoint was not sufficient in the previous configuration. From this delivery, certificates of another identity are refused, even if their CA is reliable. Host adjustment and SAN of the certificate when updating. Do not disable validation to circumvent the refusal. An external pool provided by the application continues to be responsible for its own configuration TLS.

For a private CA, set up an appropriate truststore before starting JVM, for example with `javax.net.ssl.trustStore`, `javax.net.ssl.trustStoreType` and `javax.net.ssl.trustStorePassword`. Keep passwords and keys in the safe setting of the environment. No trust manager is installed that accepts any certificate, nor is there library option to shut down the name check.

`TlsSecurityIT` generates CA and ephemeral certificates with `keytool`, available in JDK, and tests:

| Scenario | Customer Truststore | Server Certificate | Result required |
|---|---|---|---|
| Valid | Correct CA | Valid, SAN localhost/127.0.0.1 | PING authenticated reply PONG |
| Unknown AC | Other CA | Same valid certificate | Handshake failure |
| Incorrect identity | Correct CA | SAN wrong.invalid | Handshake failure |
| Expired | Correct CA | SAN correct, validity closed | Handshake failure |

Client and server TLS run in Docker, sharing the container network. The client uses the classes/dependencies actually compiled by Maven, in a JVM separate with its own truststore. This prevents inspection HTTPS/TLS of the host from replacing the certificate used in the test. In this machine, Avast actually replaced the localhost certificate during the first attempt outside of Docker.

The tests do not alter the antivirus, the overall trust of JVM tests or the Windows certificate repository. The CA key remains only in the temporary directory of the test process. The server receives only server certificate/key and the client only public trusters. The files and containers are discarded at the end.

The scenario uses authentication ACL over TLS and `tls-auth-clients no`: valid authentication of **server** and customer password. It does not constitute mTLS test, certificate rotation, all providers JSSE or all versions TLS. These behaviors are not inferred by the tests of this delivery.

## Expanded ACL matrix

`AclSecurityIT` starts a dedicated Datadam instance with protected mode enabled, the default user disabled, and three roles:

| Paper | Data | Consultations | Administration |
|---|---|---|---|
| Writer | JSON/HASH in the secure prefix:* | No index permissions | No DDL/index metadata |
| reader | Only visible keys of JSON/HASH | IDX/FT restricted to readable keys; EVAL authorised only to test internal checks | Metadates explicitly permitted, without DDL |
| schema | No reading/writing of documents | Without consulting documents | CREATE/INFO/LIST/DROP indexes |

The following shall be verified:

- Mandatory authentication and writing refusal outside the prefix of the writer.
- Reading of permitted keys and refusal of hidden keys in JSON and HASH.
- Rejection of JSON.SET, HREPLACE and DEL by the reader, even in readable keys.
- Content, native and total projection IDX/FT restricted to the authorized keys in both formats.
- Refusal of writing or reading hidden key by EVAL: authorising the script does not exempt ACL from the commands/keys called within it.
- Separation between index administration and access to documents; DROP index maintains documents.

Metadatas IDX.INFO/FT.INFO are global: a reader who received FT.INFO sees counts of all the index keys, although his queries return only the authorized keys. The test affirms this difference. It does not promise isolation of metadata by key pattern. Index administrative privileges are also not limited by the index name via key patterns.

These users exist only in the disposable container. The ACL local application is not expanded to run the tests. Previous suites continue to cover users without JSON.GET/HGETALL, written with TTL denied, callbacks, UNIQUE and separate migration credentials.

## Reproduce the tests

Prerequisites: JDK with keytool, Maven, Docker CLI/Desktop and the project Datadam image. Since delivery 7.3, revisions and digests are in [config/test-images.properties](../config/test-images.properties). The server uses an explicit C++ revision and JVMs use digests. The JVM TLS isolated can be indicated by `DATADAM_TLS_JAVA_IMAGE` and needs to run bytecode Java 17 and provide the java command. See [compatibility matrix](compatibility-and-performance.md) for the validated scope.

```powershell
./scripts/run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background
```

The script prepares the image of JVM TLS when it does not yet exist. To run Maven directly:

```powershell
./scripts/build-test-image.ps1
docker compose --env-file config/test-images.properties up -d --no-build
# Prepare the Java images of the contract, or use run-local.ps1 -IntegrationTests.
mvn verify -Pintegration
```

If the application is using JAR on Windows, stop/restart it with the script before repackaging. For a focused execution, use `-Dit.test=TransportFailuresIT,TlsSecurityIT,AclSecurityIT` and `-Dfailsafe.failIfNoSpecifiedTests=false` on the reactor. The default execution does not skip tests if Docker, image, key/certified or handshake are not available: explicitly fails.

Containers have their own names UUID and random doors published only in loopback. The helper uses an explicitly reserved door so that the address does not change at restart. The fault proxy has frame limits and closes sockets/workers at the end. It does not serve as a production proxy or library transport model.

Primary references: [SSLParameters and Endpoint Identification](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/net/ssl/SSLParameters.html#setEndpointIdentificationAlgorithm(java.lang.String)), in addition to `docs/security.md` and `docs/secondary-indexes.md` in the Datadam code used by the tests.
