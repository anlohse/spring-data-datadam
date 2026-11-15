# Compatibilidade e desempenho — entrega 7.3

Esta entrega define o conjunto efetivamente validado, fixa os insumos de imagem dos testes e publica uma medição reproduzível. Não altera a semântica de consultas ou promete desempenho constante.

## Matriz suportada

O contrato atual é Spring Boot **3.5.16**, Spring Data Commons **3.5.13**, Jedis **6.0.0**, Jackson Databind **2.21.4** e Micrometer **1.15.12**, com as versões gerenciadas pelo POM do projeto. Não substituímos Spring Data Redis: os repositórios usam Commons e o protocolo Datadam diretamente.

| Runtime validado | Plataforma | Unidade | Consumidor Spring Boot + Datadam |
|---|---|---|---|
| Temurin 17.0.21 | Linux x86_64 em Docker | 188 aprovados | Aprovado |
| Temurin 21.0.13 | Linux x86_64 em Docker | 188 aprovados | Aprovado |
| Temurin 25.0.5 | Linux x86_64 em Docker | 188 aprovados | Aprovado |
| OpenJDK 25.0.2 | Windows 11 x64 | 188 aprovados | Suíte completa de integração aprovada |

O build registrado usa Maven 3.8.4, JDK 25.0.2 e `--release 17`. As outras JVMs executam os mesmos artefatos compilados; não são três recompilações com compiladores diferentes. A matriz não declara suporte validado a Boot 4, outras versões de dependências, Java intermediário, Java 26+, ARM ou servidor nativo Windows. A faixa permitida pelo próprio Boot é mais ampla que os pontos efetivamente testados pela biblioteca.

`CompatibilityMatrixIT` copia somente classpaths compilados/dependências para clientes isolados. Em cada imagem JDK executa a suíte inteira da biblioteca pelo JUnit Console e depois inicia um consumidor Boot. O consumidor valida IDs, versão, CRUD JSON/HASH, UNIQUE, IDX derivado com Page, projeções FT, atualização/exclusão versionadas, recusa de escrita obsoleta, TTL, health e métricas. O servidor é novo em cada linha da matriz.

As três linhas são três casos da suíte Maven, mas executam adicionalmente **564 testes unitários dentro dos contêineres**. Desde 8.1, o classpath reúne testes do núcleo e da autoconfiguração; são 140 + 48 casos. A verificação de ausência de Boot/telemetria é feita no classpath Maven isolado do núcleo, e a mesma configuração manual é reexecutada no classpath combinado da matriz. Essas execuções repetidas não são somadas ao número de testes únicos do projeto. Os testes TLS da entrega 7.2 continuam sendo um conjunto separado e usam a JVM JRE fixada no contrato de imagens.

Evidência histórica de 7.3: [compatibilidade em JSON](validation/2026-11-07-compatibility.json). A validação de 8.1 está em [artefatos e consumo independente](validation/2026-11-08-consumption.json). Os logs completos e relatórios JUnit ficam em `datadam-demo/target/compatibility/java17`, `java21` e `java25`.

## Identidade do Datadam e imagens

O servidor validado é o commit C++ **`e2e2c0ae4e9282dd4b1d815e9d893931bd41bd3e`**, que contém HREPLACE e RETURNJSON. É um ponto de desenvolvimento identificável, não uma declaração de compatibilidade com qualquer Datadam nem com Redis comum.

[config/test-images.properties](../config/test-images.properties) é o contrato compartilhado entre scripts e testes. Ele fixa:

- Tag local `datadam:spring-data-e2e2c0ae4e92` e revisão C++ completa.
- Digests das imagens JDK 17/21/25 e da JRE usada no TLS.

`scripts/build-test-image.ps1` usa `git archive` da revisão fixa para montar um contexto em target. O checkout C++ pode estar em outra branch ou ter mudanças locais: elas não entram no build. A imagem recebe o label OCI da revisão. Informe `-Source` ou `DATADAM_SOURCE` para usar outro clone que contenha esse commit.

Antes de iniciar servidores descartáveis, `TestImages` exige esse label e resolve a tag para o ID de conteúdo uma vez por execução. Os contêineres usam o ID resolvido, evitando troca de tag durante o teste. Override por `DATADAM_IMAGE` só passa se mantiver a revisão esperada. O Compose também usa a tag versionada; a referência antiga `datadam:latest` deixou de ser o padrão.

ID observado neste build: `sha256:8f04a23076a8e57be3024dc9f80bf8a1195a16ae65ca7a32ec6a69be8db3f8ea`.

Esse ID identifica o conteúdo executado localmente. Reconstruções em outro momento podem ter outro ID: os pacotes/base do Dockerfile C++ não são todos congelados por este projeto. A revisão de código é fixa, e cada execução registra o ID real; não prometemos builds bit a bit idênticos ou uma imagem Datadam publicada em registry. Não é preciso modificar o checkout C++ para construir o contrato de teste.

```powershell
./scripts/build-test-image.ps1 -Source D:/cpp/workspaces/datadam
./scripts/run-local.ps1 -Restart -IntegrationTests -Background
```

O segundo comando prepara as imagens Java fixadas se necessário. Para inspecionar/iniciar Compose manualmente, use `docker compose --env-file config/test-images.properties up -d --no-build`. Atualizações da revisão ou de imagens exigem editar o contrato, reconstruir e repetir a matriz; não são feitas silenciosamente pelos testes.

## Medição registrada

Artefatos de 07/11/2026, horário America/Sao_Paulo:

- [CSV completo: 104 combinações](performance/2026-11-07-results.csv).
- [Ambiente, parâmetros, revisão e ID da imagem](performance/2026-11-07-environment.json). O timestamp no JSON está em UTC, já em 08/11.

Cliente: Windows 11, OpenJDK 25.0.2, Intel Core i7-14700HX, 28 processadores lógicos disponíveis. Docker Engine 29.9.2, Linux x86_64, VM com cerca de 7,6 GiB. Cada servidor de benchmark foi limitado a **2 CPUs e 1 GiB**, com dois read-threads. Concorrência do cliente: **1**. O host não é dedicado; os números incluem SO, virtualização e transporte loopback.

Foram carregados **1.000 e 10.000 documentos por formato** (JSON e HASH), dez cidades/grupos e payload String com 256 caracteres. Cada formato tem IDX composto `(city, age)`, IDX UNIQUE por email e FT TAG/NUMERIC. As atualizações mantêm esses três índices; não há cenário de gravação sem índices para atribuir isoladamente o custo de UNIQUE.

O filtro seleciona 10% dos documentos: 100 ou 1.000 resultados. A página tem 20 registros. O offset profundo é 80 ou 980. Há dez aquecimentos por cenário e cinquenta amostras, exceto lista sem limite (dez) e contagem global (cinco). Cada cenário roda com validate-connection false e true. Criação de schema e carga inicial estão fora das amostras; o tempo de carga é registrado no JSON.

Resultados selecionados para **10.000 documentos por formato**, com validação de conexão **ativada**, em milissegundos:

| Operação | JSON mediana / p95 | HASH mediana / p95 | Comandos por chamada |
|---|---|---|---|
| IDX somente conteúdo, primeira página | 0,849 / 0,911 | 1,079 / 1,199 | 1 |
| IDX Page, primeira página | 1,756 / 2,175 | 1,856 / 2,062 | 2 |
| IDX Page, offset 980 | 6,063 / 6,704 | 4,286 / 4,654 | 2 |
| IDX COUNT do filtro, 1.000 resultados | 0,854 / 0,950 | 0,717 / 0,823 | 1 |
| FT Page, primeira página | 2,497 / 2,825 | 2,847 / 3,032 | 1 |
| FT Page, offset 980 | 4,012 / 4,330 | 4,381 / 4,755 | 1 |
| Substituição completa com índices | 0,674 / 0,830 | 0,770 / 0,889 | 1 |
| count() global, 10.000 chaves | 30,523 / 31,227 | 28,978 / 29,416 | 40 |

O CSV contém também lookup UNIQUE, projeções IDX/FT, listas sem limite e incrementos nativos JSON.NUMINCRBY/HINCRBY. O incremento HASH é um comando de baixo nível via DatadamCommands, não uma nova API de entidade HASH. `commands_per_operation` conta chamadas do transporte da biblioteca e **não** os PINGs internos de validação do pool.

Esses números são uma referência local, não SLO, capacidade concorrente, teste de carga ou comparação com Redis/outro banco. `serial_operations_per_second` é o inverso da média observada, não throughput máximo. Não há threshold de aprovação por latência. Cinquenta amostras e uma sequência fixa de cenários não isolam efeitos de JIT/cache nem substituem um benchmark estatístico em ambiente de produção.

Nesta medição, Page IDX custa mais que conteúdo sem COUNT, e páginas profundas custam mais que as primeiras. A validação PING acrescenta custo por empréstimo. Projeções reduzem os campos devolvidos, mas não foram universalmente mais rápidas neste payload pequeno; por exemplo, a mediana JSON IDX projetada sem validação foi 1,226 ms contra 1,101 ms para Page completo. Não concluímos que projeção ou atualização parcial sempre terá menor latência.

## Custos e limites que a API preserva

| Recurso | Trabalho envolvido |
|---|---|
| Page IDX | QUERY e COUNT separados; a API programática conta antes de consultar, a derivação consulta antes de contar. Ambos podem observar momentos diferentes sob concorrência. |
| IDX.COUNT | Percorre o intervalo/candidatos do índice que satisfazem os filtros e ACL; não é contador global O(1). A leitura de documentos depende dos filtros/validação necessários. |
| IDX com OFFSET | Precisa descartar os matches anteriores ao offset; o custo não é constante para páginas profundas. |
| FT Page | Um comando devolve total e conteúdo. O servidor avalia candidatos e aplica ACL antes do total; com ordenação, percorre entradas ordenadas para obter offset+limit. Uma chamada não significa custo O(1). |
| List IDX/derivação sem limite | Pode materializar todos os resultados no servidor/cliente. Menos comandos não limita bytes, heap ou tempo de resposta. |
| Projeções | Selecionam campos nativamente e evitam leitura adicional por documento; ainda há filtro, total quando pedido, serialização e materialização do DTO. |
| Validate-connection | Um PING por empréstimo: dois comandos de Page IDX normalmente implicam dois PINGs adicionais. Não é uma repetição de escrita. |
| count() sem argumentos | SCAN por prefixo e tipo, COUNT 256 como indicação, até cursor zero; acumula chaves para contar. Não usa IDX.COUNT nem DBSIZE. |
| findAll() | O mesmo SCAN, seguido de leitura individual JSON.GET/HGETALL; custo e memória crescem com as chaves retornadas. |
| deleteAll() | Enumeração seguida de exclusões sequenciais, com leituras/scripts adicionais em entidades versionadas ou com callbacks; pode haver êxito parcial. |

O SCAN do Datadam aproveita o prefixo literal para delimitar o cursor de metadados; não afirmamos que todo SCAN percorre o banco inteiro. Ainda depende das chaves visitadas no prefixo, filtros de tipo, expiração e ACL, sem snapshot global entre chamadas. No conjunto medido, count() global visitou 10.000 chaves do formato em quarenta chamadas; IDX.COUNT do exemplo seleciona somente 1.000 matches, portanto as duas contagens da tabela não têm a mesma seletividade.

## Reproduzir o workload

Primeiro instale/valide o reactor com o script local. Depois:

```powershell
./scripts/run-performance.ps1 -Documents '1000,10000' -Samples 50 -Warmups 10
```

O workload cria seus próprios servidores/volumes e não usa os dados da aplicação. A classe termina em Benchmark e fica fora da suíte normal `*IT`; o perfil performance separa seus relatórios. O script chama test-compile e Failsafe diretamente, evitando reempacotar um JAR que esteja em uso no Windows.

Saídas: `datadam-demo/target/performance/results.csv`, `environment.json` e `failsafe-reports`. Para registrar outra linha de base, preserve parâmetros/imagens/ambiente e publique os arquivos brutos junto da interpretação. A medição publicada acima é um snapshot, não é sobrescrita automaticamente por execuções posteriores.

Referências: [requisitos do Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [imagens oficiais Temurin](https://github.com/docker-library/docs/tree/master/eclipse-temurin), código `DatadamTemplate`/`SimpleDatadamRepository` e implementação `src/db/index_ops.cpp`, `search_ops.cpp` e `database.cpp` da revisão Datadam fixada.
