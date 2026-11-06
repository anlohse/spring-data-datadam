# Projeções nativas IDX/FT

O item 5.1 seleciona somente as propriedades solicitadas na consulta nativa e materializa outro tipo de retorno. Não carrega entidades completas para depois reduzi-las no cliente e não executa JSON.GET/HGETALL por resultado.

## Primeiro conjunto suportado

- DTOs em records, pelo construtor canônico.
- Interfaces fechadas com getters JavaBean sem parâmetros, inclusive getters herdados.
- Consultas anotadas com @IdxQuery/@FtQuery retornando List ou Page.
- Consultas programáticas por ProjectionSpec, nos repositórios e no template.
- Propriedades serializáveis da raiz da entidade, incluindo nomes Jackson e tipos escalares/coleções/objetos completos armazenados nessas propriedades.

Propriedades projetadas devem usar tipos anuláveis, por exemplo Integer em vez de int. Primitivos, Object e Optional na raiz do DTO/getter são recusados antes de consultar. Isso impede que campo ausente vire silenciosamente zero/false. O tipo deve ser compatível com o da propriedade de origem; containers precisam do mesmo tipo parametrizado declarado. São respeitados os módulos/conversores globais Jackson, mas a projeção não reproduz todo o contexto de anotações de deserialização de cada propriedade da entidade. Conversões contextuais personalizadas precisam de formato compatível com o tipo solicitado.

Interfaces com SpEL (@Value direto ou por metaanotação), métodos default, setters ou outros métodos que não sejam getters são recusadas. DTOs de classe mutável, projeções dinâmicas via Class, projeções aninhadas por caminhos de membros e derivação de nomes com retorno projetado ficam fora deste primeiro conjunto. Selecionar uma propriedade raiz que contém uma lista/Map/objeto retorna o conteúdo completo desse valor; não é seleção de seus membros internos.

## Consultas anotadas

```java
public record CustomerSummary(String name, @ProjectionProperty("city") String town, Integer age) {}

public interface CustomerView {
    String getName();
    String getCity();
    Integer getAge();
}

public interface CustomerRepository extends DatadamRepository<Customer, String> {
    @IdxQuery(index = "demo:customers:city-age", value = "city = ?0", descending = true)
    Page<CustomerSummary> summariesByCity(String city, Pageable page);

    @FtQuery(index = "demo:customers:search", value = "@city:{?0}")
    Page<CustomerView> viewsByCity(String city, Pageable page);
}
```

O tipo genérico do retorno define a projeção: se for a entidade, mantém o carregamento completo; se for outro tipo, a definição é validada e a seleção nativa é criada. Um DTO que seja @DatadamDocument ou a própria classe da entidade é recusado como alvo de ProjectionSpec. Não há uma entidade incompleta apresentada como totalmente materializada.

O nome de cada componente/getter é, por padrão, o nome Java da propriedade de origem. ProjectionProperty permite outro mapeamento, como town → city. Não informe alias nativo ou JSONPath nessa anotação. Ela aponta para a propriedade Java; o plano resolve depois o nome Jackson e o identificador/alias nativo.

List e Page usam a mesma ordem e limite nativos. Page IDX executa IDX.COUNT e IDX.QUERY; List IDX usa somente IDX.QUERY. FT retorna conteúdo e total na mesma chamada. Sem Pageable, consultas anotadas usam o limite padrão existente de 100; IDX permite Pageable.unpaged para seleção sem limite, enquanto FT exige página limitada.

## Consultas programáticas

```java
ProjectionSpec<CustomerSummary> summary = ProjectionSpec.of(CustomerSummary.class);
Page<CustomerSummary> page = repository.projectQuery(
    "demo:customers:city-age", CompoundQuery.all().where("city", "=", "SP").desc(),
    PageRequest.of(0, 20), summary);

public record NameAndTags(String display, List<String> tags) {}
var fields = ProjectionSpec.of(NameAndTags.class).field("display", "name");
Page<NameAndTags> result = repository.projectSearch(
    "demo:customers:search", new SearchQuery("@city:{$c}", Map.of("c", "SP"), Sort.by("age")),
    PageRequest.of(0, 20), fields);
```

ProjectionSpec é imutável. field recebe nome da propriedade do alvo e nome Java da origem; o mapeamento explícito prevalece sobre ProjectionProperty. Alvos desconhecidos, propriedades não serializadas, fontes aninhadas, tipos incompatíveis e bindings repetidos são recusados. Quando dois campos do DTO usam a mesma origem, ela é solicitada uma única vez e o valor é disponibilizado para os dois componentes.

O template expõe projectQuery/projectSearch com EntityModel e ProjectionSpec. projectQueryContent e projectSearchResults oferecem a forma de conteúdo/total usada pelos métodos anotados. Ordenação IDX segue o índice e CompoundQuery.desc; FT usa os nomes nativos do schema em Sort, como years no exemplo HASH. Não se pode combinar sorts conflitantes nem pedir FT ilimitado.

## JSON, HASH e aliases

No JSON, a seleção usa caminhos literais de membros raiz, resolvidos por Jackson, e **RETURNJSON**. A resposta de cada campo contém JSON tipado: a string `"null"` continua uma string e o valor JSON null continua null. Arrays e objetos mantêm sua estrutura e int64 não é convertido para número Lua. A precisão de números JSON segue o valor que o servidor armazena/retorna.

No HASH, a seleção usa RETURN e os tipos declarados do mapeamento HASH: strings nativas permanecem strings; números, datas, bytes e JSON de containers são convertidos pelo codec. BigDecimal e int64 mantêm sua precisão, incluindo a escala decimal. Valores dinâmicos dentro de Map seguem a configuração Jackson da aplicação; a passagem interna necessária para reconstruir o campo não muda essa configuração.

Aliases FT e nomes Jackson são tratados separadamente. Por exemplo, city pode ser armazenada em home_city e indexada como home; a projeção continua podendo declarar `@ProjectionProperty("city") String town`. O campo certo é solicitado e renomeado no DTO, sem uma consulta extra. Um alias de índice que oculta outra origem e impede seleção inequívoca é recusado.

Campo ausente e JSON null são representados como null no componente/getter anulável. A seleção não descarta a linha nem inventa defaults. Construtores de DTO que recusam esses valores causam DataRetrievalFailureException; a biblioteca não omite essa linha para produzir uma página aparentemente válida. Valores nativos incompatíveis com o tipo projetado também falham explicitamente, sem coerção comum de string para número ou truncamento de float para inteiro.

## Ciclo de vida e limites

Projeções são modelos de leitura: não passam por BeforeConvert/BeforeSave/AfterSave/AfterConvert de entidade, não preenchem auditoria, não incrementam versão e não alteram TTL. O construtor canônico do record é executado para criar o DTO. Listas/Maps retornados são valores locais, não uma sessão de entidade gerenciada nem uma operação de escrita.

As consultas de entidades completas continuam usando o caminho anterior e executando AfterConvert. A API rejeita alvos de entidade para evitar salvar uma versão parcial como se estivesse completa. A projeção não traz automaticamente ID, versão, email ou outros campos não solicitados.

Filtros, ordem, limites e contagens são executados pelo servidor. As permissões de leitura de chaves filtram conteúdo e total, como nas consultas normais. A conta projection-reader do exemplo não possui JSON.GET nem HGETALL e consegue consultar os campos selecionados dos registros visíveis. Isso não constitui ACL por campo: uma conta com acesso ao índice/chave pode executar outras consultas permitidas pelo servidor.

Não são acrescentadas transações entre IDX.COUNT e IDX.QUERY: sob alterações concorrentes, mantêm os limites de consistência/paginação já existentes. Não há repetição automática, filtragem de páginas no cliente ou fallback para carregar o documento inteiro. O servidor ainda precisa ler/interpretar o documento JSON para extrair os campos; a seleção reduz o tráfego e a materialização na aplicação, sem prometer custo interno proporcional apenas aos campos retornados. Projeções derivadas por nome, joins, expressões computadas, caminhos multivalorados e seletores JSONPath arbitrários não fazem parte desta entrega.

## Dependência de servidor RETURNJSON

RETURN existente devolve strings JSON sem aspas, tornando indistinguíveis uma string literal `null` e um valor JSON null. RETURNJSON foi acrescentado ao Datadam C++ para resolver isso sem mudar RETURN nem fazer uma leitura por documento. A sintaxe segue RETURN; em FT, o count também inclui os tokens AS/alias. Retorna o primeiro valor de cada caminho e omite caminhos ausentes. Em HASH, a extensão mantém a representação nativa de RETURN; a biblioteca usa RETURN normal para esse formato.

Projeções JSON precisam do [patch typed-return.patch](../server-patches/typed-return.patch), já aplicado e testado na imagem local. Projeções HASH usam RETURN existente; as escritas HASH continuam precisando do patch HREPLACE. As instruções de aplicação e testes estão em [server-patches/README.md](../server-patches/README.md). Servidores antigos falham explicitamente na opção não suportada, sem buscar toda a entidade como alternativa.

## Exemplo HTTP

CustomerProjectionController usa os mesmos dados dos endpoints Customer e HashCustomer:

```powershell
# Depois de criar clientes em /customers e /hash-customers:
Invoke-RestMethod 'http://127.0.0.1:8080/customer-projections/json?city=SP&page=0&size=10'
Invoke-RestMethod 'http://127.0.0.1:8080/customer-projections/hash?city=SP&page=0&size=10'
Invoke-RestMethod 'http://127.0.0.1:8080/customer-projections/json/search?city=SP&page=0&size=10'
Invoke-RestMethod 'http://127.0.0.1:8080/customer-projections/hash/search?city=SP&page=0&size=10'
```

As duas primeiras rotas retornam o record name/town/age; as rotas search retornam a interface name/city/age. Email, ID, tags e details não são selecionados nem serializados nessas respostas. Os testes HTTP também verificam que os detalhes internos do proxy não aparecem.

## Validação

ProjectionContractTest valida o protocolo de seleção, DTOs/interfaces, mapeamentos, tipos, nulls, coleções, precisão/conversores, limites e ausência de leituras extras. ProjectionIntegrationIT usa Datadam real para consultas anotadas List/Page, consultas programáticas, JSON/HASH, aliases, campos ausentes/null, containers, BigDecimal/int64, callbacks, TTL/conteúdo inalterados, ACL sem leitura completa, expiração e HTTP.

O estágio Docker test do servidor passou com 186 testes nativos e integração/persistência. A suíte Java passou com 262 testes (120 unitários e 142 de integração), incluindo 12 testes unitários e 16 de integração novos de projeções. Execute `./scripts/run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background` após aplicar os patches de servidor que ainda faltarem no seu checkout.
