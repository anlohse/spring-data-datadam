# Diagnóstico operacional da biblioteca

O item 7.1 acrescenta configuração explícita do pool, health check opcional e métricas opcionais. O transporte continua RESP2/Jedis, com uma conexão emprestada por comando e sem repetição automática de gravações. Nenhuma alteração de servidor C++ é necessária.

## Conexão e pool

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

| Propriedade | Padrão | Efeito |
|---|---|---|
| `datadam.timeout` | 5s | Padrão compatível usado pelos três limites abaixo quando não informados |
| `datadam.connect-timeout` | `timeout` | Timeout de conexão Jedis |
| `datadam.read-timeout` | `timeout` | Timeout de leitura do socket |
| `datadam.pool.max-wait` | `timeout` | Limite de espera por uma conexão disponível quando o pool está esgotado |
| `datadam.pool.max-total` | 8 | Máximo de conexões |
| `datadam.pool.max-idle` | 8 | Máximo de conexões ociosas mantidas ao retornar ao pool |
| `datadam.pool.min-idle` | 0 | Mínimo desejado de conexões ociosas, mantido pelo ciclo de manutenção quando habilitado |
| `datadam.pool.block-when-exhausted` | true | Espera até max-wait; false falha imediatamente se não houver conexão/criação disponível |
| `datadam.validate-connection` | true | Valida com PING antes de entregar cada conexão |
| `datadam.pool.test-while-idle` | false | Valida conexões ociosas no ciclo de manutenção |
| `datadam.pool.eviction-interval` | 0, desativado | Intervalo do ciclo de manutenção/evicção |

Os limites de conexão, leitura e espera aceitam de 1 ms a 2.147.483.647 ms. Zero, valores negativos, submilissegundo e overflow são recusados na criação do pool automático. Exige-se `max-total >= 1` e `0 <= min-idle <= max-idle <= max-total`; ao reduzir max-total abaixo de 8, ajuste também max-idle. Eviction-interval aceita zero para desativar ou um intervalo positivo no mesmo limite.

Min-idle não abre conexões durante o startup por si só e não é uma reserva exclusiva para health. Com eviction-interval zero não há manutenção periódica, mesmo que test-while-idle/min-idle estejam configurados. As demais regras de evicção seguem o Commons Pool; o tempo mínimo padrão para evicção por ociosidade não é alterado pela biblioteca.

Max-wait limita a espera do pool, não funciona como prazo único de toda a operação. Criação da conexão, autenticação, validação PING, leitura da resposta e execução de uma operação de repositório podem adicionar tempo. Um método que emite vários comandos, como Page IDX, pode consumir vários desses limites. Defina o prazo da requisição da aplicação considerando esse comportamento.

Validate-connection acrescenta uma ida e volta por empréstimo. O pool automático usa uma validação de endpoint estático que descarta conexões desconectadas ou com PING inválido sem registrar a exceção do servidor. Isso preserva o descarte de conexões antigas após reinício, sem o WARN detalhado da validação padrão do Jedis. Falhas de aquisição, inclusive esgotamento, são traduzidas para `DataAccessResourceFailureException`; autenticação identificada na aquisição usa `PermissionDeniedDataAccessException` com mensagem genérica.

É possível substituir o bean `datadamPool`. Nesse caso, a criação, validação e os limites desse pool pertencem à aplicação; as propriedades não reconfiguram um pool externo. Apenas database 0 continua sendo usado pelo pool automático. A configuração gera metadados Spring para conclusão no IDE, com o annotation processor configurado explicitamente para compilações com JDK recente.

Com `datadam.ssl=true`, o pool automático valida também a identidade do endpoint TLS. Confiança da CA, SAN/nome/IP, validade dos certificados e cenários de falha de transporte são cobertos em [falhas e segurança](failures-and-security.md).

## Health opcional

A biblioteca declara Spring Boot Actuator como dependência opcional. A aplicação consumidora que quiser o endpoint pode adicionar:

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

`datadam.health.enabled` é **false por padrão**, mesmo com Actuator presente. Quando habilitado e com as classes disponíveis, registra `datadamHealthIndicator`; um bean com esse nome substitui o padrão. O componente Actuator chama-se `datadam`.

O indicador emite PING por `DatadamCommands`, no mesmo pool e com os mesmos limites da aplicação. Não cria outro pool nem abre uma conexão privilegiada. Precisa de permissão PING/conexão do usuário configurado; não precisa de INFO, leitura de documentos ou DDL. UP significa somente que PING respondeu PONG usando esse caminho; não comprova que índices estão READY, que queries estão autorizadas ou que uma migração está pronta.

Em falha, devolve DOWN com no máximo uma categoria fixa em `reason`: `permission`, `connection_or_pool`, `command` ou `unexpected_response`. Não associa a exceção ao objeto Health nem registra sua stack trace. Mesmo que a aplicação habilite detalhes de health, a contribuição da biblioteca não fornece endereço, usuário, senha, resposta PING inesperada ou mensagem do servidor. Não há coleta periódica própria: cada invocação do indicador faz uma verificação. Com validate-connection ligado, há o PING da validação e o PING explícito do health.

O health compartilha a pressão do pool: se todas as conexões estiverem ocupadas, ele pode ficar DOWN após a espera configurada. Isso não implica necessariamente que o processo Datadam caiu. A decisão de usar esse componente em grupos de readiness/liveness pertence à aplicação.

## Métricas opcionais

Micrometer Core também é uma dependência opcional. A configuração automática só registra `DatadamMetrics` se houver `MeterRegistry` e `datadam.metrics.enabled=true`; a biblioteca não cria registry, exporter ou endpoint de métricas. O starter Actuator da demonstração fornece a infraestrutura necessária.

```yaml
datadam:
  metrics:
    enabled: true
```

| Meter | Tipo | Tags | Significado |
|---|---|---|---|
| `datadam.command.duration` | Timer | command, outcome | Tempo de um comando do transporte, incluindo aquisição/validação, resposta, conversão RESP e devolução da conexão |
| `datadam.command.errors` | Counter | command, outcome | Chamadas que falharam; não há série de erro para sucesso |
| `datadam.pool.acquire` | Timer | outcome=success/failure | Aquisição, incluindo criação/validação; success indica que houve conexão, mesmo se o comando falhar depois |
| `datadam.pool.connections` | Gauge | state=active/idle | Conexões emprestadas e ociosas |
| `datadam.pool.waiters` | Gauge | nenhuma | Threads esperando conexão |
| `datadam.pool.max` | Gauge | nenhuma | Máximo de conexões configurado |

Timers fornecem contagem e duração acumulada; unidades de exportação seguem o registry. Histogramas/percentis não são habilitados pela biblioteca. Podem ser configurados pela aplicação com os recursos Micrometer/Actuator.

`outcome` do comando pertence ao conjunto fechado `success`, `permission_error`, `duplicate_key`, `command_error`, `connection_error`, `pool_error`, `client_error`. São classificações operacionais, não prova de que uma escrita foi aplicada ou desfeita. Um erro de conexão após envio pode deixar resultado incerto; a instrumentação não autoriza repetição automática.

`command` vem de uma enumeração dos comandos conhecidos pela biblioteca, como JSON.GET, HREPLACE, IDX.QUERY e FT.SEARCH. Nomes desconhecidos são agrupados em OTHER. Não são usados nomes de índices, propriedades de entidades, chaves, filtros, valores, caminhos, host/port, usuário, credenciais ou mensagem/classe dinâmica da exceção como tags. Assim, argumentos e comandos desconhecidos não ampliam a cardinalidade das séries.

O health explícito entra como PING nos timers. PINGs internos da validação do Jedis não geram um evento próprio; seu tempo está incluído na aquisição. Comandos internos a um EVAL não são observados individualmente, somente o EVAL enviado. A serialização da entidade antes de chamar o transporte e a duração completa do método de repositório não pertencem ao timer.

## Uso manual e extensões

```java
var metrics = new DatadamMetrics(registry, pool);
var commands = new DatadamCommands(pool, metrics);
var health = new DatadamHealthIndicator(commands);
```

O construtor existente `new DatadamCommands(pool)` mantém observação desativada. Para um transport bean personalizado, passe o observador ao construí-lo; a configuração não envolve automaticamente um `DatadamCommands` fornecido pela aplicação.

Um bean `DatadamCommandObserver` substitui a integração Micrometer padrão. Ele recebe `CommandObservation` com operação/outcome enumerados, durações e indicador de aquisição, sem argumentos nem Throwable. Falhas RuntimeException do observador são ignoradas sem logging e não substituem o retorno nem a exceção original do comando. Erros fatais da JVM não são suprimidos.

A configuração automática representa um único pool `datadamPool` e um registry resolvido pelo Spring. A criação manual de vários instrumentos com os mesmos nomes no mesmo registry pode compartilhar séries/gauges; use integração própria ao instrumentar vários pools. Sem Actuator/Micrometer no classpath, o transporte e os repositórios continuam disponíveis, inclusive se os flags de diagnóstico estiverem ligados.

## Logs, dados sensíveis e exemplo local

A biblioteca não registra argumentos, credenciais ou exceções de comandos nos diagnósticos operacionais. O health e os meters são sanitizados também no caminho de erro. As exceções normais de operações continuam preservando causas e mensagens úteis do servidor, que podem conter chaves/valores; registrá-las ou devolvê-las em uma API é uma decisão da aplicação. Logs DEBUG/TRACE de dependências, exporters, observadores e endpoints adicionais configurados pelo consumidor exigem sua própria política de dados.

A demonstração liga health e métricas e expõe somente esses dois endpoints Actuator, no mesmo bind local `127.0.0.1` da aplicação. Não expõe env, configprops, loggers ou heapdump e mantém detalhes/componentes de health ocultos. Habilitar o recurso na biblioteca não publica endpoints por conta própria; exposição depende do Actuator da aplicação.

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health'
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/metrics/datadam.pool.connections'
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/metrics/datadam.command.duration'
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/metrics/datadam.command.errors'
```

A série de erros aparece após a primeira falha observada; se ainda não houve erros, o endpoint específico pode não encontrá-la. Os testes unitários verificam classpath opcional, opt-in, substituição de beans, limites, instrumentação, cardinalidade e ausência de mensagens sensíveis em health/logs. `LibraryDiagnosticsIntegrationIT` valida Actuator e meters no Docker, comandos JSON/HASH, falhas nativas, credenciais incorretas e esgotamento controlado de um pool próprio com recuperação. Execute `scripts/run-local.ps1 -Restart -IntegrationTests -Background`.

Referências: [Spring Boot 3.5 Actuator](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html), [Micrometer Timers](https://docs.micrometer.io/micrometer/reference/concepts/timers.html) e [Commons Pool](https://commons.apache.org/proper/commons-pool/apidocs/org/apache/commons/pool2/impl/GenericObjectPoolConfig.html).
