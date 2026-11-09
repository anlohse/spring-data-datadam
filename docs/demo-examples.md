# Exemplos executáveis do demo — etapas 0 a 7

Os 16 subtópicos do roadmap têm roteiros que criam dados, executam operações reais e conferem os resultados. `ExampleScenarios` contém os passos de negócio; entidades, repositórios, callbacks e serviços ficam em `datadam-demo/src/main/java/com/anlohse/datadam/demo/examples`. O cenário de falhas usa os testes de segurança existentes e o novo `StockItem`.

## Preparar e executar

Requisitos: Java/Maven e Docker Desktop, conforme o README principal. Na raiz do projeto:

```powershell
# Se o servidor já estiver rodando de uma versão anterior, recarrega a nova ACL:
docker compose restart datadam
.\scripts\run-local.ps1 -Restart -IntegrationTests -Background

# Todos os exemplos até 7.3.1, incluindo o runner isolado de falhas:
.\scripts\run-demo-examples.ps1

# Também é possível escolher um subtópico:
.\scripts\run-demo-examples.ps1 -Scenario 2.1.1
.\scripts\run-demo-examples.ps1 -Scenario 6.2.1
.\scripts\run-demo-examples.ps1 -Scenario 7.2.1
.\scripts\run-demo-workload.ps1 -Count 100
```

`-Restart` substitui somente o processo Java iniciado pelo script. O `docker compose restart datadam` acima é necessário uma vez para recarregar a ACL alterada; o volume permanece. O build verifica a imagem Datadam fixada em `config/test-images.properties`. Os testes não usam Redis nem um banco simulado.

O runner CLI inicia um contexto Spring sem porta HTTP e imprime uma linha `EXAMPLE_RESULT` por roteiro. Um cenário normal retorna `{"scenario":"2.1.1","status":"PASSED","checks":[...]}`. Qualquer resultado inesperado encerra o processo com erro. O runner 7.2.1 mostra os resultados JUnit e uma confirmação ao final. Não é necessário parar a aplicação HTTP para executar os exemplos.

Os scripts usam explicitamente as credenciais de desenvolvimento do Docker local. Para outras credenciais, configure `DATADAM_USERNAME`, `DATADAM_PASSWORD`, `DATADAM_ADMIN_PASSWORD` (bootstrap) e, quando necessário, `DATADAM_SCHEMA_PASSWORD`, `DATADAM_INDEX_OBSERVER_PASSWORD`, `DATADAM_INDEX_MIGRATOR_PASSWORD` e `DATADAM_INDEX_RETIRER_PASSWORD`. O fallback das funções administrativas só é habilitado pelo runner local e somente para `localhost`/`127.0.0.1`.

## Catálogo dos roteiros

Cada comando abaixo usa `run-demo-examples.ps1 -Scenario <número>`. As verificações descritas aparecem na saída e são executadas também em `DemoExamplesIntegrationIT`.

| Item | Classes principais | Entradas e resultado esperado |
|---|---|---|
| 0.1 | `Product`, `ProductRepository`, `ProductController` | Dois produtos de categoria própria, preços 10/20. Busca por SKU, faixa 0–30, Page de tamanho 1 ordenada pelo preço e FT TAG/NUMERIC retornam os registros esperados. Outro cadastro com o mesmo SKU é recusado. |
| 1.1.1 | `TemporaryReservation`, `ReservationRepository` | TTL padrão de 60 segundos, override de 1 segundo e `persist`. Consulta antes da expiração encontra a reserva; depois, entidade, IDX e FT não a retornam. `persist` deixa PTTL = -1. |
| 1.2.1 | `SupportTicket`, `TicketConfiguration`, `ExternalTicket` | O gerador nomeado retorna `TKT-<UUID>`. O ID retornado é usado para ler/atualizar sem gerar outro. Uma importação com ID externo usa `Persistable.isNew()` e recusa a segunda criação com o mesmo ID. |
| 1.3.1 | `Article`, `ArticleCallbacks` | O título `  Spring Data  ` é normalizado antes da validação; auditoria recebe datas/autores. Editar preserva criação; título vazio é recusado sem substituir o registro. Corpo `[fail-after-save]` dispara falha depois da confirmação: leitura pelo ID comprova a escrita. |
| 2.1.1 | `StockItem`, `StockService`, `StockController` | Quantidade 10 passa a 9 com a versão original. Outra edição e uma exclusão com versão antiga falham. A releitura mostra 9; exclusão com a versão atual funciona. |
| 3.1.1 | `UserPreferences`, `PreferencesRepository` | Leituras tipadas de `$.settings.theme`, objeto aninhado e array. JSON null, caminho ausente e documento ausente têm resultados diferentes. SET/DEL preservam a data absoluta de expiração. Escritas parciais em entidades com versão, auditoria ou callbacks são recusadas. |
| 3.2.1 | `ShoppingCart`, `CartRepository` | Incremento/multiplicação, append/insert/pop/trim/length e merge de entrega. Pop de JSON null informa `popped=true`; array vazio informa `false`. Um insert fora dos limites falha depois de um append já aplicado, que permanece. |
| 4.1.1 | `DeviceState`, `DeviceRepository` | HASH com booleanos, números, lista e mapa. Save com campo nulo remove o campo físico; TTL absoluto é preservado quando a atualização não informa outro TTL. Versão antiga é recusada. JSON e HASH coexistem com o mesmo ID em prefixos diferentes. |
| 4.2.1 | `DeviceState`, `UnsupportedDeviceQueries` | IDX compara a região inteira, inclusive vírgula. FT usa aliases `zone`, `seen`, `tag`, TAG por token e separador `;` para labels. `Java` encontra `Java;Spring`, `ava` não. Derivar igualdade de campo dividido em tokens é recusado pelo diagnóstico. |
| 5.1.1 | `ProductSummary`, `DeviceSummary` | Projeção record em JSON mapeia categoria para `department`; interface fechada em HASH mapeia lastSeen e preserva Boolean ausente como null. Não exige materializar a entidade completa. |
| 5.2.1 | `Book`, `BookRepository`, `UnsupportedBookQueries` | Prefixos com `*}|` são literais. Há IgnoreCase ASCII, Between, In, Not/NotIn e AND/OR. Negação numérica exige presença e exclui páginas null. Ordenar por número sozinho não é um filtro de presença. Prefixo vazio, Contains e Not de String são recusados. |
| 6.1.1 | `ExampleAdministration.inspect`, `ProductSchemaMismatch` | Observador lista índices e compara schema/status de produtos, livros e dispositivos. Exibe diferença intencional de campos e índice/motivo escolhido pelos planos locais. Uma ordenação não coberta é recusada. Não lê documentos nem executa DDL. |
| 6.2.1 | `MigrationProduct`, `ProductSchemaV2`, `CategoryUniqueSchema` | Prepara v2, aguarda READY e consulta seus dados sem mudar v1. UNIQUE com duplicatas falha; corrigir dados, descartar explicitamente o alvo FAILED e preparar novamente funciona. Um consumidor explícito passa a v2 antes da retirada de v1. |
| 7.1.1 | `ImportJob`, `ImportService`, `ImportController` | Job conhecido antes da execução, lote de três linhas com um SKU duplicado: estado PARTIAL, 2 processados e 1 rejeitado. Status consultável, reexecução recusada, health UP e métricas de erros/pool presentes. |
| 7.2.1 | `TransportFailuresIT`, `StockItem`, `AclSecurityIT`, `TlsSecurityIT` | Perda antes/depois do envio, timeout, reinício, pool saturado, perda da confirmação de save versionado, ACL e TLS. Contêineres isolados: os casos recusados são resultados esperados, não falhas do roteiro. |
| 7.3.1 | `Product`, `DeviceState`, `ExampleScenarios.workload` | Volume configurável de 1 a 1.000 documentos por formato. Page, Slice, lista limitada, count e projeção concordam sobre conteúdo/totais aplicáveis. O mesmo volume HASH é conferido por Page e projeção. |

## Explorar por HTTP

O catálogo tem API própria. As rotas existentes de clientes, notas e HASH continuam disponíveis.

```powershell
$base = 'http://localhost:8080'
$sku = 'DEMO-' + [guid]::NewGuid().ToString('N')
$inputProduct = @{ sku=$sku; name='Teclado'; category='peripherals'; price=150; tags=@('Java','Spring') }
$product = Invoke-RestMethod "$base/examples/products" -Method Post -ContentType application/json -Body ($inputProduct | ConvertTo-Json)
try {
    Invoke-RestMethod "$base/examples/products/sku/$sku"
    Invoke-RestMethod "$base/examples/products?category=peripherals&minimum=100&maximum=200&page=0&size=10"
    Invoke-RestMethod "$base/examples/products/search?category=peripherals&minimum=100&maximum=200"
    Invoke-RestMethod "$base/examples/products/summaries?category=peripherals&minimum=100&maximum=200"
    # Repetir o POST com o mesmo SKU resulta em HTTP 409.
} finally { Invoke-RestMethod "$base/examples/products/$($product.id)" -Method Delete }
```

As faixas deste catálogo são inclusivas nas duas pontas. Page usa a estrutura `content` e `page.totalElements` do Spring Data. `ProductSummary` retorna somente nome, preço e departamento. O servidor representa NUMERIC como número de ponto flutuante: a escala de BigDecimal não é preservada (por exemplo, 10 pode voltar como 10.0); este exemplo não promete precisão decimal financeira.

```powershell
$item = Invoke-RestMethod "$base/examples/stock" -Method Post -ContentType application/json -Body (@{sku=$sku; quantity=10} | ConvertTo-Json)
$change = @{quantity=9; expectedVersion=$item.version} | ConvertTo-Json
$updated = Invoke-RestMethod "$base/examples/stock/$($item.id)" -Method Put -ContentType application/json -Body $change
# Repetir o PUT com $change ou excluir usando $item.version retorna 409.
Invoke-RestMethod "$base/examples/stock/$($item.id)"
Invoke-RestMethod "$base/examples/stock/$($item.id)?expectedVersion=$($updated.version)" -Method Delete
```

O cliente envia a versão esperada. O serviço passa essa versão ao CAS no servidor, mesmo que tenha acabado de ler uma versão mais nova. Ele não repete nem resolve conflitos automaticamente.

```powershell
$job = Invoke-RestMethod "$base/examples/imports" -Method Post
Invoke-RestMethod "$base/examples/imports/$($job.id)" # PENDING
$rows = @($inputProduct, $inputProduct) | ConvertTo-Json -Depth 5
$finished = Invoke-RestMethod "$base/examples/imports/$($job.id)/run" -Method Post -ContentType application/json -Body $rows
Invoke-RestMethod "$base/examples/imports/$($job.id)" # PARTIAL: uma linha aceita e outra duplicada
Invoke-RestMethod "$base/actuator/health"
Invoke-RestMethod "$base/actuator/metrics/datadam.command.errors"
Invoke-RestMethod "$base/actuator/metrics/datadam.pool.max"
# Após explorar, remova apenas os produtos deste job:
$finished.productIds | ForEach-Object { Invoke-RestMethod "$base/examples/products/$_" -Method Delete }
Invoke-RestMethod "$base/examples/imports/$($job.id)" -Method Delete
```

A importação aceita 1 a 100 produtos novos; usa PENDING → RUNNING → COMPLETED/PARTIAL, com atualização de progresso por linha. A execução HTTP é síncrona; outra conexão pode consultar o ID conhecido enquanto o POST roda. Uma falha de transporte tenta registrar UNCERTAIN e propaga o erro. Essa atualização de status também pode falhar, deixando RUNNING; é necessário reconciliar pelos SKUs de entrada. Nenhuma das duas escritas recebe repetição automática ou transação conjunta. Produtos e status de job são persistidos separadamente.

Para experimentar outro limite de pool apenas nesse processo CLI, preservando a configuração anterior:

```powershell
$previousConfig = $env:SPRING_APPLICATION_JSON
try {
    $env:SPRING_APPLICATION_JSON = '{"datadam":{"pool":{"max-total":4,"max-wait":"1s"},"read-timeout":"2s"}}'
    .\scripts\run-demo-examples.ps1 -Scenario 7.1.1
} finally { $env:SPRING_APPLICATION_JSON = $previousConfig }
```

O valor de `datadam.pool.max` no processo será 4. O Actuator HTTP pertence ao processo iniciado por `run-local.ps1` e mantém a configuração com que ele foi iniciado. Métricas usam nomes de operação e categorias de resultado; não incluem SKU, ID de job, argumentos ou senhas.

## Administração, isolamento e limpeza

O usuário `app` recebeu o prefixo adicional `~demo:examples:*`, sem DDL. `SchemaApplication` prepara os índices com `schema`. O observador usa somente IDX.LIST/IDX.INFO/FT.INFO, o preparador usa CREATE/INFO e o retirador usa DROP/INFO. Seus pools são abertos apenas no CLI administrativo e fechados depois; nenhuma rota HTTP cria/remove índices ou injeta falhas.

Cada execução normal cria IDs/categorias/SKUs próprios e remove por ID somente as suas chaves, inclusive em falhas conhecidas. A limpeza direta com DEL é infraestrutura de fixtures; atualizações/exclusões de estoque usam o controle de versão no serviço. Os índices comuns permanecem para a aplicação. O runner não usa FLUSHDB nem exclusão por prefixo.

O roteiro 6.2.1 exige o prefixo `demo:examples:migration:product:` vazio e ausência dos seus alvos v2. Execute uma instância por vez, como manutenção exclusiva desse espaço. Ele nunca migra o catálogo normal. Ao terminar, remove seus documentos/alvos e restaura o schema v1 vazio. A troca de consumidor e a retirada são chamadas separadas no código: não há alias, troca atômica ou deploy automático. Se o processo for morto fora do finally, revise os dados/índices desse espaço antes de outra execução; não descarte alvos de forma automática.

Expiração da reserva não implementa transação de estoque. As operações parciais do carrinho são comandos separados e não fazem checkout transacional. Falha AfterSave informa confirmação já recebida; falha de transporte pode deixar resultado incerto. As decisões das etapas 9 e 10 permanecem pendentes.

## Falhas e TLS — 7.2.1

```powershell
.\scripts\run-example-failures.ps1
```

O script executa 15 verificações com a imagem nativa identificada por revisão/ID e a JVM TLS fixada por digest. `StockItem` começa com quantidade 10 e versão 0; o proxy deixa o servidor aplicar quantidade 9/versão 1, mas perde a resposta. A cópia local continua na versão 0. Uma leitura direta reconcilia o estado; repetir a cópia obsoleta causa conflito. A biblioteca não repete a escrita de transporte automaticamente.

Outros casos verificam reader/writer/schema, recusa de comandos/DDL, certificados confiáveis, CA desconhecida, hostname incorreto e certificado expirado. Cliente e servidor TLS ficam no Docker, preservando a cadeia mesmo com inspeção HTTPS no Windows. Relatórios ficam em `datadam-demo/target/example-failures/failsafe-reports`, separados da suíte completa. Consulte também [falhas e segurança](failures-and-security.md).

## Trabalho executado e medição — 7.3.1

```powershell
.\scripts\run-demo-workload.ps1 -Count 100
# Medição isolada com warmup/amostras, imagens fixadas e CSV/ambiente:
.\scripts\run-demo-workload.ps1 -Count 100 -Benchmark
# Ajustar volumes e amostragem do benchmark diretamente:
.\scripts\run-performance.ps1 -Documents '1000,2000' -Samples 10 -Warmups 3
```

O primeiro roteiro compara sem fazer uma promessa de latência. Semeia o mesmo número de produtos JSON e dispositivos HASH, com Page de até 10 registros. O benchmark opcional mede operações nativas isoladas, com formatos/operações comparáveis; seus documentos por padrão são 1.000/10.000, independentes do `Count` didático. Não confundir uma consulta da aplicação com um teste de capacidade do servidor.

| Alternativa didática | Trabalho lógico no servidor/aplicação |
|---|---|
| Product/Device Page completa | IDX.QUERY com limite + IDX.COUNT, leitura/materialização dos documentos devolvidos |
| Product Slice | IDX.QUERY com tamanho + 1 para detectar próxima página; sem COUNT |
| Product lista com Pageable | IDX.QUERY com limite; sem COUNT nem total |
| Product count | IDX.COUNT; sem documentos devolvidos |
| Product/Device projeção Page | IDX.QUERY com seleção das propriedades + IDX.COUNT; sem entidade completa |
| FT do catálogo | FT.SEARCH TAG/NUMERIC; total fornecido pelo servidor, com limite de conteúdo |

Cada comando pode adicionar validação PING na aquisição da conexão, conforme configuração do pool. O benchmark inclui aquisição e validação nas latências medidas e publica ambiente/volume em `datadam-demo/target/performance/environment.json` e amostras em `results.csv`. Compare somente cenários equivalentes; warmup, JIT, host, Docker e inspeção de conexão afetam os números. A API global `findAll`/`count()` do repositório faz SCAN do prefixo; o roteiro usa consultas indexadas e volume limitado. Consulte [compatibilidade e desempenho](compatibility-and-performance.md) para a revisão nativa, digests, custos e medições anteriores.

## Validação automatizada

`DemoExamplesIntegrationIT` executa 15 roteiros de negócio/administração e testa as três APIs HTTP novas. O cenário 7.2.1 corresponde aos 15 casos de falhas/ACL/TLS da suíte existente, agora usando `StockItem` no save com resposta perdida. A suíte completa continua sendo executada por `run-local.ps1 -IntegrationTests`; ela também cobre a matriz Java 17/21/25 e recuperação do backfill após reinício.

Execução de **08/11/2026**: 394 testes aprovados (187 unitários + 207 de integração), incluindo os 18 novos testes do demo, e mais 561 reexecuções unitárias na matriz. Os 16 exemplos passaram pelos runners; o workload adicional usou 100 documentos por formato. A API local respondeu UP, o cadastro/leitura HTTP foi conferido e a limpeza terminou com zero chaves em `demo:examples:*`. Evidência: [resultados dos exemplos](validation/2026-11-08-demo-examples.json).

A reprodução curta do benchmark usou 1.000 documentos por formato, 52 combinações, 3 warmups e 10 amostras. Os [resultados CSV](performance/2026-11-08-examples-results.csv) e o [ambiente JSON](performance/2026-11-08-examples-environment.json) registram essa execução; servem para conferir a reprodução e os comandos executados, sem sustentar uma conclusão estatística sobre latência ou capacidade.
