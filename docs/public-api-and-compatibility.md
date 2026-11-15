# API pública, distribuição e compatibilidade — 8.1

## Artefatos e escolha de dependência

**Coordenadas e pacotes atualizados em 09/11/2026:** todos os artefatos Datadam usam `com.anlohse.datadam`; os pacotes Java passaram de `io.datadam.*` para `com.anlohse.datadam.*`. Para o Central, o namespace a verificar é `com.anlohse`, correspondente a `anlohse.com`. Atualize os groupIds, imports e nomes de classes em configurações, reinstale as bibliotecas e reconstrua seus consumidores. As evidências das execuções anteriores preservam os nomes usados na ocasião; uma nova evidência registra a migração dos pacotes.

| Artefato `com.anlohse.datadam` | Responsabilidade |
|---|---|
| `spring-data-datadam` | Entidades, callbacks/auditoria, repositórios Spring Data Commons, template, transporte RESP/Jedis, operações JSON/HASH/IDX/FT, projeções e administração explícita de índices. Sem Boot, Actuator ou Micrometer Core nas dependências de execução. |
| `datadam-spring-boot-autoconfigure` | Properties, configuração do pool/template/geradores e diagnóstico opcional do Boot. Depende do núcleo; Actuator, Micrometer e processador de configuração são opcionais. |
| `datadam-spring-boot-starter` | Entrada recomendada para Boot: agrega autoconfiguração e `spring-boot-starter`, que traz também o núcleo. Sem API própria de execução. |
| `datadam-spring-data-parent` | Metadados e configuração do build; necessário para resolver os POMs publicados/instalados. Não precisa ser o parent da aplicação consumidora. |

A separação tem um benefício verificável: uma aplicação Spring sem Boot resolve somente o núcleo, enquanto uma aplicação Boot recebe o conjunto pronto pelo starter. `EnableDatadamRepositories` e `EnableDatadamAuditing` continuam no núcleo e não exigem Boot. Os nomes Java das classes movidas foram preservados; a localização dos JARs mudou. Há split packages `config`/`diagnostics` entre núcleo e autoconfiguração: o contrato atual é classpath, não JPMS/module-path.

### Atualizar os consumidores anteriores deste SNAPSHOT

Antes desta entrega, `spring-data-datadam` incluía a autoconfiguração. Para continuar usando `datadam.*` e a descoberta automática de beans no Boot, substitua a dependência direta pelo starter, com a mesma versão:

```xml
<dependency>
  <groupId>com.anlohse.datadam</groupId>
  <artifactId>datadam-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Quem já configura tudo manualmente pode manter o núcleo. Quem monta sua própria convenção de dependências Boot pode usar `datadam-spring-boot-autoconfigure` diretamente. Ative explicitamente `@EnableDatadamRepositories`; o starter não executa DDL nem ativa auditoria. Health/métricas permanecem desativados por padrão e só funcionam com as classes opcionais correspondentes e opt-in nas propriedades.

## Superfície revisada

O suporte de consumo se concentra nos tipos abaixo. A visibilidade `public` de um adaptador de infraestrutura não o transforma em ponto de extensão. Não são garantidos construtores/override de implementações usados apenas internamente pelo Spring.

| Superfície | Contrato e uso |
|---|---|
| `annotation.*` | Metadados declarativos de documento/storage, índices/campos, queries IDX/FT, TTL, ID e mapeamento de projeção. |
| `repository.DatadamRepository` | Interface a estender por domínio; CRUD Spring Data mais consultas e projeções nativas no subconjunto documentado. |
| `DatadamTemplate`, `EntityModel` | API programática de entidades, queries e projeções; o modelo liga o tipo a um prefixo/storage e às suas declarações. |
| `CompoundQuery`, `SearchQuery`, `ProjectionSpec`, `JsonPath` | Objetos de entrada tipados. Parâmetros vinculados não são concatenação livre de valores de usuário. |
| `DatadamJsonOperations`, `JsonReadResult`, `JsonPopResult` | Leitura e mutação parcial, resultados de null/ausência e arrays conforme os guias específicos. |
| `DatadamIdGenerator`, `IdGenerationContext`, `UuidDatadamIdGenerator` | Extensão de geração de IDs; a estratégia padrão tem nome `datadamIdGenerator`. |
| `callback.*`, `auditing.DatadamAuditing` | Callbacks ordenados e integração de auditoria, incluindo falha AfterSave já confirmada. Anotações de opt-in em `config` permanecem públicas. |
| `DatadamIndexOperations`, `DatadamIndexMigrations`, `Index*` | Inspeção/comparação, preparação, validação e retirada explícitas. Executar com credenciais e ciclo administrativo separados. |
| `repository.DatadamQueryDiagnostics`, `DerivedQueryPlan` | Explicação local, sem valores de predicados; plano de compatibilidade do schema declarado, não estimativa de custo do servidor. |
| `DatadamCommands`, `diagnostics.DatadamCommandObserver`, `CommandObservation` | Transporte síncrono e observação. Comandos diretos são uma saída de baixo nível e podem ignorar o ciclo das entidades. |
| `config.DatadamProperties` e autoconfiguração Boot | Propriedades `datadam.*`, nomes documentados de beans e recuo quando o consumidor fornece beans compatíveis. A implementação do factory do pool é interna. |

`SimpleDatadamRepository`, `DatadamRepositoryFactoryBean`, `DatadamRepositoriesRegistrar`, `DatadamAuditingConfiguration` e o contexto de mapeamento de auditoria são adaptadores de infraestrutura. Não há SPI prometida para subclasses, reflexão sobre campos internos ou substituição de compiladores/decodificadores. Use interfaces de repositório, callbacks, geradores, template e beans documentados.

Na configuração manual sem Boot, os construtores simples de `DatadamTemplate` não procuram callbacks/auditoria no contexto Spring. Quem precisa desse ciclo deve fornecer `EntityCallbacks` e `DatadamAuditing` pelo construtor correspondente; a variante com esses objetos e sem predicado de callbacks recusa mutações parciais de forma conservadora. A autoconfiguração Boot faz essa ligação e identifica os tipos com callbacks de gravação.

Exceções Spring DAO distinguem duplicata (`DuplicateKeyException`), versão obsoleta (`OptimisticLockingFailureException`), ACL, uso inválido e falha de recurso. `AfterSaveCallbackException` informa escrita confirmada e sua chave. `JsonMutationDecodingException` distingue falha de decodificação depois do envio: não é sinal de que a mutação foi desfeita. As mensagens nativas não são um protocolo estável para lógica da aplicação e podem conter detalhes; a telemetria evita registrá-las.

## Garantias e limites

- APIs são bloqueantes, com uma conexão do pool por comando. Não há suporte reativo, cluster, database diferente de 0 ou gerenciador de transações Spring nesta versão. A dependência `spring-tx` oferece a hierarquia de exceções, sem fornecer um transaction manager Datadam.
- JSON é padrão; HASH requer HREPLACE. Projeções JSON requerem RETURNJSON. O servidor validado é a revisão fixada em `config/test-images.properties`, não Redis comum nem qualquer revisão Datadam.
- UNIQUE, filtros, ordem e paginação usam recursos nativos. Não se promete equivalência com todos os operadores Spring Data nem busca textual completa do RediSearch. TAG/NUMERIC e as recusas documentadas definem o escopo.
- `@Version` faz CAS por entidade. Atualizações/exclusões recusadas por versão não substituem o documento. Comandos diretos e alterações fora da biblioteca podem contornar esse contrato.
- A biblioteca não repete gravações de transporte automaticamente. Depois de perder uma resposta, leia/reconcilie por ID ou chave de negócio antes de decidir uma nova operação.
- `saveAll`, importações e sequências de comandos JSON são operações separadas, com possibilidade de êxito parcial e sem rollback geral. Não há promessa de gravação conjunta durável de documento/TTL após crash além das garantias já documentadas.
- Escrita parcial JSON não pode alterar ID/TTL nem ignorar versão, auditoria ou callbacks de gravação. Projeções retornam DTOs/interfaces, sem carregar entidades completas ou executar seu ciclo de callbacks.
- `findAll`/`count()` globais fazem SCAN do prefixo; operações indexadas podem ter COUNT adicional. List sem limite pode materializar todos os resultados. Prefira Page/Slice/lista limitada conforme o custo desejado.
- Credenciais de runtime e DDL são separadas. Não há criação de índices implícita pelo starter, alias de migração, retirada automática de origem ou limpeza global de dados.

Os detalhes por recurso continuam nos guias de [TTL](entity-ttl.md), [IDs](entity-identity.md), [auditoria](auditing-and-callbacks.md), [versão](optimistic-locking.md), [JSON](json-native-mutations.md), [HASH](hash-indexes-and-queries.md), [projeções](projections.md), [derivação](derived-queries.md), [migração](index-migrations.md) e [falhas/segurança](failures-and-security.md).

## Política de versão

A versão atual é **0.1.0-SNAPSHOT**, ainda anterior à primeira versão estável. SNAPSHOT é mutável e pode receber mudanças incompatíveis, como a divisão dos artefatos nesta entrega. Atualizações exigem ler as notas/guias e repetir os testes do consumidor; não se deve tratar um SNAPSHOT como release imutável.

Para versões estáveis futuras, a política proposta é SemVer: patch preserva a superfície de consumo documentada; minor adiciona funcionalidades compatíveis e pode depreciar APIs; remoções/mudanças incompatíveis dessa superfície exigem major e orientação de migração. Correções que alterem comportamento incorreto ou reforcem segurança devem explicitar o impacto. Esta política não concede estabilidade a adaptadores internos nem promete suporte a todas as versões externas.

A matriz validada é Java 17/21/25, Boot 3.5.16 e Data Commons 3.5.13, conforme [compatibilidade](compatibility-and-performance.md). O núcleo usa o gerenciamento de versões do parent no build, mas não depende de Boot em execução. Sua validação isolada usa Spring 6.2/Data Commons da mesma matriz. Boot 4, Spring 7 e versões de dependências fora desse conjunto não estão validados. As aplicações consumidoras Boot devem gerenciar dependências com um parent/BOM compatível; não oferecemos ainda um BOM próprcom.anlohse.datadam.

## Artefatos locais e metadados

```powershell
mvn -B -ntp -pl datadam-spring-boot-starter -am install
.\scripts\verify-library-artifacts.ps1
.\scripts\verify-consumer.ps1
```

O primeiro comando instala o parent e os três artefatos com POMs. Núcleo e autoconfiguração geram JAR normal, `-sources.jar` e `-javadoc.jar`; o starter gera os mesmos classificadores, com documentação de pacote e um HTML explicando que não oferece classes de API próprias. Esse JAR informativo foi acrescentado no item 8.2 para o formato exigido pelo Central. A verificação atual confere nove JARs, licença MIT do projeto, scripts Lua do núcleo, índice de Javadoc e metadados Boot. O Javadoc valida erros de formato/referências; avisos de documentação ausente não impedem o build. Não é uma afirmação de documentação exaustiva de cada método interno.

Licença, autor, URL, SCM e issue tracker são herdados do parent. Os JARs de código/fontes e de Javadoc carregam `META-INF/LICENSE`; o núcleo não contém AutoConfiguration.imports e a autoconfiguração inclui esse registro e `spring-configuration-metadata.json`. Hashes dos JARs ficam em `target/library-artifacts.json`.

A validação real de consumo é [examples/consumer-boot](../examples/consumer-boot/README.md), fora do reactor. Ela empacota e executa somente JARs instalados em outro build Maven, com schema separado e Datadam descartável. Não existe `systemPath`, importação do demo ou fallback para `target/classes` do projeto principal.

O item 8.2 acrescenta CI, changelog, assinatura e preparação de bundle para o Central Portal; o [guia de release](ci-and-release.md) descreve esses passos. Não há `distributionManagement`, credenciais reais nem deploy remoto automático. A publicação continua dependente de namespace/chave reais e autorização explícita.

Validação de 08/11/2026: **395 testes aprovados**, com 140 no núcleo, 48 na autoconfiguração e 207 de integração; mais 564 reexecuções unitárias em Java 17/21/25. O consumidor independente executou o JAR com CRUD/IDX/FT/UNIQUE/versão, sem dependências do demo, Actuator ou Micrometer Core. Resultado, árvore de dependências, revisão do servidor e hashes dos oito JARs estão na [evidência da entrega](validation/2026-11-08-consumption.json). Hashes identificam este build local de SNAPSHOT; não são promessa de reprodução bit a bit.
