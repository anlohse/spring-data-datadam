# Spring Data Datadam

Biblioteca Java com repositórios Spring Data que persistem entidades como documentos JSON e consultam os índices nativos do Datadam. O projeto contém a biblioteca `spring-data-datadam` e uma aplicação Spring Boot HTTP `datadam-demo`.

O [roadmap de evolução](ROADMAP.md) acompanha as entregas concluídas, as próximas etapas e os critérios de conclusão de cada recurso.

## Executar no Windows

Pré-requisitos: Java 17 ou superior (validado com Java 25), Maven e Docker Desktop com engine Linux. Spring Boot 3.5.16 gerencia as versões de Spring Data Commons, Jackson e Jedis. A linha 3.5 suporta Java 25 conforme os [requisitos oficiais](https://docs.spring.io/spring-boot/3.5/system-requirements.html).

Na pasta deste README:

```powershell
# A imagem datadam:latest já está disponível nesta máquina.
.\scripts\run-local.ps1 -IntegrationTests -Background

# Para recompilar e substituir a instância iniciada pelo script:
.\scripts\run-local.ps1 -Restart -IntegrationTests -Background

# Se precisar construir a imagem a partir do projeto C++:
.\scripts\run-local.ps1 -BuildDatadam -IntegrationTests -Background

# Insere três clientes e executa consultas por HTTP; pode ser repetido.
.\scripts\sample-data.ps1

Invoke-RestMethod http://localhost:8080/
Invoke-RestMethod 'http://localhost:8080/customers?city=SP&minimum=18&maximum=40&descending=true'
Invoke-RestMethod 'http://localhost:8080/customers/search?city=sp&minimum=18&maximum=40'
Invoke-RestMethod 'http://localhost:8080/customers/derived?city=SP&minimum=18&maximum=40'
Invoke-RestMethod 'http://localhost:8080/customers/email?email=ana@example.test'
```

A API fica em `127.0.0.1:8080`; o Datadam em `127.0.0.1:6380`. `POST /customers` salva/substitui um cliente, `GET /customers/{id}` lê e `DELETE /customers/{id}` remove. Email repetido de outro ID retorna HTTP 409. A consulta composta usa mínimo inclusivo e máximo exclusivo; a busca FT usa ambos inclusivos. A paginação HTTP aceita `page` a partir de zero e `size` entre 1 e 100. A resposta é um DTO de paginação estável, com `content` e `page.totalElements`.

O processo em segundo plano grava PID em `datadam-demo/target/demo.pid` e logs em `datadam-demo/target/demo.log`. Para parar:

```powershell
Stop-Process -Id ([int](Get-Content .\datadam-demo\target\demo.pid))
docker compose down
```

O volume `datadam-spring-data_datadam-data` preserva os documentos e índices. `docker compose down` mantém esse volume. A imagem pode ser reconstruída com `docker compose build`; para mudar a localização do fonte C++, configure `DATADAM_SOURCE` (padrão `D:/cpp/workspaces/datadam`). Portas HTTP e Datadam podem ser configuradas, respectivamente, por `PORT` e pelo mapeamento no Compose junto com `DATADAM_PORT`.

## Usar a biblioteca em outra aplicação

`mvn install` instala o POM pai e a biblioteca no repositório Maven local. Não há publicação remota automática.

```xml
<dependency>
  <groupId>io.datadam</groupId>
  <artifactId>spring-data-datadam</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A aplicação consumidora deve gerenciar dependências com Spring Boot 3.5.x (esta implementação foi validada com 3.5.16). A configuração automática registra o pool, `DatadamCommands` e `DatadamTemplate`. Ative a descoberta de repositórios:

```java
@SpringBootApplication
@EnableDatadamRepositories(basePackageClasses = CustomerRepository.class)
public class Application { /* main padrão SpringApplication.run */ }
```

```yaml
datadam:
  host: localhost
  port: 6380
  username: app
  password: ${DATADAM_PASSWORD}
  timeout: 5s
  ssl: false
```

Há uma conexão RESP2 por comando, obtida de um pool Jedis. Por padrão, `datadam.validate-connection=true` executa PING ao obter a conexão para descartar conexões antigas após reinício do servidor; isso adiciona uma ida e volta por comando. A espera por uma conexão do pool é limitada por `datadam.timeout`. Não são enviados comandos de cluster, configuração do servidor ou ativação de notificações de keyspace. Apenas o database 0 é usado, como exige o Datadam.

## Modelar entidade e índices

O arquivo `datadam-demo/src/main/java/io/datadam/demo/Customer.java` é o exemplo completo. Cada entidade precisa de `@DatadamDocument(prefix = "...")` e exatamente um campo `@Id` do Spring Data, inclusive em records. O ID deve ser atribuído pela aplicação antes do save. As chaves são `prefix + id`; use prefixos exclusivos e sem sobreposição entre tipos.

```java
@DatadamDocument(prefix = "demo:customer:")
@DatadamIndex(name = "demo:customers:city-age", fields = {
    @IndexField(path = "$.city", alias = "city"),
    @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC)})
@DatadamIndex(name = "demo:customers:email", unique = true,
    fields = @IndexField(path = "$.email", alias = "email"))
public record Customer(@Id String id, String email, String city, int age) {}
```

`@DatadamIndex` é repetível. O tipo padrão é `COMPOUND` (`IDX.CREATE ... ON JSON`); `kind = SEARCH` cria `FT.CREATE ... ON JSON`. Campos aceitam JSONPath, alias e tipos TAG/NUMERIC. Apenas TAG de índices FT aceita `caseSensitive = true`. TAG de IDX sempre diferencia maiúsculas; TAG de FT ignora maiúsculas ASCII por padrão. Arrays JSON podem produzir vários valores indexados.

A ordem dos campos em um índice composto é parte do contrato: igualdade nos campos iniciais e faixa no seguinte selecionam uma fatia contígua. Campos ausentes ou numéricos inválidos excluem o documento de IDX. FT mantém documentos que não têm todos os campos. UNIQUE é imposto atomicamente pelo servidor e convertido em `DuplicateKeyException`.

## TTL de entidades

Use `@DatadamDocument(timeToLive = 60, prefix = "note:")` para um prazo padrão em segundos e `@DatadamTimeToLive(unit = TimeUnit.MILLISECONDS)` em um campo integral ou Duration para substituir o prazo por entidade. TTL positivo é renovado em cada `save`; sem política de TTL, a expiração existente é preservada. `repository.persist(id)` remove explicitamente a expiração.

O exemplo completo está na API `/temporary-notes`. Consulte [TTL de entidades](docs/entity-ttl.md) para regras, permissões, exemplos e limites de atomicidade e recuperação após crash do servidor.

## Repositórios e consultas

```java
public interface CustomerRepository extends DatadamRepository<Customer, String> {
    @IdxQuery(index = "demo:customers:city-age",
              value = "city = ?0 age >= ?1 age < ?2")
    Page<Customer> byCityAndAge(String city, int minimum, int maximum, Pageable page);

    @FtQuery(index = "demo:customers:search", value = "@city:{?0} @age:[?1 ?2]")
    Page<Customer> search(String city, int minimum, int maximum, Pageable page);
}
```

O módulo usa a infraestrutura `RepositoryFactoryBeanSupport`/`RepositoryFactorySupport` do Spring Data Commons. Repositórios estendem `ListCrudRepository` e acrescentam `query`, `count`, `lookup` e `search`. Métodos com consulta anotada devem ter exatamente uma anotação de consulta e retornar `Page<T>` ou `List<T>`. Para essas consultas anotadas, sem `Pageable`, o limite é 100; `List<T>` retorna a página solicitada. As posições `?N` correspondem aos argumentos Java, contando desde zero. FT vincula valores usando `PARAMS` (`$pN`), e IDX envia cada valor como um argumento RESP separado. Não há interpolação de valores na expressão. Métodos sem essas anotações são derivados do nome, com as regras da seção seguinte.

```java
var query = CompoundQuery.all()
    .where("city", "=", "SP")
    .where("age", ">=", 18)
    .where("age", "<", 40)
    .desc();
Page<Customer> page = repository.query("demo:customers:city-age", query, PageRequest.of(0, 20));
long count = repository.count("demo:customers:city-age", query);
List<Customer> byEmail = repository.lookup("demo:customers:email", "ana@example.test");

var search = new SearchQuery(
    "@city:{$city} (@age:[18 25] | @tag:{java}) -@tag:{legacy}",
    Map.of("city", "SP"), Sort.by(Sort.Direction.DESC, "age"));
Page<Customer> results = repository.search("demo:customers:search", search, PageRequest.of(0, 20));
```

`query` retorna em ordem do índice; use `.desc()` ou `@IdxQuery(descending = true)` para inverter. `Pageable.sort` em IDX é rejeitado para não prometer uma ordenação arbitrária. FT aceita um campo de ordenação, em `SearchQuery.sort` ou no `Pageable`; critérios conflitantes são rejeitados. Consultas só aceitam índices declarados para a entidade e o tipo correto (IDX/FT).

## Consultas derivadas de nomes

Sem `@IdxQuery`/`@FtQuery`, a biblioteca usa o parser `PartTree` do Spring Data para compilar métodos em IDX ou FT. O exemplo real está em `CustomerRepository`:

```java
Optional<Customer> findByEmail(String email);
long countByCityAndAgeLessThan(String city, int maximum);
boolean existsByEmail(String email);
Page<Customer> findByCityAndAgeBetweenOrderByAgeDesc(
    String city, int minimum, int maximum, Pageable page);
Slice<Customer> findByCityAndAgeGreaterThanEqualOrderByAgeAsc(
    String city, int minimum, Pageable page);
List<Customer> findTop2ByCityAndAgeGreaterThanOrderByAgeDesc(String city, int minimum);
List<Customer> findByCityInOrderByAgeAsc(Collection<String> cities);
Optional<Customer> findFirstByCityOrderByAgeDesc(String city);
```

A API de exemplo expõe `GET /customers/derived?city=SP&minimum=18&maximum=40`: a consulta é derivada, `Between` inclui os dois limites e a ordem é idade decrescente. O endpoint por email também usa `findByEmail` sem anotação.

| Recurso | Comportamento |
|---|---|
| Igualdade (`Is`, `Equals` ou sem sufixo) | TAG exato ou igualdade NUMERIC |
| `Between` | Faixa numérica inclusiva nos dois limites |
| `GreaterThan`, `GreaterThanEqual`, `LessThan`, `LessThanEqual` | Faixas numéricas com limites correspondentes |
| `And`, `Or` | Interseção/união; grupos do parser Spring são preservados com parênteses em FT |
| `In` | Coleção ou array de valores escalares; usa união em FT; coleção vazia é rejeitada |
| `True`, `False` | Igualdade em uma propriedade booleana |
| `IgnoreCase`, `AllIgnoreCase` | Exigem TAG FT sem CASESENSITIVE; aceitam parâmetros ASCII, conforme o folding nativo do Datadam |
| `OrderBy`, `Sort`, `Pageable.sort` | Ordenação nativa compatível com o índice; ordens conflitantes são rejeitadas |
| `TopN`, `FirstN` | Limite nativo para List; `Top1`/`First1` também aceitam entidade/Optional |
| `Page<T>` | Conteúdo e total; IDX faz uma contagem separada |
| `Slice<T>` | Busca tamanho da página + 1; IDX não faz contagem |
| `List<T>` | Sem Pageable/Top/First, retorna todos os resultados, sem limite implícito de 100 |
| Entidade ou `Optional<T>` | No máximo um resultado; duplicidade lança `IncorrectResultSizeDataAccessException` |
| `countBy...` / `existsBy...` | Retornam long/Long e boolean/Boolean; IDX usa COUNT ou QUERY LIMIT 0 1 NOCONTENT |

A biblioteca prefere índices IDX compatíveis, pontuando igualdade nos campos iniciais e faixa no seguinte. Empates são resolvidos pelo nome do índice, de forma determinística. Lookup de igualdade completa em UNIQUE usa `IDX.GET`. OR/IN e outras combinações que precisam de FT são compiladas em `FT.SEARCH` com `PARAMS`. Não há SCAN nem filtragem Java para executar esses métodos.

A escolha do índice preserva o significado da consulta:

- Todos os campos de um índice composto precisam estar exigidos pelos predicados. Usar `(city, age)` para apenas `findByCity` perderia documentos sem age; nesse caso é necessário um IDX somente por city ou um FT compatível.
- Igualdade de String diferencia maiúsculas. Índices FT usados para igualdade exata ou ordenação TAG devem declarar `caseSensitive = true`; TAG FT padrão serve para `IgnoreCase`. A demonstração mantém o FT original para busca flexível e adiciona `demo:customers:derived` com TAGs exatos. O alias `home` desse índice corresponde à propriedade Java `city`.
- Nomes de propriedades Java são resolvidos pelos nomes JSON de Jackson, inclusive `@JsonProperty` e naming strategy. A derivação aceita caminhos de propriedades escalares com membros JSON simples; coleções/maps não são derivados. Os JSONPaths declarados nos índices devem corresponder ao caminho serializado (por exemplo `$.city`).
- Ordenação IDX deve seguir os campos variáveis do índice; campos fixados por igualdade podem ser omitidos da ordem. FT aceita um único campo de ordenação. A ordenação solicitada nunca é aplicada depois de paginar.
- Sem índice compatível para os predicados e a ordem estática, a criação do repositório falha. Uma ordem dinâmica incompatível falha antes de enviar a consulta. Não há criação de schema automática como efeito da derivação.

Os parâmetros não podem ser null; TAG vazio e IN vazio são rejeitados. Predicados como `IsNull`, `Containing`, `Like`, `Not`, `NotIn`, exclusão derivada, projeções, parâmetros Limit/scrolling e comparação textual por faixa ainda não são suportados e falham na inicialização. `Top`/`First` não podem ser combinados com Pageable, count ou exists; retorno de entidade/Optional também não aceita Pageable. As consultas anotadas continuam disponíveis para expressar diretamente recursos nativos fora desse subconjunto.

A sintaxe segue os [nomes de consultas do Spring Data](https://docs.spring.io/spring-data/commons/reference/repositories/query-keywords-reference.html), respeitando o subconjunto suportado por este módulo.

## Schema, backfill e ACL

Criação de índices é uma etapa explícita de migração:

```java
// commandsAdmin deve usar uma conexão com o usuário de schema.
new DatadamIndexOperations(commandsAdmin)
    .ensureIndexes(Customer.class, Duration.ofSeconds(30));
```

`ensureIndexes` cria os índices ausentes, aguarda `IDX.INFO state=ready`, detecta `failed` e valida a definição existente (prefixo, tipo JSON, campos/ordem, UNIQUE e modo de case dos TAGs FT). Definição incompatível falha sem descartar índices ou dados. Um backfill UNIQUE com duplicatas falha; corrija os dados e migre explicitamente. A criação é idempotente. A espera também se aplica a FT para que a inicialização não aceite resultados parciais durante backfill.

O script executa `SchemaApplication` antes da API, com um usuário `schema`. O processo da API usa apenas `app`. A configuração de desenvolvimento está em `docker/datadam.conf`:

- `default off` exige autenticação; `protected-mode yes` permanece ativo.
- `schema` pode criar, inspecionar e remover índices; a API não recebe esses comandos.
- `app` pode ler/gravar documentos somente em `demo:customer:*` e `demo:temporary:*`, consultar índices e executar os comandos necessários ao TTL.
- `reader` só lê chaves `demo:customer:visible*`, para testar filtros ACL de resultados e totais.
- O memcache está desativado e apenas o loopback do host recebe a porta publicada.

As senhas incluídas são exclusivamente para desenvolvimento local. A configuração é montada como somente leitura e as regras ACL estão nela; não é usado `ACL SAVE`, `aclfile` ou `CONFIG REWRITE`. A documentação C++ tem uma menção residual a `protected-mode` na lista de itens não suportados, mas a seção dedicada, a configuração e a implementação o suportam; a execução local mantém a opção habilitada.

## Testes e limites desta versão

```powershell
docker compose up -d --no-build
mvn verify -Pintegration
mvn install
```

`mvn test` executa testes de contrato e validação da derivação sem depender do Docker. `mvn verify -Pintegration` executa adicionalmente testes de integração reais, que falham se o Datadam não estiver acessível; nenhuma troca por Redis comum ou mock é feita. A suíte usa os usuários da configuração local e cria/remove apenas suas próprias chaves `it-*`/`visible-it-*`, além de um índice temporário de teste. Não usa FLUSHALL. Os testes cobrem CRUD JSON, faixas, ordem/páginas, UNIQUE e concorrência, backfill com duplicatas, schema incompatível, manutenção automática após JSON.NUMINCRBY, FT com AND/OR/NOT, parâmetros literais, TTL, ACL e HTTP. Para derivação, verificam também comandos usados (lookup, contagem e Slice), limites inclusivos/exclusivos, OR/IN, case, campos ausentes, ordenação dinâmica, resultados únicos e listas acima de 100 registros.

Se o Maven falhar com PKIX nesta máquina, o script já usa o repositório de certificados confiáveis do Windows. Para execução manual, configure `MAVEN_OPTS` com `-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`, mantendo a validação TLS.

Escopo de 0.1:

- Mapeamento de repositórios em JSON. Comandos HASH, JSON adicionais e projeções nativas podem ser enviados com `DatadamCommands.execute`, mas não são automaticamente convertidos para entidades HASH.
- Consultas anotadas, derivação de nomes e API programática. Repositórios reativos, transações Spring, optimistic locking e geração de IDs ainda não estão implementados. Derivação é limitada ao subconjunto documentado acima.
- `Page` de IDX faz `IDX.COUNT` e `IDX.QUERY` separados. Sob concorrência, conteúdo e total podem refletir momentos distintos. Para somente contagem, use `count(index, query)`; FT já devolve o total no mesmo comando.
- `findAll`, `count()` e `deleteAll()` percorrem o prefixo usando SCAN TYPE ReJSON-RL. Para consultas de negócio e grandes coleções, prefira índices; o `DBSIZE` do Datadam é uma estimativa global.
- `saveAll` e exclusões em lote são sequenciais e podem ter êxito parcial. Sem política de TTL, `save` faz JSON.SET e mantém o prazo existente. Com TTL, usa um script isolado para gravar e expirar, com os limites de durabilidade descritos na documentação de TTL. Não há emulação de índices com sets de Redis.
- FT oferece TAG/NUMERIC; não oferece TEXT, GEO, VECTOR, agregações ou scoring. Opções de ordenação não suportadas são rejeitadas.
- A API de exemplo tem bind local; autenticação HTTP e implantação externa estão fora do escopo desta aplicação de teste.

Fontes do protocolo usadas: `D:/cpp/workspaces/datadam/docs/secondary-indexes.md`, `search.md`, `json.md`, a seção de diferenças de `commands.md` e ACL/protected-mode de `security.md`. A escolha de uma factory específica segue a infraestrutura de [repositórios Spring Data](https://docs.spring.io/spring-data/jpa/reference/repositories/custom-implementations.html).
