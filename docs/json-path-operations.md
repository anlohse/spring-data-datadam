# Leitura e alterações simples por JSONPath

O item 3.1 oferece operações nativas JSON.GET, JSON.SET e JSON.DEL, vinculadas ao tipo da entidade e ao seu prefixo. A aplicação envia o caminho e o valor da mudança, sem carregar, alterar e reenviar o documento inteiro.

```java
DatadamJsonOperations<Customer> json = template.json(Customer.class);
JsonPath<String> city = JsonPath.of("$.city", String.class);

JsonReadResult<String> result = json.read(id, city);
boolean written = json.set(id, city, "Curitiba");
long removed = json.remove(id, JsonPath.of("$.name", String.class));
```

O ID é convertido em chave pelo mesmo EntityModel dos repositórios. Nunca passe a chave completa no lugar do ID. As operações respeitam a ACL do usuário de aplicação e a manutenção de índices pelo servidor.

## Tipo e forma da leitura

O tipo do JsonPath representa **cada valor selecionado**, não o array externo do protocolo JSONPath. Use wrappers para escalares que podem ser null, JsonNode para estruturas dinâmicas e TypeReference para coleções parametrizadas:

```java
var tags = JsonPath.of("$.tags", new TypeReference<List<String>>() {});
json.read(id, tags).values(); // [["java", "database"]]: um match, cujo valor é uma lista
json.read(id, JsonPath.of("$.tags[*]", String.class)).values(); // ["java", "database"]: dois matches
json.read(id, JsonPath.of(".tags", new TypeReference<List<String>>() {})).values(); // [["java", "database"]]
```

A API normaliza a diferença entre o array externo de JSONPath e o valor único dos caminhos legados, sem achatar arrays que pertencem ao documento. Os resultados usam uma lista imutável que aceita valores null:

| Situação | keyExists | values |
|---|---|---|
| Chave ausente | false | lista vazia |
| JSONPath sem matches, com chave existente | true | lista vazia |
| Um valor JSON null | true | lista com um elemento null |
| Um valor escalar | true | lista com esse valor |
| Vários matches JSONPath | true | um elemento por match, em ordem do servidor |
| Caminho legado inexistente | — | exceção do servidor; não é convertido em ausência |

Com JsonNode, JSON null é representado por NullNode, conforme Jackson. JSON.GET com caminho `$` também tem envelope de matches; a raiz legada `.` retorna diretamente o documento. Ambos viram uma lista com um elemento.

Leituras aceitam os seletores que o Datadam suporta: membros, índices, wildcards, descendentes, filtros, slices e unions para JSONPath; o subconjunto legado segue o servidor. Sintaxe inválida e WRONGTYPE são propagados como InvalidDataAccessResourceUsageException. Erros de ACL continuam PermissionDeniedDataAccessException. Falha ao converter o valor para o tipo solicitado gera DataRetrievalFailureException.

A cópia do ObjectMapper mantém módulos, nomes e conversores da aplicação e desativa coerções escalares comuns: uma string numérica não vira Integer, um número não vira String e um float não é truncado para inteiro. Conversores Jackson personalizados continuam tendo sua própria semântica. A configuração do mapper compartilhado não é alterada.

Essas são leituras de valores JSON, sem materialização pelo ciclo do repositório: não executam AfterConvert, mesmo se o tipo solicitado coincidir com a classe da entidade.

## Definir e remover

Nesta etapa, as escritas aceitam somente caminhos singulares não raiz: membros e índices, inclusive nomes entre aspas, nomes com escapes e índices negativos. Exemplos: `$.address.city`, `.tags[0]`, `$['metadata']['display.name']`. Wildcards, filtros, unions, slices, descendentes e as raízes `$` e `.` são recusados antes do transporte. Leituras continuam podendo usar esses seletores.

`set` envia um único JSON.SET com o valor serializado. Retorna true quando o servidor confirma OK, inclusive se o valor já era igual, e false quando o caminho não pode ser criado/alterado. Um null Java é enviado como JSON `null`; não significa remoção. O valor é verificado contra o tipo declarado pelo JsonPath antes de enviar.

As regras nativas são preservadas:

- Um membro final ausente pode ser criado se o objeto pai já existe.
- Pais ausentes, pais escalares e índices fora do array não criam estruturas intermediárias: set retorna false.
- Uma chave ausente/expirada não pode ser criada por um caminho não raiz: set propaga o erro do servidor. A API não ressuscita documentos.
- `remove` envia um único JSON.DEL e retorna o número removido, zero ou um para os caminhos permitidos. Caminho/chave ausente retorna zero. Remover um índice de array desloca os seguintes, como no servidor.

O tipo do JsonPath valida o valor enviado ou recebido; não é uma validação completa do schema da entidade. Por exemplo, declarar deliberadamente um caminho de Integer como String pode produzir um documento que o repositório não consegue materializar depois. Use tipos coerentes com seu modelo. A API não executa Bean Validation ou validações do construtor da entidade nas alterações parciais.

O item 3.2 adiciona [incrementos, operações de arrays e merge](json-native-mutations.md), com as mesmas proteções para escritas. NX/XX não fazem parte desta API.

## Políticas da entidade

Alterações parciais desta etapa são destinadas a documentos simples. Para preservar os contratos já entregues, a API recusa set/remove com InvalidDataAccessApiUsageException nos seguintes casos:

- Entidade com `@Version`: não ignora a versão esperada nem faz incremento separado.
- Entidade com metadados de auditoria, inclusive embutidos, ou que implemente Auditable. A recusa ocorre mesmo sem preenchimento automático habilitado.
- Tipo com BeforeConvert, BeforeSave ou AfterSave registrado para ele. Callbacks de outros tipos não impedem a alteração; tipos genéricos não resolvidos são tratados conservadoramente. AfterConvert isolado não impede, pois a API não materializa entidades.
- Caminho que modifica/remove o ID ou seus descendentes, considerando os nomes Jackson, aliases de leitura e escapes do caminho.
- Caminho que altera/remove a propriedade `@DatadamTimeToLive` ou seus descendentes/aliases.

Use `repository.save(entity)` para os casos que precisam desses recursos. O suporte a mudanças parciais com versão/auditoria não está incluído nesta etapa; exigirá uma operação que aplique todas as políticas no mesmo protocolo de escrita. Não há opção no JsonPath para ignorar essas proteções.

A configuração automática consulta callbacks registrados no contexto Spring, incluindo ancestrais, e considera tipos relacionados por herança. Templates manuais construídos com comandos/mapper/geradores e sem callbacks permitem alterações nos modelos simples. O construtor que recebe EntityCallbacks opaco não consegue inspecionar seus registros e bloqueia todas as alterações parciais de forma conservadora. O novo construtor com predicado por Class permite declarar os tipos com callbacks; quem o configura deve incluir todos os callbacks aplicáveis e tratar genéricos desconhecidos como aplicáveis a todos. Leituras continuam disponíveis em todos esses casos.

`DatadamCommands` permanece uma API de comandos diretos, fora do ciclo de entidades. Não use comandos diretos como forma de contornar a versão, auditoria ou validação exigida pela aplicação.

## TTL, índices e atomicidade

Cada set/remove é um único comando nativo, executado pelo servidor sob a trava da chave. Uma sequência read/set, dois sets ou set/remove **não** é uma transação: outra conexão pode escrever entre os comandos. Não há retry automático ou proteção contra sobrescrita de outra alteração no mesmo caminho de um documento sem versão. Alterações em caminhos diferentes não reenviam valores de outros campos lidos anteriormente.

Esses comandos preservam a data absoluta de expiração, inclusive em entidades com TTL padrão ou por instância: não reiniciam o prazo e não precisam de PEXPIRE. Para mudar a política declarada de TTL, use save. `persist(id)` continua sendo a operação explícita para remover expiração.

IDX e FT são atualizados pelo servidor. Mudar um campo indexado retira os valores antigos e publica os novos; remover o campo também atualiza os índices. Uma recusa UNIQUE gera DuplicateKeyException e preserva documento, data absoluta de expiração e os donos anteriores dos valores indexados.

O servidor lê, interpreta e regrava o documento inteiro internamente para uma mudança JSON parcial. O ganho desta API é evitar o tráfego e a reconstrução do documento na aplicação; o custo de processamento do servidor continua proporcional ao tamanho do documento. Não são adicionadas garantias de transação ou de durabilidade além das já oferecidas pelo comando nativo. Falhas de transporte podem deixar o resultado incerto.

## Exemplo HTTP

O CustomerJsonController oferece operações sobre Customer, um documento sem versão/auditoria/callbacks de gravação:

```powershell
$id = [guid]::NewGuid().ToString()
$customerData = @{ id = $id; name = 'Ana'; email = "$id@example.test"; city = 'SP'; age = 32; tags = @('java', 'database') }
Invoke-RestMethod http://127.0.0.1:8080/customers -Method Post -ContentType application/json -Body ($customerData | ConvertTo-Json)
$url = "http://127.0.0.1:8080/customers/$id/json"
Invoke-RestMethod "$($url)?path=$.tags"
Invoke-RestMethod "$url/city" -Method Put -ContentType application/json -Body (@{ value = 'Curitiba' } | ConvertTo-Json)
Invoke-RestMethod "$url/name" -Method Delete
Invoke-RestMethod "http://127.0.0.1:8080/customers/$id" -Method Delete
```

GET aceita um caminho de leitura e retorna keyExists/values como JSON dinâmico. PUT city recebe um valor String (ou null explícito) e retorna written; DELETE name retorna removed. Outros campos de negócio não são expostos para escrita por esse controller de demonstração. Caminhos inválidos recebem HTTP 400 e conflitos UNIQUE recebem HTTP 409.

## Validação

JsonOperationsTest cobre envelopes, null, tipos, erros, aliases e restrições antes do transporte. JsonPathIntegrationIT usa Datadam real em Docker para caminhos JSONPath/legados, arrays, ausência, tipos incompatíveis, TTL absoluto, IDX/FT, UNIQUE, ACL, políticas de versão/auditoria/callbacks, expiração e HTTP.

Execute `./scripts/run-local.ps1 -Restart -IntegrationTests -Background` para validar e iniciar o exemplo.
