# Índices e consultas HASH

O item 4.2 adiciona schema e consultas IDX/FT para entidades com `storage = DatadamStorage.HASH`. As mesmas APIs de repositório retornam entidades completas a partir dos campos nativos da resposta, sem buscar cada documento novamente por HGETALL.

```java
@DatadamDocument(prefix = "customer:hash:", storage = DatadamStorage.HASH)
@DatadamIndex(name = "customers:city-age", fields = {
    @IndexField(path = "city", alias = "city"),
    @IndexField(path = "age", alias = "age", type = IndexField.Type.NUMERIC)})
@DatadamIndex(name = "customers:search", kind = DatadamIndex.Kind.SEARCH, fields = {
    @IndexField(path = "city", alias = "home"),
    @IndexField(path = "age", alias = "years", type = IndexField.Type.NUMERIC),
    @IndexField(path = "labels", alias = "tag", separator = ";", caseSensitive = true)})
public record Customer(@Id String id, String city, Integer age, String labels) {}
```

## Campos, aliases e schema

Para HASH, path é o **nome literal do campo armazenado**, não JSONPath. Uma propriedade Java renomeada com `@JsonProperty("home_city")` deve ser declarada com `path = "home_city"`. Objetos aninhados/coleções do [mapeamento HASH](hash-entities.md) ficam como JSON dentro de um campo: `address.city` não extrai automaticamente o membro city de um objeto address.

IDX HASH não aceita AS: alias deve ser igual a path. Essa limitação é validada antes do DDL. FT HASH aceita AS, e os aliases podem diferir dos nomes armazenados. No exemplo, a busca FT usa `@home` e a ordenação nativa usa `years`; a entidade retornada continua tendo city/age.

`ensureIndexes(Entity.class, timeout)` emite ON HASH ou ON JSON conforme o modelo. Continua usando conexão administrativa, criação idempotente, espera por backfill e validação explícita. Um índice existente com outro formato, prefixo, ordem/campos, UNIQUE, alias, modo de caixa ou separador gera DataIntegrityViolationException. Nenhum índice é removido ou recriado automaticamente. Informações de IDX.INFO e FT.INFO são combinadas para validar também o identificador real de um alias FT.

separator é uma nova opção de IndexField, disponível para TAG em HASH e JSON. String vazia usa o padrão do servidor; um valor explícito deve ser um único caractere ASCII diferente de NUL. NUMERIC com separador, caractere de vários bytes e CASESENSITIVE fora de TAG FT são recusados.

## Diferenças de valores indexados

| Aspecto | IDX HASH | FT HASH | JSON |
|---|---|---|---|
| TAG padrão | Campo inteiro, sem trim, sensível à caixa | Tokens separados por vírgula, com trim e remoção de vazios/duplicados; caixa ASCII ignorada | Valores selecionados pelo caminho; sem separador padrão |
| TAG com separator explícito | Tokens separados, com trim e remoção de vazios/duplicados | Mesmo procedimento, com o separador escolhido | Divide os valores string selecionados |
| CASESENSITIVE | Sempre diferencia caixa | Opcional em TAG | IDX sempre diferencia; FT usa a opção |
| Lista armazenada | O JSON da lista é texto de um campo HASH | O mesmo texto; não vira automaticamente um token por elemento | Arrays podem produzir um valor indexado por elemento |
| Objeto armazenado | JSON do objeto é texto do campo | JSON do objeto é texto do campo | Objetos selecionados não são TAG/NUMERIC indexáveis |
| NUMERIC | Texto do campo interpretado como número | Texto do campo interpretado como número | Valores numéricos selecionados pelo caminho |
| Campo ausente/NUMERIC inválido | Ausência de qualquer componente exclui a chave do IDX composto | A chave pode continuar no índice/presença, sem o valor desse campo | Mesma regra de presença de IDX/FT, aplicada aos valores JSON |

Exemplo: cidade `Alpha,Beta` é um único valor no IDX sem separador, mas dois tokens no FT HASH padrão. Um filtro FT `@home:{Alpha}` pode encontrar essa chave; isso não equivale a city igual a `Alpha`.

Para labels, o exemplo usa um campo String separado por `;`. A propriedade tags é uma List armazenada como JSON pelo codec HASH; indexar esse campo não a transforma em uma lista nativa de TAGs. Escolha uma representação textual dedicada quando quiser os tokens HASH do servidor. Não concatene listas silenciosamente no codec, pois isso perderia tipos e ambiguidades de conversão.

Valores numéricos inválidos não viram zero nem são corrigidos pela biblioteca. Uma chave escrita externamente com age inválido pode aparecer na presença FT, mas falhar na materialização como Integer se a consulta selecionar esse documento completo. Consultas numéricas não incluem esse valor; LIMIT 0 permite obter totais sem materialização. Precisão/ordem numérica seguem o índice nativo do Datadam, sem avaliação numérica alternativa no cliente.

## Consultas declaradas e programáticas

```java
public interface CustomerRepository extends DatadamRepository<Customer, String> {
    @IdxQuery(index = "customers:city-age", value = "city = ?0 age >= ?1 age < ?2")
    Page<Customer> byCityAndAge(String city, int minimum, int maximum, Pageable page);

    @FtQuery(index = "customers:search", value = "@home:{?0} @years:[?1 ?2]")
    Page<Customer> searchCityAndAge(String city, int minimum, int maximum, Pageable page);
}

repository.query("customers:city-age",
    CompoundQuery.all().where("city", "=", "SP").where("age", ">=", 18).desc(),
    PageRequest.of(0, 20));

repository.search("customers:search",
    new SearchQuery("@tag:{$t}", Map.of("t", "Java"), Sort.by("years")),
    PageRequest.of(0, 20));
```

Condições IDX usam os nomes nativos dos campos/aliases; FT usa aliases do schema. A ordenação IDX segue a sequência dos campos do índice, com DESC em CompoundQuery; FT aceita o SORTBY nativo suportado, com um campo. Sort em consultas declaradas/programáticas FT também usa nomes do schema, como years. Parâmetros permanecem argumentos vinculados; filtros, faixas, contagens e ordenação são executados no servidor.

A faixa da @IdxQuery acima exclui maximum por usar `<`; os dois extremos de `Between` derivado são inclusivos, assim como `[min max]` do FT. Esses contratos são iguais aos usados pelo exemplo JSON.

## Derivação de nomes

As propriedades Java são resolvidas por Jackson para o campo HASH raiz; o planejador compara esse campo com os caminhos do schema e usa o alias nativo correspondente. Os operadores e retornos já suportados continuam disponíveis quando existe um plano semanticamente compatível: igualdade, faixas numéricas, count/exists, Optional/List/Page/Slice e Top/First no subconjunto documentado pela biblioteca.

O HashCustomerRepository demonstra igualdade/lookup de email UNIQUE, igualdade de cidade com faixa/ordem de idade por IDX, count, exists, Slice/Top e consulta somente numérica por FT. A ordenação derivada usa a propriedade Java age e a converte para o alias FT years quando esse for o plano escolhido.

Para não mudar o significado de igualdade, o planejador **não usa TAGs com separação** para predicados escalares de igualdade/IN/IgnoreCase nem para ordenação escalar desse campo. Isso inclui o FT HASH com vírgula padrão, mesmo se CASESENSITIVE estiver ativado. Pode usar um IDX sem separador que cubra os predicados, ou recusar o método na criação do repositório se não existir plano correto. A mesma regra vale para um TAG JSON com separator explícito. Não há pós-filtragem em memória nem varredura para simular a semântica ausente.

Logo, `findByCityAndAgeBetweenOrderByAgeDesc` no exemplo usa IDX e mantém igualdade do campo completo, enquanto a consulta FT declarada por cidade usa explicitamente semântica de token. Consultas numéricas por FT continuam elegíveis, mesmo se outros campos do índice forem TAGs divididos. Métodos sobre propriedades aninhadas de um objeto dentro de um campo HASH são recusados; esses membros não são campos HASH independentes.

## Materialização, permissões e ciclo de vida

IDX.QUERY/IDX.GET e FT.SEARCH devolvem todos os campos HASH por padrão. O template usa HashEntityCodec e executa AfterConvert da mesma forma que em findById. Listas, Maps, nomes Jackson e campos opcionais são reconstruídos pelo tipo declarado. A resposta é validada contra o prefixo do modelo. Essas APIs não solicitam projeções parciais nem preenchem entidades com campos omitidos por RETURN; projeções são a próxima etapa 5.1.

Não há HGETALL adicional por resultado. O teste de ACL usa um leitor com IDX/FT e acesso somente a chaves `visible*`, sem permissão de HGETALL: conteúdo e total refletem apenas as chaves legíveis. count/exists nativos não materializam entidades nem executam AfterConvert. A criação de índices continua exigindo as credenciais administrativas; a aplicação permanece sem DDL.

Alterações HASH por save/HREPLACE atualizam IDX/FT e liberam os valores antigos ou removidos. Recusas UNIQUE preservam documento, TTL e donos dos índices. Chaves expiradas deixam de aparecer nos dois mecanismos. TTL, versão, auditoria e callbacks mantêm os contratos do CRUD 4.1; consultas não alteram esses dados.

Esta etapa não acrescenta novos comandos C++ ao patch HREPLACE: usa o suporte HASH de IDX/FT que o Datadam já possui. O requisito de servidor do CRUD HASH continua documentado em server-patches/README.md.

## Exemplo HTTP equivalente ao Customer

SchemaApplication também prepara HashCustomer idempotentemente. As rotas `/hash-customers`, `/hash-customers/derived`, `/hash-customers/search` e `/hash-customers/labels` cobrem IDX, derivação e FT; GET/POST/DELETE por ID completam o fluxo CRUD.

```powershell
$id = [guid]::NewGuid().ToString()
$data = @{ id = $id; name = 'Ana'; email = "$id@example.test"; city = 'SP'; age = 25; labels = 'Java; Spring'; tags = @('java', 'database'); details = @{ active = $true } }
Invoke-RestMethod http://127.0.0.1:8080/hash-customers -Method Post -ContentType application/json -Body ($data | ConvertTo-Json -Depth 5)
Invoke-RestMethod 'http://127.0.0.1:8080/hash-customers?city=SP&minimum=18&maximum=40'
Invoke-RestMethod 'http://127.0.0.1:8080/hash-customers/derived?city=SP&minimum=18&maximum=40'
Invoke-RestMethod 'http://127.0.0.1:8080/hash-customers/search?city=sp&minimum=18&maximum=40'
Invoke-RestMethod 'http://127.0.0.1:8080/hash-customers/labels?label=Java'
Invoke-RestMethod "http://127.0.0.1:8080/hash-customers/$id" -Method Delete
```

## Validação

HashIndexContractTest cobre geração/validação de DDL, separadores, aliases, diferenças de schema e materialização sem leituras extras. DerivedQueryValidationTest acrescenta nomes Jackson HASH, recusa de TAG dividido e recusa de propriedades aninhadas. HashQueriesIntegrationIT usa Datadam real no Docker para schema idempotente, faixa/ordem/paginação/totais, consultas declaradas/derivadas/programáticas, aliases, TAG/NUMERIC, valores ausentes/inválidos, atualização/remoção de campos, UNIQUE, TTL, ACL, callbacks e HTTP.

Execute `./scripts/run-local.ps1 -Restart -IntegrationTests -Background`.
