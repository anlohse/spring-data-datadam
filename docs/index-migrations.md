# Migrações explícitas de índices

O item 6.2 prepara um novo índice sem substituir o índice que a aplicação usa. O fluxo tem três fases independentes: **preparar**, **trocar os consumidores** e **remover o índice anterior**. Não há alias lógico, troca atômica de nomes, atualização automática de repositórios ou rollback automático.

## 1. Declarar um alvo versionado separado

```java
@DatadamDocument(prefix = "customer:")
@DatadamIndex(name = "customers:search:v2", kind = DatadamIndex.Kind.SEARCH,
    fields = {
        @IndexField(path = "$.city", alias = "city", caseSensitive = true),
        @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC),
        @IndexField(path = "$.email", alias = "email", caseSensitive = true)})
record CustomerSearchV2(@Id String id) {}

var migration = new IndexMigration(
    CustomerSearchV2.class, "customers:search", "customers:search:v2");
var migrations = new DatadamIndexMigrations(administrativeCommands);
```

O alvo deve estar declarado no modelo informado e ter sufixo `:vN`, com N inteiro positivo. Origem e alvo precisam ter nomes diferentes. A origem deve existir, estar READY e cobrir exatamente o mesmo prefixo e formato JSON/HASH. É permitido mudar a família IDX/FT e o schema; essas mudanças precisam ser compatíveis com as consultas e dados da aplicação.

Use um modelo de preparação separado das entidades/repositórios ativos. A derivação escolhe entre as anotações da entidade: acrescentar v2 à entidade ativa pode alterar essa escolha na próxima execução, mesmo antes do deploy pretendido. Criar v2 no servidor, usando um modelo separado, não muda o plano da entidade antiga. Não inclua o modelo de preparação no provisionamento automático de todos os schemas.

## 2. Preparar e validar

```java
IndexMigrationStatus state = migrations.inspect(migration); // somente leitura
state = migrations.prepare(migration, Duration.ofMinutes(5));
if (!state.readyForUse()) throw new IllegalStateException("Migration is not ready");

// Revalidar antes de publicar consumidores que referenciam o nome novo:
migrations.validateReady(migration);
```

`prepare` valida o escopo da origem e o schema declarado. Se o alvo não existe, cria **somente esse índice**, com backfill completo; não usa SKIPINITIALSCAN. Se existe e o schema corresponde, aproveita-o e aguarda READY. Schema diferente interrompe o fluxo com diferenças por propriedade, sem DROP nem substituição. Uma criação concorrente que responde “already exists” também é inspecionada e comparada.

O retorno contém a declaração da migração, a inspeção da origem e a comparação do alvo. `readyForUse()` exige ambos os índices READY e schema do alvo compatível. `inspect` pode mostrar alvo ausente/BUILDING/FAILED e origem ausente, sem criar nada. `validateReady` exige o estado pronto; não executa DDL nem muda roteamento.

O timeout limita a espera por backfill, não a soma das chamadas de metadados, que também seguem o timeout de conexão/socket. Os schemas e estados são observados por comandos separados. FT.INFO percorre o índice para obter metadados completos/estatísticas; use o [status leve](index-diagnostics.md) para polling frequente.

READY confirma que o backfill desse índice terminou, mas não substitui testes das consultas de negócio. Mudanças de campos, TAGs divididas, caixa, aliases e valores ausentes podem mudar os resultados. IDX composto exclui documentos que não fornecem todos os campos indexáveis. Faça consultas de validação usando credenciais com acesso aos dados necessários e confirme filtros, ordenação, cobertura e totais conforme a semântica nova; a API de migração não lê nem corrige documentos.

Um índice existente criado externamente com SKIPINITIALSCAN pode ter schema compatível e estado READY sem cobrir os dados históricos. O servidor não expõe essa proveniência em INFO. Retome apenas alvos criados por este fluxo ou cuja criação com backfill completo tenha sido verificada operacionalmente. A biblioteca não consegue certificar essa história a partir de INFO.

## 3. Fazer a troca de uso na aplicação

Depois de `validateReady` e dos testes de negócio, faça um deploy que referencie explicitamente `customers:search:v2` em `@FtQuery`, `@IdxQuery`, na API programática ou nas declarações da entidade usada pela derivação. Para exemplo anotado:

```java
@FtQuery(index = "customers:search:v2", value = "@city:{?0} @age:[?1 ?2]")
Page<CustomerSearchV2> byCityAndAge(String city, int minimum, int maximum, Pageable page);
```

O índice precisa estar declarado na entidade desse repositório. O servidor não redireciona o nome antigo para o novo. Durante um deploy gradual, versões antigas e novas podem consultar nomes diferentes. Mantenha ambos enquanto houver consumidores antigos, inclusive tarefas agendadas e workers. Para voltar à versão anterior, volte o deploy enquanto o índice anterior ainda existe; isso não desfaz efeitos de novas restrições UNIQUE sobre gravações.

**UNIQUE afeta as escritas desde CREATE, antes da troca das consultas.** O índice novo passa a ser mantido assim que é registrado. Antes de prepará-lo, ajuste os produtores e resolva os dados incompatíveis com a nova restrição. A migração não é uma fase sem efeitos sobre gravações, mesmo que o roteamento de leitura permaneça intacto.

## 4. Remover o anterior em operação separada

```java
// Com outra conexão, autorizada a DROP, após retirar todos os consumidores antigos:
var cleanup = new DatadamIndexMigrations(retirementCommands);
cleanup.retirePrevious(migration);
```

Essa operação revalida schema e readiness e envia somente `IDX.DROP previousIndex`. O Datadam aceita IDX.DROP tanto para IDX quanto para FT. Não é usado FT.DROPINDEX DD e nenhum documento é removido. A API não pode detectar tráfego ou provar que todos os consumidores migraram; essa decisão pertence ao deploy/operação. `prepare` e `validateReady` nunca chamam DROP.

Não remova o anterior se ainda for necessário para rollback. Depois do DROP, a declaração antiga também precisa sair do provisionamento de schemas da aplicação; caso contrário, `ensureIndexes` poderá recriá-la. Revalidar a migração pelo nome anterior após sua remoção informa origem ausente: o relatório não é um histórico permanente de execução.

Operações administrativas concorrentes precisam ser serializadas externamente. A checagem e o DROP não são uma operação atômica com a aplicação nem com outro administrador que possa remover/recriar o mesmo nome. Use nomes versionados imutáveis e não reutilize nomes de migrações concluídas.

## Falha UNIQUE e recuperação

Se dados existentes violam UNIQUE, o alvo fica FAILED, a preparação lança `DataIntegrityViolationException` e o índice anterior permanece. A biblioteca não apaga documentos, escolhe “um vencedor”, modifica valores, relaxa UNIQUE nem recria o alvo automaticamente.

1. Inspecione a falha e identifique os dados com ferramentas autorizadas.
2. Resolva as duplicatas explicitamente, respeitando o domínio e os produtores concorrentes.
3. Com credenciais de limpeza, chame `discardFailedTarget(migration)`. Essa operação exige schema correspondente e estado FAILED, mantém a origem READY e remove somente o alvo.
4. Execute `prepare` novamente e repita a validação de negócio.

O método de descarte recusa alvos READY ou BUILDING. Para abandonar uma construção em andamento, use uma intervenção administrativa separada e avalie seus consumidores; não há cancelamento automático pela biblioteca. Um índice FAILED não é mantido pelas escritas; corrigir os dados não o torna READY por si só.

Erros podem conter nomes de índices, chaves ou valores fornecidos pelo servidor; trate a saída administrativa conforme a sensibilidade dos dados e não a publique em logs HTTP.

## Timeout, desconexão e reinício

- Timeout/interrupção de espera deixa os índices existentes no servidor. Nova execução de `prepare` inspeciona o alvo e retoma a espera, sem criar outro nome nem remover a origem.
- Se a resposta de CREATE se perder, a API propaga a falha sem repetir a mutação dentro da mesma chamada. Execute novamente com o mesmo nome/intenção para descobrir se a definição foi registrada. Só “already exists” é tratado como disputa de criação, após nova comparação do schema.
- O Datadam persiste definições e reinicia automaticamente o backfill de um índice que ficou BUILDING. Após reinício do servidor, recrie a conexão/cliente, inspecione e execute `prepare`/`validateReady` novamente. Progresso pode recomeçar em zero.
- Não há tabela de migrações ou decisão de cutover persistida pela biblioteca. A retomada usa o nome versionado e o schema do servidor.
- A perda da resposta de DROP também deixa resultado incerto. Inspecione os nomes antes de decidir o próximo passo; não repita remoções cegamente nem reutilize o nome removido.

Falhas ACL/transporte não são convertidas em “índice ausente”. A API não amplia privilégios nem usa uma conexão alternativa automaticamente.

## Credenciais específicas

O Docker local separa três papéis:

| Usuário | Permissões de índices | Uso |
|---|---|---|
| `index-observer` | IDX.LIST, IDX.INFO, FT.INFO | Diagnóstico somente leitura |
| `index-migrator` | IDX.INFO, FT.INFO, IDX.CREATE, FT.CREATE | Preparação/validação, sem DROP |
| `index-retirer` | IDX.INFO, FT.INFO, IDX.DROP | Retirada ou descarte explícito, sem CREATE |

Esses usuários também têm conexão/autenticação. Nenhum recebe comandos de leitura/escrita de documentos ou permissões para alterar ACL. A aplicação `app` continua sem DDL. Ajuste usuários e senhas no ambiente real; os segredos do exemplo são apenas locais.

ACL de comandos de índice não limita o nome do índice por padrão de chave. Esses privilégios administrativos e metadados são globais; uma conexão autorizada a DROP não é confinada ao prefixo da entidade pela ACL. A API verifica escopo, mas o controle de acesso deve considerar o comando nativo. Para validar consultas/dados, use outro usuário com os comandos e padrões de leitura necessários.

Depois de editar a configuração local, `docker compose restart datadam` carrega as novas credenciais sem remover o volume. Não conceda DROP ao preparador por conveniência.

## Exemplo executável

`CustomerSearchV2` é o modelo de preparação separado de `Customer`. O exemplo migra o FT `demo:customers:search` para `demo:customers:search:v2`, com cidade exata e email no schema. Isso muda a semântica em relação ao FT original com folding e tags; a aplicação ativa continua usando suas declarações atuais. O exemplo não faz deploy/cutover.

```powershell
$env:DATADAM_MIGRATION_USER = 'index-migrator'
$env:DATADAM_MIGRATION_PASSWORD = 'local-index-migrator-password'
java '-Dloader.main=com.anlohse.datadam.demo.IndexMigrationApplication' -cp datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher inspect
java '-Dloader.main=com.anlohse.datadam.demo.IndexMigrationApplication' -cp datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher prepare
java '-Dloader.main=com.anlohse.datadam.demo.IndexMigrationApplication' -cp datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher validate
```

A CLI exige exatamente uma ação. `retire` e `discard-failed` são ações separadas e exigem credenciais `index-retirer`; não são parte de `prepare`. Não execute `retire` neste exemplo enquanto os repositórios atuais ainda dependerem do FT original.

## Validação automatizada

`IndexMigrationsTest` testa preparo de um único alvo, retomada, conflitos, resposta de criação perdida, estados inválidos, interrupção e separação entre preparo/descarte/retirada. `IndexMigrationsIntegrationIT` valida JSON/HASH, backfill histórico, colisão de schema, duplicatas UNIQUE e sua correção explícita, manutenção do plano ativo e ACL de cada papel.

`IndexMigrationRestartIT` inicia um Datadam descartável em porta aleatória de loopback, carrega 15.000 documentos e interrompe o processo enquanto o alvo FT está BUILDING. Após reinício, outro cliente retoma a preparação e verifica a reconstrução e os totais nos dois índices sem novo CREATE/DROP. O contador observado após reinício confirma que o backfill foi retomado. O contêiner e seu volume anônimo são removidos ao final; o serviço da aplicação não é reiniciado por esse teste.

Execute `scripts/run-local.ps1 -Restart -IntegrationTests -Background`. Os testes de reinício exigem Docker CLI e a imagem versionada do [contrato de testes](../config/test-images.properties); `-BuildDatadam` prepara a revisão local fixada quando necessário. Nenhuma mudança C++ adicional é exigida por esta entrega.
