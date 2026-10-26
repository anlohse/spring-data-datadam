# Roadmap — Spring Data Datadam

Este documento acompanha a evolução da biblioteca, da base funcional atual até uma versão adequada para adoção em outros projetos. A ordem busca entregar utilidade em cada etapa, sem depender de uma reescrita futura.

**Estado atual:** base funcional e TTL de entidades entregues, com 52 testes aprovados na última execução confirmada: 19 unitários e 33 de integração com Datadam real no Docker.

**Próxima entrega:** **1.2 — Geração de IDs e identificação de entidades novas**. A implementação desse recurso ainda não começou.

## Como vamos trabalhar

- Cada item numerado representa uma entrega pequena que podemos implementar e revisar separadamente.
- Seguiremos a ordem abaixo. Ao concluir um item, atualizaremos seus checks e o campo “Próxima entrega”.
- Um item só recebe `[x]` depois de implementação, testes adequados, documentação e exemplo de uso, quando aplicável.
- Recursos já existentes não precisam ser refeitos; serão ampliados mantendo os contratos atuais.
- Mudanças incompatíveis precisam de uma orientação de migração. Nenhuma etapa autoriza apagar documentos, remover índices em uso ou publicar artefatos remotamente de forma automática.
- Investigações devem resultar em uma decisão documentada: implementar, reduzir o escopo ou adiar com uma razão concreta. Não vamos marcar uma funcionalidade como entregue só porque sua viabilidade foi investigada.

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

### 1.2. Geração de IDs e identificação de entidades novas

- [ ] Definir quando uma entidade é considerada nova, preservando o suporte a IDs atribuídos pela aplicação.
- [ ] Oferecer geração opcional de IDs e uma extensão para estratégias próprias.
- [ ] Suportar classes mutáveis e records, retornando a entidade com o ID efetivamente persistido.
- [ ] Testar geração, preservação de IDs existentes e comportamento em falhas de gravação.
- [ ] Documentar os tipos de ID suportados e atualizar o exemplo.

**Concluído quando:** o usuário puder escolher entre informar o ID ou recebê-lo no retorno de `save`, com comportamento previsível em novas gravações e atualizações.

### 1.3. Auditoria e callbacks

- [ ] Introduzir callbacks de persistência e leitura com ordem de execução documentada.
- [ ] Integrar datas de criação/alteração e autor de criação/alteração com as abstrações do Spring Data.
- [ ] Usar a detecção de entidade nova da etapa 1.2 para preservar os dados de criação.
- [ ] Testar classes mutáveis, records, relógio controlável e callbacks que recusam uma operação.
- [ ] Adicionar exemplo de auditoria sem exigir que a aplicação use esse recurso.

**Concluído quando:** salvar uma entidade puder preencher auditoria e executar callbacks sem alterar indevidamente sua identidade ou seus dados de criação.

## 2. Concorrência e controle de versão

### 2.1. Optimistic locking com `@Version`

**Benefício:** impedir que uma atualização sobrescreva silenciosamente uma alteração feita por outra conexão.

- [ ] Definir a versão inicial e as regras de incremento para criação, atualização e exclusão.
- [ ] Validar e implementar no Datadam uma comparação de versão e gravação atômicas; comparar no cliente antes de salvar não é suficiente.
- [ ] Traduzir conflito de versão para `OptimisticLockingFailureException`.
- [ ] Integrar versão com TTL, geração de IDs, auditoria e callbacks.
- [ ] Testar duas conexões concorrentes, entidade removida/expirada e falha por UNIQUE sem incremento de versão indevido.
- [ ] Documentar a diferença entre salvar uma entidade versionada e executar comandos nativos diretamente.

**Concluído quando:** duas atualizações da mesma versão não puderem ter sucesso simultaneamente, e a operação recusada preservar documento, TTL e índices.

## 3. Atualizações parciais JSON

### 3.1. API de caminhos e alterações simples

- [ ] Criar uma API tipada para ler, definir e remover valores por JSONPath.
- [ ] Tratar corretamente as respostas diferentes de JSONPath e dos caminhos legados.
- [ ] Definir a interação dessas alterações com `@Version` e auditoria; não permitir que uma API da biblioteca contorne silenciosamente essas políticas.
- [ ] Testar caminhos ausentes, valores null, tipos incompatíveis, TTL e atualização dos índices.
- [ ] Adicionar exemplos na aplicação de teste.

### 3.2. Incrementos, arrays e merge

- [ ] Expor incrementos numéricos, operações de arrays e merge de objetos com contratos de retorno claros.
- [ ] Definir os limites de atomicidade: uma sequência de comandos não deve ser apresentada como uma única operação atômica.
- [ ] Testar concorrência, falhas por UNIQUE e manutenção dos índices após cada tipo de alteração.
- [ ] Documentar o custo do servidor: uma alteração parcial JSON pode continuar exigindo reprocessar o documento inteiro no Datadam.

**Concluído quando:** a aplicação puder alterar partes de um documento sem montar sua substituição completa e sem perder as garantias documentadas da entidade.

## 4. Persistência em HASH

### 4.1. Mapeamento e CRUD

- [ ] Permitir escolher explicitamente HASH ou JSON no mapeamento da entidade.
- [ ] Definir conversão de números, booleanos, enums, campos ausentes e null; deixar explícito como tratar estruturas aninhadas e coleções.
- [ ] Implementar CRUD HASH com substituição correta dos campos removidos, preservando TTL e respeitando UNIQUE.
- [ ] Integrar IDs, auditoria e `@Version`, verificando as garantias atômicas do formato HASH.
- [ ] Testar isolamento de prefixos e coexistência de entidades HASH e JSON.

### 4.2. Índices e consultas sobre HASH

- [ ] Gerar e validar definições `ON HASH` e seus nomes/aliases de campos.
- [ ] Adaptar consultas anotadas, derivadas e programáticas para o formato HASH.
- [ ] Testar diferenças de TAG, separadores, valores ausentes e NUMERIC entre HASH e JSON.
- [ ] Adicionar à aplicação um exemplo HASH equivalente ao exemplo JSON.

**Concluído quando:** o mesmo conjunto de garantias públicas de CRUD e consultas funcionar nos dois formatos, com suas diferenças documentadas.

## 5. Consultas mais completas

### 5.1. Projeções

- [ ] Implementar seleção de campos usando os retornos nativos de IDX/FT.
- [ ] Mapear o resultado para DTOs e/ou interfaces de projeção, definindo explicitamente o primeiro conjunto suportado.
- [ ] Preservar aliases, conversões, campos ausentes, ordem e paginação sem consultas extras por documento.
- [ ] Testar HASH e JSON sem apresentar uma entidade incompleta como se estivesse totalmente carregada.

### 5.2. Ampliação da derivação

- [ ] Elaborar uma matriz dos operadores Spring Data e da sua viabilidade em IDX/FT.
- [ ] Implementar os operadores adicionais viáveis, em entregas separadas, começando por prefixos TAG (`StartingWith`).
- [ ] Avaliar `Not`/`NotIn` com regras explícitas para documentos sem o campo, antes de implementá-los.
- [ ] Manter erros claros para operadores sem equivalente correto; não simular suporte filtrando páginas em memória.
- [ ] Ampliar testes de combinações, aliases, campos ausentes e ordenação.

**Concluído quando:** os recursos novos tiverem semântica documentada, execução nativa e testes que comprovem filtros, conteúdo e totais.

**Limite conhecido:** não há compromisso de suportar todos os operadores do Spring Data. Null, campos ausentes e buscas textuais dependem das capacidades reais do servidor.

## 6. Evolução de índices

### 6.1. Inspeção e diagnóstico

- [ ] Expor APIs públicas para listar, inspecionar e acompanhar índices e backfills.
- [ ] Exibir diferenças entre schema esperado e existente de forma legível.
- [ ] Disponibilizar o plano escolhido para uma consulta derivada: índice, família IDX/FT e motivo da escolha.

### 6.2. Migrações explícitas

- [ ] Definir um fluxo de migração com nomes versionados, criação do novo índice e validação do backfill antes da mudança de uso.
- [ ] Tratar duplicatas em UNIQUE como erro de dados que exige resolução explícita.
- [ ] Separar a troca de uso e a eventual remoção do índice anterior; não prometer aliases ou troca atômica sem suporte do Datadam.
- [ ] Testar interrupção/reinício do backfill e recuperação de uma migração incompleta.
- [ ] Documentar credenciais e permissões específicas de migração.

**Concluído quando:** for possível preparar e validar um schema novo sem remover automaticamente os índices que a aplicação ainda utiliza.

## 7. Operação, segurança e compatibilidade

### 7.1. Diagnóstico da biblioteca

- [ ] Expor configuração útil do pool e limites de espera, com padrões documentados.
- [ ] Adicionar health check e métricas opcionais de conexão, latência e erros.
- [ ] Garantir que logs e diagnósticos não incluam credenciais ou valores sensíveis por padrão.

### 7.2. Testes de falhas e segurança

- [ ] Automatizar cenários de reinício, timeout, perda de conexão e saturação do pool.
- [ ] Testar TLS e validação de certificado com ambiente reproduzível.
- [ ] Ampliar testes ACL para leitura, escrita, consultas e administração de índices.
- [ ] Documentar resultados incertos após falha de transporte; não repetir gravações automaticamente quando não for possível saber se foram aplicadas.

### 7.3. Matriz de compatibilidade e desempenho

- [ ] Definir e testar as versões suportadas de Java, Spring Boot/Spring Data e Datadam.
- [ ] Fixar uma versão identificável da imagem do Datadam nos testes automatizados.
- [ ] Medir casos representativos de IDX, FT, paginação e atualizações, registrando ambiente e volume de dados.
- [ ] Documentar custos de COUNT, validação de conexão, listas sem limite e SCAN nas operações globais do repositório.

**Concluído quando:** as garantias de conexão e segurança forem testadas automaticamente e houver uma matriz de compatibilidade verificável.

## 8. Distribuição e primeira versão estável

### 8.1. Projeto pronto para consumo

- [ ] Revisar a divisão entre integração Spring Data e configuração específica de Spring Boot; separar um starter se houver benefício concreto.
- [ ] Revisar a API pública e documentar garantias, limitações e política de compatibilidade.
- [ ] Preparar artefatos de fontes e Javadoc, licença e metadados de distribuição.
- [ ] Validar a dependência em uma aplicação consumidora independente do reactor Maven deste projeto.

### 8.2. Integração contínua e release

- [ ] Automatizar build, testes unitários e integração com Datadam real no Docker.
- [ ] Criar checklist de release, changelog e orientação de atualização.
- [ ] Definir destino de publicação e preparar assinatura/credenciais sem incluí-las no repositório.
- [ ] Publicar a primeira versão somente após autorização explícita para o destino escolhido.

**Concluído quando:** outro projeto puder consumir um artefato versionado com documentação, testes reproduzíveis e instruções de atualização.

## 9. Transações Spring — evolução posterior

- [ ] Investigar as garantias efetivas de MULTI/EXEC, WATCH e erros de execução no Datadam.
- [ ] Documentar a diferença entre atomicidade, isolamento e rollback; não inferir que uma transação de comandos ofereça rollback completo.
- [ ] Decidir se um gerenciador Spring pode oferecer um contrato útil e correto, incluindo conexão vinculada à transação e propagação suportada.
- [ ] Se viável, implementar o escopo definido e testar falhas de comandos, UNIQUE, versão e perda de conexão.
- [ ] Documentar o contrato entregue ou o motivo concreto para adiar o suporte.

**Concluído quando:** a decisão estiver registrada e qualquer suporte oferecido tiver garantias comprovadas, sem sugerir equivalência automática com transações de um banco relacional.

## 10. Repositórios reativos — evolução posterior

- [ ] Selecionar e validar um transporte realmente não bloqueante que preserve os formatos de resposta usados pelo Datadam.
- [ ] Implementar operações reativas para JSON, IDX e FT, com cancelamento e controle de demanda.
- [ ] Criar repositórios reativos e definir seu suporte a TTL, versão, auditoria e projeções.
- [ ] Testar concorrência, encerramento de conexões e ausência de bloqueio das threads de processamento de eventos.
- [ ] Adicionar aplicação/exemplos reativos e documentar a compatibilidade com a API imperativa.

**Concluído quando:** a API reativa tiver transporte não bloqueante e comportamento testado. Encapsular chamadas Jedis bloqueantes em Mono/Flux não será considerado conclusão desta etapa.

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
| 1.2 — Geração de IDs e identificação de entidades novas | Próxima | API e implementação ainda pendentes |

Ao terminar cada entrega, acrescentaremos aqui o resultado, os testes executados e eventuais decisões que alterem os próximos passos.
