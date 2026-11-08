# Inspeção e diagnóstico de índices

O item 6.1 oferece inspeção de schema e backfill e explica a escolha de índices nas consultas derivadas. Essas APIs não criam, removem ou migram índices. `ensureIndexes`, que já existia, continua sendo a operação explícita de criação/validação.

## Listar e acompanhar backfill

```java
var indexes = new DatadamIndexOperations(commands);
List<String> names = indexes.listIndexes();
IndexStatus status = indexes.status("customers:city-age");
System.out.println(status.state());       // BUILDING, READY ou FAILED
System.out.println(status.keysScanned()); // chaves visitadas, não porcentagem
System.out.println(status.error());       // motivo informado pelo servidor em FAILED

indexes.awaitReady("customers:city-age", Duration.ofSeconds(30));
```

`listIndexes()` executa `IDX.LIST`, que lista tanto IDX quanto FT. `status()` faz somente `IDX.INFO`. `awaitReady` consulta esse mesmo status em intervalos de 25 ms, respeita interrupção e exige timeout positivo. Falha de backfill produz `DataIntegrityViolationException` com a causa do servidor; timeout produz `QueryTimeoutException` com o último contador de chaves visitadas. Um índice ausente não é tratado como “ainda construindo”.

Não há porcentagem real nem tempo estimado de conclusão. A contagem de chaves visitadas não informa quantas faltam. Um backfill interrompido por reinício pode recomeçar; não suponha que o contador seja monotônico entre reinícios.

Durante construção, IDX recusa consultas; FT pode responder com o subconjunto já indexado. Para exigir completude, aguarde READY antes de usar o índice. Um índice UNIQUE que falhou não será reconstruído nem terá suas duplicatas removidas por estas APIs.

## Inspeção completa

```java
IndexInfo info = indexes.inspect("customers:search");
info.fields().forEach(field -> System.out.println(
    field.path() + " AS " + field.alias() + ": " + field.type()
    + ", separator=" + field.separator() + ", caseSensitive=" + field.caseSensitive()));
```

`IndexInfo` contém status, formato JSON/HASH, prefixos, UNIQUE e campos em ordem. Cada campo tem caminho, alias, tipo TAG/NUMERIC, separador efetivo e sensibilidade a caixa. IDX TAG é sempre sensível a caixa; FT usa os atributos retornados pelo servidor, inclusive o separador padrão `,` no HASH.

Para IDX, a inspeção usa somente `IDX.INFO`. Para FT, usa também `FT.INFO`, necessário para CASESENSITIVE e estatísticas de documentos/entradas. **FT.INFO percorre o índice para contar entradas**: prefira `status()` para polling frequente. `searchStatistics` é null em IDX. Não expomos `percent_indexed` como progresso real, pois o servidor atualmente informa somente 0 ou 1.

As respostas são observações de comandos separados, sem snapshot transacional. Se o schema divergir entre IDX.INFO e FT.INFO, a inspeção recusa a resposta com `DataRetrievalFailureException`; repita após concluir a alteração administrativa. Estatísticas e status podem refletir momentos diferentes. As listas devolvidas são imutáveis.

## Comparar schema sem DDL

```java
for (IndexSchemaComparison comparison : indexes.compare(Customer.class)) {
    if (!comparison.compatible()) {
        System.out.println(comparison.index() + ": " + comparison.describe());
    }
}
// Ou somente um índice declarado na entidade:
var comparison = indexes.compare(Customer.class, "customers:city-age");
```

Cada diferença contém `property`, `expected` e `actual`. Exemplo:

```text
fields.size: expected=2, actual=1;
fields[0].alias: expected=city, actual=home;
fields[0].caseSensitive: expected=true, actual=false
```

São comparados família IDX/FT, formato JSON/HASH, prefixos, UNIQUE e posição, caminho, alias, tipo, separador e caixa dos campos. A validação de declarações usa as mesmas regras de `ensureIndexes`. O formato é normalizado: um campo HASH sem AS tem caminho igual ao nome; separadores padrão e caixa IDX são comparados pelos seus efeitos reais.

Índice ausente produz `exists=false`, `actual=null` e diferença `exists`. Erros de conexão, ACL e respostas inválidas propagam suas exceções; não viram “índice ausente”. Um nome não declarado na entidade é recusado antes de I/O.

`compatible()` indica somente correspondência de schema. Um índice BUILDING ou FAILED pode ter schema compatível; consulte também `actual().status()` e aguarde READY quando necessário. `ensureIndexes` utiliza o mesmo comparador e inclui as diferenças na exceção, mantendo a exigência de migração explícita.

## Explicar uma consulta derivada

```java
var diagnostics = new DatadamQueryDiagnostics(template);
var method = CustomerRepository.class.getMethod(
    "findByEmailStartingWithOrderByEmailAsc", String.class, Pageable.class);
DerivedQueryPlan plan = diagnostics.explain(CustomerRepository.class, method);

System.out.println(plan.index());      // nome do índice declarado escolhido
System.out.println(plan.kind());       // COMPOUND (IDX) ou SEARCH (FT)
System.out.println(plan.commands());   // por exemplo [IDX.QUERY, IDX.COUNT]
System.out.println(plan.reason());
plan.candidates().forEach(System.out::println);
```

Spring Boot fornece um bean `DatadamQueryDiagnostics` substituível. Também é possível construir a API manualmente. Para simular a ordem dinâmica de um método com Sort/Pageable, use `explain(repository, method, Sort.by(...))`; a combinação com OrderBy segue as mesmas regras e conflitos da execução. Métodos sem parâmetro dinâmico não aceitam uma ordem dinâmica no diagnóstico.

O relatório usa **o mesmo compilador e a mesma seleção de candidatos da execução**. Inclui a compatibilidade, pontuação e motivo de rejeição de cada índice: campo ausente/tipo incompatível, TAG dividido, caixa incompatível, campo IDX não exigido, OR/IN/negação que exige FT e restrições de ordenação. Entre planos compatíveis, vence a pontuação atual do planejador; empate é resolvido pelo nome do índice. Essa pontuação é uma regra de preferência, não uma estimativa de custo/latência do servidor.

O plano identifica IDX.GET para resultado único por tupla UNIQUE, IDX.COUNT para count/exists, QUERY+COUNT para Page IDX e FT.SEARCH para planos FT. `nativeSort` contém aliases FT; em IDX, a ordem é a tupla do índice e `descending` informa a inversão. O planejamento de uma lista não promete usar IDX.GET só porque o schema é UNIQUE.

A explicação não executa comandos nem recebe valores de predicados. Não verifica se o índice existe, está pronto ou se o usuário pode consultá-lo. Também não valida valores dependentes da chamada, como prefixos vazios, null ou caracteres não ASCII em IgnoreCase. Ela considera o mapeamento Jackson do template e as declarações do repositório; use `compare` e `status` para validar o servidor. Métodos anotados, CRUD, default e operadores não suportados são recusados. A ausência de plano compatível também informa os motivos dos candidatos na exceção.

## Permissões e exemplo local

`IDX.LIST`, `IDX.INFO` e `FT.INFO` exigem permissões próprias. São metadados globais, não limitados pelos padrões de chaves de documentos da ACL; estatísticas FT também não representam necessariamente apenas as chaves legíveis pelo usuário. Não exponha essas informações em uma rota pública da aplicação.

O Docker local inclui `index-observer`, autorizado somente a conexão e esses três comandos; ele não lê documentos nem executa DDL. O usuário `schema` ganhou `IDX.LIST`; as credenciais `app` continuam sem acesso à inspeção. O diagnóstico de consultas é local e não exige nenhum desses privilégios.

`IndexDiagnosticsApplication` usa a conexão administrativa configurada para listar, comparar os índices JSON/HASH de clientes e explicar um método derivado. Não executa DDL. Depois de instalar a aplicação, execute:

```powershell
$env:DATADAM_ADMIN_PASSWORD = 'local-schema-password'
java '-Dloader.main=io.datadam.demo.IndexDiagnosticsApplication' -cp datadam-demo/target/datadam-demo-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
# Acrescente --wait para aguardar até 30 segundos por índice existente.
```

Após alterar a ACL no arquivo do Docker, reinicie o serviço para carregar a configuração: `docker compose restart datadam`. Não remova o volume. A entrega 6.1 usa comandos já existentes e não exige novo patch C++.

`IndexDiagnosticsTest` cobre respostas, normalização, diferenças, estados, timeout, interrupção e propagação de falhas. `QueryDiagnosticsTest` compara planos com comandos de execução e cobre ordenação, aliases, UNIQUE, recusas e desempate. `IndexDiagnosticsIntegrationIT` valida JSON/HASH no Docker, ACL restrita, backfill UNIQUE com falha e equivalência com execução real. A suíte completa é executada por `scripts/run-local.ps1 -Restart -IntegrationTests -Background`.
