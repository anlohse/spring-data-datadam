# Roadmap — Spring Data Datadam

Este documento acompanha a evolução da biblioteca, da base funcional atual até uma versão adequada para adoção em outros projetos. A ordem busca entregar utilidade em cada etapa, sem depender de uma reescrita futura.

**Estado atual:** CRUD, consultas e projeções nativas IDX/FT em JSON e HASH, com TTL, IDs, auditoria, callbacks e controle de versão, mais operações JSON com incrementos, arrays e merge. Há diagnóstico de consultas/pool e migração explícita de índices. A matriz valida Java 17/21/25 com Boot 3.5.16 e Data Commons 3.5.13; imagens são identificadas por revisão/digests e há resultados de desempenho publicados. São 394 testes Java aprovados: 187 unitários e 207 de integração com Datadam real no Docker, incluindo 18 verificações novas dos exemplos. A matriz reexecuta também os 187 unitários em cada JVM isolada (561 execuções adicionais). O servidor com HREPLACE/RETURNJSON passou por 186 testes nativos e integração/persistência C++ na entrega 5.1; as entregas posteriores até 7.3 não alteraram o código C++.

**Próxima entrega:** **8.1 — Projeto pronto para consumo**. A implementação dessa entrega ainda não começou.

**Trilha adicional de exemplos:** concluídos os 16 subtópicos de **0.1 até 7.3.1**, com modelos próprios, roteiros CLI/HTTP e validação no Docker. Consulte [os exemplos executáveis](docs/demo-examples.md) e [a evidência da execução](docs/validation/2026-11-08-demo-examples.json). O próximo exemplo, **8.1.1**, depende da entrega 8.1; os exemplos das etapas 8–10 continuam pendentes.

## Como vamos trabalhar

- Cada item numerado representa uma entrega pequena que podemos implementar e revisar separadamente.
- Seguiremos a ordem abaixo. Ao concluir um item, atualizaremos seus checks e o campo “Próxima entrega”.
- Um item só recebe `[x]` depois de implementação, testes adequados, documentação e exemplo de uso, quando aplicável.
- Recursos já existentes não precisam ser refeitos; serão ampliados mantendo os contratos atuais.
- Mudanças incompatíveis precisam de uma orientação de migração. Nenhuma etapa autoriza apagar documentos, remover índices em uso ou publicar artefatos remotamente de forma automática.
- Investigações devem resultar em uma decisão documentada: implementar, reduzir o escopo ou adiar com uma razão concreta. Não vamos marcar uma funcionalidade como entregue só porque sua viabilidade foi investigada.

## Como vamos ampliar os exemplos do demo

- Cada entrega recebe um subtópico próprio de exemplo, com um domínio e um roteiro de uso. Podemos trabalhar nele pelo número, como **1.1.1** ou **5.2.1**.
- Os exemplos existentes são a base de cada subtópico. Seus arquivos e rotas continuam disponíveis; os checks novos indicam cenários adicionais ainda pendentes.
- Novos domínios serão organizados gradualmente em pacotes de exemplos, como catálogo, reservas, estoque, preferências, carrinho, livros e dispositivos. Entidades, repositórios, DTOs, callbacks e serviços serão separados conforme a necessidade do cenário.
- Para concluir um exemplo, entregar instruções de execução, entradas e saídas esperadas, um caminho de sucesso, um limite ou falha relevante e validação de integração do roteiro principal. HTTP, CLI ou script serão escolhidos conforme o recurso.
- Incluir a preparação do schema, os prefixos e as permissões locais necessárias. A aplicação continua sem DDL; os roteiros criam e limpam apenas seus próprios dados.
- Reutilizar domínios quando isso mostrar uma evolução útil: `Product` no catálogo, projeções e migrações; `DeviceState` no HASH e nas consultas. Os domínios novos não recebem implicitamente uma transação conjunta.
- Os exemplos de transações e reatividade dependem das decisões e implementações das etapas 9 e 10. Seus checks permanecem pendentes enquanto o suporte correspondente não existir.

## Visão da evolução

| Etapa | Objetivo | Resultado esperado |
|---|---|---|
| 0 | Consolidar a base existente | CRUD, índices e buscas nativas funcionando |
| 1 | Completar o ciclo das entidades | Expiração, IDs e auditoria |
| 2 | Proteger atualizações concorrentes | Controle de versão com `@Version` |
| 3 | Trabalhar com partes de documentos | Operações JSON sem substituir o documento pela aplicação |
| 4 | Oferecer outro formato de persistência | Repositórios para HASH e JSON |
| 5 | Ampliar as consultas | Projeções e operadores compatíveis com o servidor |
| 6 | Evoluir índices com segurança | Inspeção e migrações explícitas de schema |
| 7 | Preparar a operação contínua | Diagnóstico, segurança e testes de falhas |
| 8 | Distribuir uma versão estável | Build reproduzível, CI e artefatos consumíveis |
| 9 | Avaliar transações Spring | Contrato explícito sobre atomicidade e falhas |
| 10 | Oferecer acesso reativo | Transporte não bloqueante e repositórios reativos |

As etapas 9 e 10 são evoluções posteriores. A primeira versão estável não precisa esperar por elas.

## 0. Base existente — concluída

- [x] Projeto Maven com biblioteca e aplicação Spring Boot separadas.
- [x] Configuração automática de conexão, pool, comandos e template.
- [x] Entidades JSON com identificação e prefixo próprios.
- [x] Repositórios Spring Data com CRUD.
- [x] Índices compostos IDX, faixas, ordem e UNIQUE nativos.
- [x] Buscas FT sobre TAG/NUMERIC com parâmetros vinculados.
- [x] Consultas anotadas e derivação de nomes `findBy`, `countBy` e `existsBy` no subconjunto documentado.
- [x] Retornos Optional, List, Page e Slice, com limites e ordenação nativos.
- [x] Criação idempotente de índices, validação de schema e acompanhamento de backfill.
- [x] Separação entre credenciais de aplicação e administração dos índices.
- [x] Datadam local em Docker, com ACL, volume persistente e porta restrita ao host local.
- [x] Aplicação HTTP de exemplo e scripts de execução.
- [x] Testes de integração com UNIQUE concorrente, ACL, TTL via comando, consultas e HTTP.
- [x] Verificação manual de persistência e recuperação de conexão após reinício do container.
- [x] Instalação da biblioteca no repositório Maven local.

**Limite original desta base:** TTL estava disponível apenas por comando. A integração declarativa foi entregue no item 1.1 abaixo. Publicação Maven remota ainda não foi feita.

### 0.1. Exemplo no demo: catálogo de produtos

**Base existente:** `Customer`, `CustomerRepository`, `/customers` e os scripts de dados de exemplo.

- [x] Criar `Product` e seu repositório/controller, com SKU UNIQUE, índice composto por categoria/preço e busca FT por TAG/NUMERIC.
- [x] Adicionar roteiro de cadastro, consulta por SKU, faixa de preço, ordenação/paginação e recusa de SKU duplicado, com documentação e validação de integração.

## 1. Ciclo das entidades

### 1.1. TTL de entidades — concluído

**Benefício:** permitir expirar uma entidade sem a aplicação ter de emitir comandos adicionais manualmente.

- [x] Definir a API de TTL: valor padrão por tipo e, se informado, valor por entidade com unidade explícita.
- [x] Definir os comportamentos para criação, atualização, ausência de TTL, remoção da expiração e valores inválidos.
- [x] Implementar a gravação com expiração em script isolado, validar antes de gravar e verificar/documentar os limites de atomicidade do Datadam.
- [x] Testar criação, atualização, preservação/alteração da expiração e falha por UNIQUE; uma gravação recusada não pode modificar o TTL existente.
- [x] Confirmar que documentos expirados deixam de aparecer tanto em IDX quanto em FT.
- [x] Adicionar exemplo de entidade temporária e documentação.

**Concluído quando:** a política de expiração estiver explícita e os testes comprovarem o comportamento do documento e de seus índices.

**Entrega:** `@DatadamDocument(timeToLive = ...)`, `@DatadamTimeToLive`, `persist(id)` e exemplo HTTP `/temporary-notes`. Há 8 novos testes unitários e 11 novos testes de integração, além da suíte anterior. Consulte [TTL de entidades](docs/entity-ttl.md).

**Decisão de atomicidade:** os testes comprovam isolamento da gravação/TTL e preservação do documento e da data absoluta de expiração em recusas por UNIQUE, ACL e valores inválidos. O servidor não oferece rollback geral de scripts, e uma escrita UNIQUE pode forçar commit antecipado. A garantia de documento + TTL em uma única gravação durável após crash não está incluída nesta entrega; exigiria uma evolução explícita do servidor C++. Esse limite está documentado e não é considerado resolvido pela biblioteca.

#### 1.1.1. Exemplo no demo: reservas temporárias

**Base existente:** `TemporaryNote` e `/temporary-notes`.

- [x] Criar `TemporaryReservation` para demonstrar TTL padrão, override por entidade e remoção explícita da expiração com `persist`.
- [x] Adicionar roteiro que observe TTL, consulte a reserva antes/depois da expiração e confira sua saída de IDX/FT; explicar que a expiração não implementa uma transação de reserva de estoque.

### 1.2. Geração de IDs e identificação de entidades novas — concluído

- [x] Definir quando uma entidade é considerada nova, preservando o suporte a IDs atribuídos pela aplicação.
- [x] Oferecer geração opcional de IDs e uma extensão para estratégias próprias.
- [x] Suportar classes mutáveis e records, retornando a entidade com o ID efetivamente persistido.
- [x] Testar geração, preservação de IDs existentes e comportamento em falhas de gravação.
- [x] Documentar os tipos de ID suportados e atualizar o exemplo.

**Concluído quando:** o usuário puder escolher entre informar o ID ou recebê-lo no retorno de `save`, com comportamento previsível em novas gravações e atualizações.

**Entrega em 05/11/2026:** `@DatadamGeneratedId`, UUID padrão para String/UUID, estratégias nomeadas `DatadamIdGenerator` e detecção local de estado com suporte a `Persistable`. IDs gerados usam criação NX, inclusive com TTL, para recusar colisões sem sobrescrever documentos. Records retornam uma cópia; classes mutáveis recebem o ID somente após sucesso. O exemplo `/temporary-notes` aceita POST sem ID. Consulte [IDs e identificação de entidades novas](docs/entity-identity.md).

**Validação:** 17 novos testes unitários e 12 novos testes de integração; 81 testes aprovados no total. Inclui UNIQUE, ACL, falha de transporte simulada, colisão sem alteração do TTL, disputa simultânea pelo mesmo ID, geradores personalizados, saveAll e HTTP. A API local também foi conferida por POST sem ID, leitura pelo ID retornado e consulta de TTL.

**Decisões de contrato:** IDs atribuídos em entidades comuns mantêm save como criação/substituição. `Persistable.isNew() == true` solicita criação exclusiva. A detecção não consulta o banco, e flags de Persistable não são alteradas automaticamente. Falha de transporte pode deixar resultado incerto; não há repetição automática. `saveAll` permanece sequencial com possibilidade de êxito parcial, conforme documentado.

#### 1.2.1. Exemplo no demo: abertura de chamados

**Base existente:** geração de IDs no cadastro de `TemporaryNote` e suporte a estratégias próprias na biblioteca.

- [x] Criar `SupportTicket` com geração de ID e um cenário separado de ID atribuído externamente; demonstrar uma estratégia nomeada de geração sem substituir o padrão dos demais exemplos.
- [x] Adicionar roteiro que use o ID retornado por `save` para consultar/atualizar, preserve IDs existentes e demonstre recusa de colisão em criação exclusiva.

### 1.3. Auditoria e callbacks — concluído

- [x] Introduzir callbacks de persistência e leitura com ordem de execução documentada.
- [x] Integrar datas de criação/alteração e autor de criação/alteração com as abstrações do Spring Data.
- [x] Usar a detecção de entidade nova da etapa 1.2 para preservar os dados de criação.
- [x] Testar classes mutáveis, records, relógio controlável e callbacks que recusam uma operação.
- [x] Adicionar exemplo de auditoria sem exigir que a aplicação use esse recurso.

**Concluído quando:** salvar uma entidade puder preencher auditoria e executar callbacks sem alterar indevidamente sua identidade ou seus dados de criação.

**Entrega em 05/11/2026:** `@EnableDatadamAuditing` com anotações Spring Data, AuditorAware e DateTimeProvider/Clock; callbacks BeforeConvert, BeforeSave, AfterSave e AfterConvert, ordenados e filtrados por tipo pelo Spring. O estado novo é capturado antes da geração de ID. A auditoria é opcional, suporta classes mutáveis e records e preserva os dados de criação em atualizações. Consulte [auditoria e callbacks](docs/auditing-and-callbacks.md).

**Validação:** 18 novos testes unitários e 9 novos testes de integração; 108 testes aprovados no total. Cobertura de relógio/autores controlados, criação/alteração, ordem, normalização, proteção de identidade e auditoria, recusas, UNIQUE, TTL, materialização por CRUD/IDX/FT e API HTTP. O exemplo `/audited-notes` também foi conferido por POST/PUT na aplicação local.

**Decisões de contrato:** auditoria por anotações na raiz da entidade; metadados embutidos e interface Auditable ficam fora deste escopo. A biblioteca preserva os dados de criação recebidos e não busca o estado anterior para autenticar esses valores; o exemplo HTTP usa DTOs de negócio e carrega a entidade antes de atualizar. Falha AfterSave é distinguida por AfterSaveCallbackException porque a escrita já foi confirmada. Efeitos executados por callbacks e alterações diretas em memória não recebem rollback automático. O estado dos itens anteriores e seus limites de transporte/durabilidade são mantidos.

#### 1.3.1. Exemplo no demo: publicação de artigos

**Base existente:** `AuditedNote`, `DemoAuditingConfiguration`, callbacks e `/audited-notes`.

- [x] Criar `Article` com datas/autores de criação e alteração, callback de normalização e uma validação de conteúdo que possa recusar a gravação.
- [x] Adicionar roteiro de criação/edição que preserve a auditoria de criação e mostre a ordem dos callbacks, a recusa antes da escrita e o tratamento de falha após confirmação.

## 2. Concorrência e controle de versão

### 2.1. Optimistic locking com `@Version` — concluído

**Benefício:** impedir que uma atualização sobrescreva silenciosamente uma alteração feita por outra conexão.

- [x] Definir a versão inicial e as regras de incremento para criação, atualização e exclusão.
- [x] Validar e implementar no Datadam uma comparação de versão e gravação atômicas; comparar no cliente antes de salvar não é suficiente.
- [x] Traduzir conflito de versão para `OptimisticLockingFailureException`.
- [x] Integrar versão com TTL, geração de IDs, auditoria e callbacks.
- [x] Testar duas conexões concorrentes, entidade removida/expirada e falha por UNIQUE sem incremento de versão indevido.
- [x] Documentar a diferença entre salvar uma entidade versionada e executar comandos nativos diretamente.

**Concluído quando:** duas atualizações da mesma versão não puderem ter sucesso simultaneamente, e a operação recusada preservar documento, TTL e índices.

**Entrega em 05/11/2026:** suporte a `@Version` do Spring Data em Long/Integer e primitivos, com comparação e gravação em EVAL isolado no servidor. Wrappers começam em null e são gravados como zero; primitivos começam em zero e são gravados como um. Cada atualização incrementa o contador; exclusões verificam a versão sem incrementá-la. Conteúdo e nova versão pertencem ao mesmo JSON.SET, inclusive sob UNIQUE. Consulte [concorrência com @Version](docs/optimistic-locking.md).

**Validação:** 14 novos testes unitários e 15 novos testes de integração; 137 testes aprovados no total. Inclui disputa por duas conexões de pools independentes, recusa de criação duplicada, entidade removida/expirada, precisão acima de 2^53 e Long.MAX_VALUE, TTL absoluto, IDX/FT, UNIQUE, ACL, classes mutáveis, records, lotes, callbacks e HTTP. O exemplo `/versioned-notes` foi conferido também na aplicação local: criação em zero, atualização para um, PUT obsoleto com HTTP 409 e exclusão com a versão atual.

**Decisões de contrato:** a versão determina o estado novo mesmo com ID atribuído; Persistable deve concordar. deleteById lê e exclui com a versão corrente; para exigir a versão vista pelo cliente, use delete(entity). Comandos diretos não recebem incremento/checagem automática, documentos sem versão exigem migração explícita e reutilizar o mesmo ID após exclusão/expiração pode produzir ABA. Os limites anteriores de transporte incerto, lotes sequenciais e durabilidade documento/TTL permanecem documentados.

#### 2.1.1. Exemplo no demo: atualização concorrente de estoque

**Base existente:** `VersionedNote`, `/versioned-notes` e respostas HTTP de conflito.

- [x] Criar `StockItem` com `@Version` e um fluxo de alteração de quantidade que receba a versão observada pelo consumidor.
- [x] Adicionar roteiro com dois consumidores lendo a mesma versão, uma atualização aceita e outra recusada, seguido de nova leitura e reconciliação explícita; incluir exclusão com versão obsoleta.

## 3. Atualizações parciais JSON

### 3.1. API de caminhos e alterações simples — concluído

- [x] Criar uma API tipada para ler, definir e remover valores por JSONPath.
- [x] Tratar corretamente as respostas diferentes de JSONPath e dos caminhos legados.
- [x] Definir a interação dessas alterações com `@Version` e auditoria; não permitir que uma API da biblioteca contorne silenciosamente essas políticas.
- [x] Testar caminhos ausentes, valores null, tipos incompatíveis, TTL e atualização dos índices.
- [x] Adicionar exemplos na aplicação de teste.

**Entrega em 05/11/2026:** `template.json(Entity.class)` com `JsonPath<T>`, TypeReference, read/set/remove e JsonReadResult. Leituras normalizam JSONPath e caminhos legados sem achatar arrays do documento e distinguem chave ausente, nenhum match e JSON null. Escritas singulares não raiz enviam somente o caminho/valor em um comando nativo e protegem ID/TTL, inclusive nomes Jackson e aliases. Consulte [operações por JSONPath](docs/json-path-operations.md).

**Validação:** 12 novos testes unitários e 12 novos testes de integração; 161 testes aprovados no total. Cobertura de caminhos e envelopes, coleções, null, tipos incompatíveis, ausência, TTL absoluto, IDX/FT, UNIQUE, ACL, expiração, políticas de entidades e HTTP. O exemplo `/customers/{id}/json` foi conferido também na aplicação local com leitura de array, alteração de cidade e remoção de nome, preservando os demais campos.

**Decisões de contrato:** alterações parciais estão disponíveis para documentos simples; entidades com @Version, auditoria ou callbacks de gravação aplicáveis são recusadas antes do transporte, com orientação para usar save. O suporte parcial a essas políticas exige um protocolo adicional e não foi entregue neste item. Leituras podem usar seletores múltiplos, mas escritas aceitam somente membros/índices singulares, preservam a expiração sem reiniciar TTL e não criam chaves ausentes. O tipo declarado valida os valores da operação, sem validação integral do schema da entidade. Cada alteração é um comando; sequências não são transações e o servidor ainda reprocessa o documento inteiro internamente.

#### 3.1.1. Exemplo no demo: preferências do usuário

**Base existente:** `CustomerJsonController` e operações em `/customers/{id}/json`.

- [x] Criar `UserPreferences` com objetos aninhados e demonstrar leitura, definição e remoção por `JsonPath<T>`, incluindo caminhos ausentes, JSON null e arrays.
- [x] Adicionar roteiro que preserve campos não alterados e TTL e mostre a recusa de alterações parciais quando a entidade exige versão, auditoria ou callbacks de gravação.

### 3.2. Incrementos, arrays e merge — concluído

- [x] Expor incrementos numéricos, operações de arrays e merge de objetos com contratos de retorno claros.
- [x] Definir os limites de atomicidade: uma sequência de comandos não deve ser apresentada como uma única operação atômica.
- [x] Testar concorrência, falhas por UNIQUE e manutenção dos índices após cada tipo de alteração.
- [x] Documentar o custo do servidor: uma alteração parcial JSON pode continuar exigindo reprocessar o documento inteiro no Datadam.

**Concluído quando:** a aplicação puder alterar partes de um documento sem montar sua substituição completa e sem perder as garantias documentadas da entidade.

**Entrega em 05/11/2026:** increment/multiply; arrayAppend, arrayInsert, arrayPop, arrayTrim, arrayLength e arrayIndex; merge de objetos RFC 7386. Retornos normalizados para JSONPath/legado, promoção numérica preservada e JsonPopResult distingue JSON null removido de nenhum elemento removido. JsonMutationDecodingException identifica falhas ao interpretar o resultado de uma mutação já enviada. Consulte [incrementos, arrays e merge](docs/json-native-mutations.md).

**Validação:** 10 novos testes unitários e 15 novos testes de integração; 186 testes aprovados no total. Inclui incrementos/appends/merges concorrentes por pools independentes, UNIQUE em número, array, posição de array e membro de merge, preservação de TTL absoluto e IDX/FT, tipos, null, limites de índices, overflow, erros numéricos, expiração, ACL e HTTP. O exemplo local foi conferido com incremento de idade, append/pop de tags e merge que remove um membro null preservando os demais.

**Decisões de contrato:** mantidas as proteções de 3.1: escritas singulares não raiz, sem alterar ID/TTL e sem contornar versão, auditoria ou callbacks. Incrementos retornam Number porque overflow int64 pode promover a float; pop distingue nil RESP de JSON null. Merge aceita patch de objeto, combina Maps aninhados e preserva nulls de remoção mesmo com mapper NON_NULL; OK não prova que houve matches. Cada alteração é um comando atômico no servidor, mas uma sequência não é transação e sucessos anteriores permanecem após falha. O servidor continua reprocessando o documento inteiro, e falhas de transporte/conversão após envio não autorizam repetição automática.

#### 3.2.1. Exemplo no demo: carrinho de compras

**Base existente:** operações numéricas, arrays e merge já demonstradas sobre `Customer`.

- [x] Criar `ShoppingCart`, sem políticas incompatíveis com a API parcial, com itens em array, quantidades numéricas e opções de entrega em objeto; expor incrementos, append/insert/pop/trim e merge.
- [x] Adicionar roteiro que diferencie null removido de ausência, use null para remover um membro via merge e mostre que uma sequência com falha pode manter alterações anteriores.

## 4. Persistência em HASH

### 4.1. Mapeamento e CRUD — concluído

- [x] Permitir escolher explicitamente HASH ou JSON no mapeamento da entidade.
- [x] Definir conversão de números, booleanos, enums, campos ausentes e null; deixar explícito como tratar estruturas aninhadas e coleções.
- [x] Implementar CRUD HASH com substituição correta dos campos removidos, preservando TTL e respeitando UNIQUE.
- [x] Integrar IDs, auditoria e `@Version`, verificando as garantias atômicas do formato HASH.
- [x] Testar isolamento de prefixos e coexistência de entidades HASH e JSON.

**Entrega em 05/11/2026:** `@DatadamDocument(storage = DatadamStorage.HASH)`, com JSON como padrão; CRUD/listagem/count com formato próprio e o mesmo ciclo de ID, auditoria, callbacks, TTL e versão. Escalares viram campos textuais, null omite/remove campos, objetos/coleções viram JSON dentro de cada campo HASH. Inclui exemplo HTTP `/hash-notes`. Consulte [entidades HASH](docs/hash-entities.md).

**Validação:** 9 novos testes unitários e 14 novos testes de integração; 209 testes Java aprovados. Cobertura de tipos, aliases, precisão decimal/temporal, bytes/arrays, Duration TTL, substituição integral, campos removidos, estado final UNIQUE composto, preservação integral em recusa, concorrência por pools independentes, versão int64, mutáveis/records, expiração, ACL, coexistência/WRONGTYPE e HTTP. O servidor passou por 184 testes nativos e pela suíte de integração/persistência, incluindo recusa UNIQUE dentro de Lua e preservação após reabrir o banco.

**Dependência de servidor:** a entrega acrescenta HREPLACE ao Datadam C++, para substituir campos e versão na mesma transação nativa, preservando TTL e validando o estado final UNIQUE. A investigação também encontrou e corrigiu a representação de undo que confundia ausência de entrada no batch com tombstone e podia apagar campos após uma recusa. A imagem Docker local foi reconstruída; o patch e as instruções estão em [server-patches](server-patches/README.md). HASH não possui fallback HSET/HDEL em servidores antigos. JSON mantém os comandos anteriores.

**Decisões de contrato:** prefixos exclusivos, nenhuma conversão automática JSON→HASH, propriedades Object na raiz recusadas por ambiguidade e estruturas aninhadas não achatadas. Mantidos os limites de transporte, lotes sequenciais, ABA e durabilidade conjunta de conteúdo/TTL após crash com UNIQUE. Schema e consultas HASH na biblioteca são recusados explicitamente até 4.2; índices HASH nos testes são fixtures administrativas, sem antecipar essa etapa.

#### 4.1.1. Exemplo no demo: estado de dispositivos

**Base existente:** `HashNote`, callbacks e `/hash-notes`.

- [x] Criar `DeviceState` em HASH, com escalares, lista de capacidades e objeto de configuração armazenados em campos próprios; demonstrar substituição completa e remoção de campos null.
- [x] Adicionar roteiro de criação/leitura/atualização/exclusão, preservação de TTL e controle de versão, com um objeto JSON de outro domínio coexistindo em prefixo separado.

### 4.2. Índices e consultas sobre HASH — concluído

- [x] Gerar e validar definições `ON HASH` e seus nomes/aliases de campos.
- [x] Adaptar consultas anotadas, derivadas e programáticas para o formato HASH.
- [x] Testar diferenças de TAG, separadores, valores ausentes e NUMERIC entre HASH e JSON.
- [x] Adicionar à aplicação um exemplo HASH equivalente ao exemplo JSON.

**Concluído quando:** o mesmo conjunto de garantias públicas de CRUD e consultas funcionar nos dois formatos, com suas diferenças documentadas.

**Entrega em 05/11/2026:** schema ON HASH com criação idempotente, backfill e validação de formato, campos, aliases, caixa e separadores. IDX/FT materializam entidades a partir da própria resposta, sem HGETALL adicional; consultas declaradas, programáticas e derivadas usam os caminhos HASH e os aliases nativos. IndexField.separator expõe separadores TAG para os dois formatos. Inclui HashCustomer/HashCustomerRepository e HTTP `/hash-customers`, com faixas, ordem, paginação, buscas e labels. Consulte [índices e consultas HASH](docs/hash-indexes-and-queries.md).

**Validação:** 9 novos testes unitários e 16 novos testes de integração; 234 testes Java aprovados. Cobertura de DDL e drift sem remoção de índices, aliases/nome Jackson, faixa/ordem/totais, Page/Slice/Top, count/exists, IDX/FT/derivação, separadores/caixa, listas JSON dentro do HASH, campos ausentes/NUMERIC inválido, atualização/remoção de campos, UNIQUE, TTL/expiração, callbacks e ACL de conteúdo/total. O leitor de teste não possui HGETALL e ainda assim recebe entidades completas diretamente das consultas. As rotas IDX/derivada/FT/labels foram conferidas na aplicação local.

**Decisões de contrato:** IDX HASH não aceita AS, então alias deve ser igual ao campo armazenado; FT aceita aliases. FT HASH divide TAG por vírgula por padrão; IDX sem separador mantém igualdade do campo completo. Derivação recusa TAGs divididos em tokens para igualdade/ordenação escalar, em vez de alterar a semântica ou pós-filtrar páginas; pode selecionar um IDX sem separador ou um plano FT numérico compatível. Objetos/coleções HASH não são achatados nem indexados como arrays JSON automaticamente. O patch C++ 4.1 permanece necessário para a escrita, sem novas alterações de servidor nesta etapa; projeções parciais ficam para 5.1.

#### 4.2.1. Exemplo no demo: busca de dispositivos por região e atividade

**Base existente:** `HashCustomer`, `/hash-customers` e consultas IDX/FT sobre HASH.

- [x] Ampliar `DeviceState` com região, instante de atividade em campo numérico explícito e labels; demonstrar IDX composto, FT com alias e separador TAG configurado.
- [x] Adicionar roteiro de faixas, páginas e totais e comparar busca por token com igualdade do campo inteiro; mostrar a recusa de derivação escalar sobre TAG dividido.

## 5. Consultas mais completas

### 5.1. Projeções — concluído

- [x] Implementar seleção de campos usando os retornos nativos de IDX/FT.
- [x] Mapear o resultado para DTOs e/ou interfaces de projeção, definindo explicitamente o primeiro conjunto suportado.
- [x] Preservar aliases, conversões, campos ausentes, ordem e paginação sem consultas extras por documento.
- [x] Testar HASH e JSON sem apresentar uma entidade incompleta como se estivesse totalmente carregada.

**Entrega em 06/11/2026:** ProjectionSpec, ProjectionProperty e seleção nativa em projectQuery/projectSearch; consultas @IdxQuery/@FtQuery também inferem records e interfaces fechadas pelos retornos List/Page. Tipos projetados são distintos das entidades, com propriedades raiz anuláveis, nomes Jackson/aliases e conversões tipadas. Inclui `/customer-projections` para JSON/HASH por IDX/FT. Consulte [projeções](docs/projections.md).

**Validação:** 12 novos testes unitários e 16 novos testes de integração; 262 testes Java aprovados. Cobertura de campos solicitados apenas, records/interfaces, bindings, null versus string literal null, campos ausentes, conversões/containers, BigDecimal/int64, ordenação, List/Page/total, queries declaradas/programáticas, ACL sem JSON.GET/HGETALL, expiração, callbacks não executados e conteúdo/TTL inalterados, além de HTTP. O servidor passou por 186 testes nativos e pela integração/persistência, incluindo compatibilidade do RETURN existente e tipos/aliases em RETURNJSON.

**Dependência de servidor:** projeções JSON usam RETURNJSON, opção aditiva implementada no Datadam C++ para preservar aspas de strings, nulls e estruturas sem uma leitura extra por documento. O RETURN anterior fica inalterado; HASH usa RETURN existente. O patch reproduzível está em server-patches/typed-return.patch e a imagem local está atualizada. Não há fallback para carregar a entidade inteira em servidores antigos.

**Decisões de contrato:** suporte inicial a records e interfaces fechadas com getters, propriedades raiz com tipos anuláveis e retornos List/Page em consultas anotadas ou programáticas. Recusados alvos de entidade, primitivos/Object/Optional na raiz, SpEL/default methods/setters, fontes aninhadas, tipos incompatíveis e projeções dinâmicas/derivadas. Projeções não executam callbacks de entidade nem preenchem auditoria/versão; ausência e null viram null no DTO/getter, sem defaults ou descarte da linha. Campos de tipos complexos são selecionados inteiros. Mantidos os limites nativos de paginação/contagem e a separação entre projeção e entidade completa.

#### 5.1.1. Exemplo no demo: visões resumidas de produtos e dispositivos

**Base existente:** `CustomerSummary`, `CustomerView` e `/customer-projections`.

- [x] Criar `ProductSummary` em record e uma interface fechada de resumo de `DeviceState`, com mapeamento de nome por `@ProjectionProperty` e propriedades anuláveis.
- [x] Adicionar roteiro de projeções IDX/FT em JSON/HASH que confira campos retornados, aliases, ausência/null, paginação e totais, mantendo o retorno distinto da entidade completa.

### 5.2. Ampliação da derivação

- [x] Elaborar uma matriz dos operadores Spring Data e da sua viabilidade em IDX/FT.
- [x] Implementar os operadores adicionais viáveis, em entregas separadas, começando por prefixos TAG (`StartingWith`).
- [x] Avaliar `Not`/`NotIn` com regras explícitas para documentos sem o campo, antes de implementá-los.
- [x] Manter erros claros para operadores sem equivalente correto; não simular suporte filtrando páginas em memória.
- [x] Ampliar testes de combinações, aliases, campos ausentes e ordenação.

**Entregue em 2026-11-06:** prefixos String literais por faixa TAG IDX ou parâmetro FT, com IgnoreCase ASCII em schema compatível; `Not`/`NotIn` numéricos por FT com guarda de presença. Ausência/null/valor inválido não passam na negação. Negação TAG e operadores sem equivalente correto continuam recusados. Matriz, decisões, limites e exemplos em [docs/derived-queries.md](docs/derived-queries.md). São 12 novos testes unitários e 9 novos testes de integração, com paginação/totais, Unicode, parâmetros literais, combinações, aliases, JSON/HASH, TTL e HTTP. Suíte completa aprovada: 283 testes.

**Concluído quando:** os recursos novos tiverem semântica documentada, execução nativa e testes que comprovem filtros, conteúdo e totais.

**Limite conhecido:** não há compromisso de suportar todos os operadores do Spring Data. Null, campos ausentes e buscas textuais dependem das capacidades reais do servidor.

#### 5.2.1. Exemplo no demo: catálogo de livros

**Base existente:** `DerivedOperatorsController`, prefixos e negação numérica em `CustomerRepository`/`HashCustomerRepository`.

- [x] Criar `Book` e métodos derivados para prefixo do título, faixas de páginas, `In`, `Not`/`NotIn` numéricos e combinações `And`/`Or`, com índices compatíveis.
- [x] Adicionar roteiro com caracteres literais no prefixo, IgnoreCase ASCII, campos ausentes e resultados paginados; apresentar os operadores recusados e seus motivos.

## 6. Evolução de índices

### 6.1. Inspeção e diagnóstico

- [x] Expor APIs públicas para listar, inspecionar e acompanhar índices e backfills.
- [x] Exibir diferenças entre schema esperado e existente de forma legível.
- [x] Disponibilizar o plano escolhido para uma consulta derivada: índice, família IDX/FT e motivo da escolha.

**Entregue em 2026-11-07:** listagem, status leve por IDX.INFO, inspeção tipada JSON/HASH/FT e comparação sem DDL com diferenças por propriedade. `ensureIndexes` reutiliza o comparador; timeout de backfill inclui progresso observado. `DatadamQueryDiagnostics` explica índice, família, comandos, ordenação e candidatos com o mesmo planejador da execução, sem comandos ou valores de predicados. Inclui CLI administrativa e usuário observador sem DDL/leitura de documentos. São 20 novos testes unitários e 6 de integração; suíte completa aprovada com 309 testes (152 unitários e 157 de integração no Docker). Detalhes e custos de FT.INFO em [docs/index-diagnostics.md](docs/index-diagnostics.md). Nenhuma mudança C++ necessária.

#### 6.1.1. Exemplo no demo: diagnóstico das buscas do catálogo

**Base existente:** `IndexDiagnosticsApplication` e `DatadamQueryDiagnostics`.

- [x] Adicionar comandos de diagnóstico para `Product`, `Book` e `DeviceState`, com listagem/status, comparação de schema e explicação dos índices candidatos de métodos derivados.
- [x] Criar roteiro com schema correspondente e divergente, ordenação incompatível e usuário observador; exibir diferenças e motivos sem enviar valores de consultas ou executar DDL.

### 6.2. Migrações explícitas

- [x] Definir um fluxo de migração com nomes versionados, criação do novo índice e validação do backfill antes da mudança de uso.
- [x] Tratar duplicatas em UNIQUE como erro de dados que exige resolução explícita.
- [x] Separar a troca de uso e a eventual remoção do índice anterior; não prometer aliases ou troca atômica sem suporte do Datadam.
- [x] Testar interrupção/reinício do backfill e recuperação de uma migração incompleta.
- [x] Documentar credenciais e permissões específicas de migração.

**Entregue em 2026-11-07:** `IndexMigration` e `DatadamIndexMigrations` preparam um alvo `:vN`, validam escopo/schema/readiness e retomam alvos compatíveis após falha ou timeout. UNIQUE com falha exige resolução explícita dos dados e descarte separado do alvo FAILED; a origem é mantida. Troca de consumidores é um deploy explícito, com modelos de preparação separados; retirada usa uma operação/credencial própria e nunca remove documentos. Inclui CLI e 15 testes unitários novos mais 8 de integração, entre eles interrupção real do backfill em contêiner descartável com 15.000 documentos, retomada por outro cliente e validação de totais. Suíte completa aprovada: 332 testes. Limites de concorrência administrativa, proveniência do backfill e efeito imediato de UNIQUE sobre escritas estão em [docs/index-migrations.md](docs/index-migrations.md).

**Concluído quando:** for possível preparar e validar um schema novo sem remover automaticamente os índices que a aplicação ainda utiliza.

#### 6.2.1. Exemplo no demo: evolução dos índices do catálogo

**Base existente:** `CustomerSearchV2` e ações de `IndexMigrationApplication`.

- [x] Criar um modelo de preparação `ProductSchemaV2`, separado dos repositórios ativos, e um roteiro de prepare/validate com nomes versionados e consultas de validação de negócio.
- [x] Demonstrar deploy/troca de consumidores e retirada como etapas separadas; incluir falha UNIQUE, correção explícita dos dados e retomada, usando somente índices/dados próprios do exemplo.

## 7. Operação, segurança e compatibilidade

### 7.1. Diagnóstico da biblioteca

- [x] Expor configuração útil do pool e limites de espera, com padrões documentados.
- [x] Adicionar health check e métricas opcionais de conexão, latência e erros.
- [x] Garantir que logs e diagnósticos não incluam credenciais ou valores sensíveis por padrão.

**Entregue em 2026-11-07:** limites separados de conexão/leitura/espera com fallback compatível a `datadam.timeout`, tamanhos e manutenção do pool com validação antecipada e metadados Spring. Health PING e métricas Micrometer são opcionais, desativados por padrão na biblioteca e habilitados no demo. Timers/gauges/counters usam operações e resultados enumerados, sem argumentos, credenciais, chaves ou mensagens de exceção; health e validação de conexões não registram falhas detalhadas. Inclui testes sem dependências opcionais, sanitização, permissões de autenticação, esgotamento controlado do pool e HTTP Actuator. São 20 novos testes unitários e 6 de integração; suíte completa aprovada com 358 testes (187 unitários e 171 de integração). Configuração, custos e limites em [docs/library-diagnostics.md](docs/library-diagnostics.md).

#### 7.1.1. Exemplo no demo: acompanhamento de uma importação

**Base existente:** Actuator health/metrics habilitados no demo e `LibraryDiagnosticsIntegrationIT`.

- [x] Criar `ImportJob` e um serviço de importação de quantidade limitada de produtos, com estados e progresso consultáveis.
- [x] Adicionar roteiro que acompanhe health e métricas durante leituras/gravações e uma falha controlada; demonstrar configuração do pool e telemetria sem payloads ou credenciais.

### 7.2. Testes de falhas e segurança

- [x] Automatizar cenários de reinício, timeout, perda de conexão e saturação do pool.
- [x] Testar TLS e validação de certificado com ambiente reproduzível.
- [x] Ampliar testes ACL para leitura, escrita, consultas e administração de índices.
- [x] Documentar resultados incertos após falha de transporte; não repetir gravações automaticamente quando não for possível saber se foram aplicadas.

**Entregue em 2026-11-07:** 15 testes de integração adicionais com contêineres isolados: seis de transporte (proxy RESP com perda antes/depois da escrita, timeout, confirmação perdida com @Version, reinício e fila/esgotamento), quatro de TLS (cadeia confiável, CA desconhecida, identidade incorreta e validade expirada) e cinco de ACL para JSON/HASH, consultas e metadados, administração e EVAL. O pool automático habilita identificação do endpoint TLS; cliente e servidor do teste TLS ficam no Docker para preservar a cadeia original mesmo em hosts com inspeção TLS. As gravações não são repetidas automaticamente; os testes demonstram a ambiguidade entre escrita não enviada e escrita aplicada sem confirmação. Suíte completa aprovada: 373 testes (187 unitários e 186 de integração). Reprodução, mudança TLS e reconciliação em [docs/failures-and-security.md](docs/failures-and-security.md).

#### 7.2.1. Exemplo no demo: falha de confirmação e acesso restrito

**Base existente:** `TransportFailuresIT`, `TlsSecurityIT`, `AclSecurityIT` e os contêineres descartáveis de testes.

- [x] Criar um runner/roteiro opt-in que use uma atualização de `StockItem` para mostrar resposta perdida, estado persistido, versão local obsoleta e reconciliação explícita.
- [x] Adicionar cenários executáveis de reader/writer/schema e conexão TLS aceita/recusada em ambiente isolado, com instruções e saídas esperadas; manter a injeção de falhas fora das rotas normais da aplicação.

### 7.3. Matriz de compatibilidade e desempenho

- [x] Definir e testar as versões suportadas de Java, Spring Boot/Spring Data e Datadam.
- [x] Fixar uma versão identificável da imagem do Datadam nos testes automatizados.
- [x] Medir casos representativos de IDX, FT, paginação e atualizações, registrando ambiente e volume de dados.
- [x] Documentar custos de COUNT, validação de conexão, listas sem limite e SCAN nas operações globais do repositório.

**Entregue em 2026-11-07:** matriz Java 17.0.21/21.0.13/25.0.5 em Docker, cada uma com 187 testes unitários e consumidor Spring Boot 3.5.16/Data Commons 3.5.13 contra Datadam fixado no commit e2e2c0a. O build usa git archive da revisão e label OCI; testes verificam a revisão e usam o ID resolvido, com JVMs por digest. Suíte completa: 376 testes (187 unitários, 189 de integração), além das reexecuções unitárias na matriz. Workload opcional mediu 104 combinações de formato/operação/validação/volume com 1.000 e 10.000 documentos por formato, servidor limitado a 2 CPUs/1 GiB e resultados CSV/ambiente publicados. Custos, limites de interpretação e reprodução em [docs/compatibility-and-performance.md](docs/compatibility-and-performance.md).

**Concluído quando:** as garantias de conexão e segurança forem testadas automaticamente e houver uma matriz de compatibilidade verificável.

#### 7.3.1. Exemplo no demo: comparação de formas de consulta

**Base existente:** `NativePerformanceBenchmark`, `run-performance.ps1` e os CSVs publicados.

- [x] Adicionar um roteiro de catálogo/dispositivos que compare conteúdo completo, projeção, Page, Slice/lista limitada e contagem, com volume de dados configurável.
- [x] Documentar comandos e trabalho realizado em cada alternativa e permitir reproduzir a medição com as imagens fixadas; apresentar ambiente, volume e limites de interpretação dos números.

## 8. Distribuição e primeira versão estável

### 8.1. Projeto pronto para consumo

- [ ] Revisar a divisão entre integração Spring Data e configuração específica de Spring Boot; separar um starter se houver benefício concreto.
- [ ] Revisar a API pública e documentar garantias, limitações e política de compatibilidade.
- [ ] Preparar artefatos de fontes e Javadoc, licença e metadados de distribuição.
- [ ] Validar a dependência em uma aplicação consumidora independente do reactor Maven deste projeto.

#### 8.1.1. Exemplo de consumo: aplicação independente

**Dependência:** conclusão do contrato de distribuição do item 8.1.

- [ ] Criar `examples/consumer-boot` com POM próprio, fora do reactor principal, consumindo o artefato versionado e demonstrando um repositório de `Book` ou `Product`.
- [ ] Adicionar instruções para instalar a biblioteca localmente, configurar a conexão, preparar índices com credenciais separadas e executar um fluxo CRUD/consulta sem depender das classes do demo principal.

### 8.2. Integração contínua e release

- [ ] Automatizar build, testes unitários e integração com Datadam real no Docker.
- [ ] Criar checklist de release, changelog e orientação de atualização.
- [ ] Definir destino de publicação e preparar assinatura/credenciais sem incluí-las no repositório.
- [ ] Publicar a primeira versão somente após autorização explícita para o destino escolhido.

**Concluído quando:** outro projeto puder consumir um artefato versionado com documentação, testes reproduzíveis e instruções de atualização.

#### 8.2.1. Exemplo de validação de uma versão

**Dependência:** aplicação consumidora independente do item 8.1.1.

- [ ] Adicionar um roteiro de smoke test do consumidor com a versão candidata: startup, health, cadastro, leitura e consulta indexada, usando dados próprios.
- [ ] Documentar execução local e na CI e como conferir a versão do artefato; manter publicação remota como uma ação separada conforme o contrato do item 8.2.

## 9. Transações Spring — evolução posterior

- [ ] Investigar as garantias efetivas de MULTI/EXEC, WATCH e erros de execução no Datadam.
- [ ] Documentar a diferença entre atomicidade, isolamento e rollback; não inferir que uma transação de comandos ofereça rollback completo.
- [ ] Decidir se um gerenciador Spring pode oferecer um contrato útil e correto, incluindo conexão vinculada à transação e propagação suportada.
- [ ] Se viável, implementar o escopo definido e testar falhas de comandos, UNIQUE, versão e perda de conexão.
- [ ] Documentar o contrato entregue ou o motivo concreto para adiar o suporte.

**Concluído quando:** a decisão estiver registrada e qualquer suporte oferecido tiver garantias comprovadas, sem sugerir equivalência automática com transações de um banco relacional.

### 9.1. Exemplo no demo: operação envolvendo pedido e estoque

**Dependência:** decisão de viabilidade e contrato efetivamente entregue na etapa 9.

- [ ] Após a decisão, criar um roteiro com `PurchaseOrder` e `StockItem` que mostre as garantias reais em sucesso, concorrência e falha, distinguindo atomicidade, isolamento e rollback.
- [ ] Se houver integração transacional Spring entregue, demonstrar seu uso com os limites documentados; caso contrário, documentar o cenário e o motivo de adiamento do exemplo transacional.

## 10. Repositórios reativos — evolução posterior

- [ ] Selecionar e validar um transporte realmente não bloqueante que preserve os formatos de resposta usados pelo Datadam.
- [ ] Implementar operações reativas para JSON, IDX e FT, com cancelamento e controle de demanda.
- [ ] Criar repositórios reativos e definir seu suporte a TTL, versão, auditoria e projeções.
- [ ] Testar concorrência, encerramento de conexões e ausência de bloqueio das threads de processamento de eventos.
- [ ] Adicionar aplicação/exemplos reativos e documentar a compatibilidade com a API imperativa.

**Concluído quando:** a API reativa tiver transporte não bloqueante e comportamento testado. Encapsular chamadas Jedis bloqueantes em Mono/Flux não será considerado conclusão desta etapa.

### 10.1. Exemplo no demo: leituras reativas de telemetria

**Dependência:** transporte não bloqueante e repositórios reativos entregues na etapa 10.

- [ ] Criar `TelemetryReading` e um fluxo reativo de persistência/consulta com controle de demanda, conforme a API que for entregue.
- [ ] Adicionar roteiro e validação de cancelamento, erros e demanda limitada, verificando a ausência de bloqueio nas threads do fluxo; manter este exemplo pendente até existir o transporte necessário.

## Fora do compromisso atual

- TEXT, GEO, VECTOR, agregações e scoring que o Datadam ainda não implementa.
- Emular um cluster Redis ou múltiplos databases na biblioteca.
- Prometer custo constante para operações que o servidor executa por varredura.
- Introduzir mudanças no servidor C++ como dependência silenciosa de um recurso da biblioteca. Se forem necessárias, serão identificadas e planejadas separadamente.

## Registro de avanço

| Entrega | Estado | Evidência |
|---|---|---|
| Base JSON, índices e buscas nativas | Concluída | Biblioteca, aplicação HTTP e Docker local |
| Consultas derivadas | Concluída | 33 testes aprovados na última execução confirmada, somando a suíte existente |
| 1.1 — TTL de entidades | Concluída | 52 testes aprovados no total; padrão/override, Duration, persist, UNIQUE, ACL, índices e HTTP; limite de durabilidade em crash documentado |
| 1.2 — Geração de IDs e identificação de entidades novas | Concluída | 81 testes aprovados no total; geração opcional, estado novo, classes/records, colisões, TTL, falhas e HTTP |
| 1.3 — Auditoria e callbacks | Concluída | 108 testes aprovados no total; auditoria opcional, ordem e proteção dos callbacks, records/classes, relógio e HTTP |
| 2.1 — Optimistic locking com @Version | Concluída | CAS nativo, concorrência, exclusão versionada e integração JSON/HASH |
| 5.1 — Projeções | Concluída | Records/interfaces, seleção nativa IDX/FT e JSON/HASH; 262 testes Java na entrega |
| 5.2 — Ampliação da derivação | Concluída | Prefixos String e negação numérica com presença; matriz e exemplos; 283 testes Java aprovados (132 unitários e 151 de integração) |
| 6.1 — Inspeção e diagnóstico | Concluída | Schema/status tipados, comparação sem DDL e planos derivados; 309 testes Java aprovados (152 unitários e 157 de integração) |
| 6.2 — Migrações explícitas | Concluída | Preparação versionada retomável, validação, retirada separada e recuperação UNIQUE; 332 testes Java aprovados (167 unitários e 165 de integração) |
| 7.1 — Diagnóstico da biblioteca | Concluída | Pool configurável, health/métricas opcionais e telemetria sanitizada; 358 testes Java aprovados (187 unitários e 171 de integração) |
| 7.2 — Testes de falhas e segurança | Concluída | Falhas com resultado incerto, reinício/pool, TLS com identidade e matriz ACL; 373 testes Java aprovados (187 unitários e 186 de integração) |
| 7.3 — Matriz de compatibilidade e desempenho | Concluída | Java 17/21/25, imagem por revisão/ID, workload e artefatos publicados; 376 testes Maven e 561 execuções unitárias adicionais na matriz |

Ao terminar cada entrega, acrescentaremos aqui o resultado, os testes executados e eventuais decisões que alterem os próximos passos.
