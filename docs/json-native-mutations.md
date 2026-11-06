# Incrementos, arrays e merge JSON

O item 3.2 amplia `template.json(Entity.class)` com comandos nativos para modificar valores dentro do documento. Cada chamada envia um comando ao Datadam; não busca e reenvia o documento pela aplicação.

```java
var json = template.json(Customer.class);
var age = JsonPath.of("$.age", Integer.class);
var tags = JsonPath.of("$.tags", new TypeReference<List<String>>() {});

List<Number> ages = json.increment(id, age, 1);
json.multiply(id, age, 2);
List<Long> lengths = json.arrayAppend(id, tags, List.of("datadam"));
json.arrayInsert(id, tags, 0, List.of("spring"));
List<JsonPopResult<String>> popped = json.arrayPop(id, tags); // último elemento
json.arrayTrim(id, tags, 0, 2); // mantém posições 0 a 2, inclusive
```

Continuam valendo as [proteções e formas de caminho do item 3.1](json-path-operations.md). Escritas aceitam somente membros/índices singulares não raiz e recusam entidades com versão, auditoria ou callbacks de gravação, além de alterações em ID e política de TTL. Use save para esses ciclos de entidade. As consultas arrayLength/arrayIndex são somente leitura e podem usar seletores múltiplos.

## Retornos e regras

Os métodos normalizam JSONPath e caminhos legados em listas imutáveis. Escritas têm zero ou um resultado por causa da restrição a caminhos singulares; consultas podem ter vários. Um JSONPath sem matches devolve lista vazia. Um resultado nil por tipo incompatível é preservado como elemento null, sem usar List.of ou Optional, que perderiam essa distinção.

| Método | Comando | Retorno |
|---|---|---|
| increment(id, path, amount) | JSON.NUMINCRBY | List de Number: valores após incremento |
| multiply(id, path, factor) | JSON.NUMMULTBY | List de Number: valores após multiplicação |
| arrayAppend(id, path, values) | JSON.ARRAPPEND | List de Long: comprimentos finais |
| arrayInsert(id, path, index, values) | JSON.ARRINSERT | List de Long: comprimentos finais |
| arrayPop(id, path[, index]) | JSON.ARRPOP | List de JsonPopResult: resultado removido por match |
| arrayTrim(id, path, start, stop) | JSON.ARRTRIM | List de Long: comprimentos finais |
| arrayLength(id, path) | JSON.ARRLEN | List de Long: comprimentos atuais |
| arrayIndex(id, path, value[, start, stop]) | JSON.ARRINDEX | List de Long: posições encontradas; -1 se ausente |
| merge(id, path, patch) | JSON.MERGE | boolean true quando o servidor responde OK; não é contagem de mudanças |

### Números

Os operandos devem ser números JSON finitos. NaN, Infinity e configurações que serializam números como strings são recusados antes da escrita. O Datadam mantém inteiro + inteiro como inteiro enquanto o resultado cabe em int64; overflow promove o resultado para ponto flutuante. Um resultado não finito é recusado sem gravar.

Por isso, o retorno é List de Number, independentemente do tipo numérico declarado no caminho. Um JsonPath de Long pode retornar Double após promoção, sem truncamento ou tentativa de deserializar de volta para Long. O inteiro exato em int64 é preservado na resposta; números promovidos seguem a precisão de ponto flutuante do servidor. O mapper Jackson determina as subclasses Java concretas (por exemplo Integer, Long ou Double).

Com JSONPath, um valor não numérico retorna `[null]`; sem matches, `[]`. Com caminho legado, tipo incompatível e ausência do caminho são erros. Uma chave ausente/expirada é erro, sem criação implícita. A biblioteca propaga os erros do servidor e não repete incrementos automaticamente.

### Arrays

Use JsonPath de List com TypeReference para informar o tipo dos elementos. Append/insert exigem pelo menos um valor e enviam cada elemento serializado como argumento JSON separado; o valor null pode ser inserido usando uma lista que o aceite, como Arrays.asList. Os elementos são validados contra o tipo declarado antes de enviar.

Índices negativos contam do final. Insert aceita index igual ao comprimento para adicionar no final; um índice fora dos limites é erro e não modifica o documento. Pop usa -1 por padrão; um índice fora dos extremos é ajustado pelo servidor ao extremo mais próximo. Trim mantém start/stop **inclusivos**; intervalos vazios esvaziam o array. ArrayIndex procura em start/stop com **stop exclusivo** e zero como o fim do array; os padrões são 0/0.

Pop distingue o conteúdo JSON do nil de RESP:

- `JsonPopResult(true, null)`: um elemento JSON null foi removido.
- `JsonPopResult(true, value)`: o elemento foi removido, inclusive se value for outra lista/objeto.
- `JsonPopResult(false, null)`: o match não era um array ou o array estava vazio.
- Lista sem resultados: JSONPath não selecionou nada.

Append/insert/trim/length/index retornam um elemento null para matches de tipo incompatível em JSONPath. Caminhos legados mantêm os erros de tipo/ausência do servidor, exceto pop, cujo nil para array vazio ou não-array é preservado como popped=false. ArrayLength em chave ausente retorna lista vazia com JSONPath e lista contendo null com caminho legado. Os demais comandos de arrays exigem chave existente.

### Merge de objetos

```java
var metadata = JsonPath.of("$.details", new TypeReference<Map<String, Object>>() {});
json.set(id, metadata, Map.of("keep", true, "obsolete", "old"));
Map<String, Object> patch = new HashMap<>();
patch.put("obsolete", null);
patch.put("added", 42);
json.merge(id, metadata, patch);
// details = {"keep": true, "added": 42}
```

Merge recebe um Map de patch de objeto e aplica as regras RFC 7386: objetos são combinados recursivamente, um membro null é removido, arrays são substituídos como valores e não concatenados. Um alvo escalar pode ser substituído por objeto. Valores null do patch, inclusive em Maps aninhados, são enviados mesmo quando o mapper usa inclusão NON_NULL. Arrays e escalares na raiz do patch não fazem parte desta API.

O caminho precisa existir para efetuar a combinação. JSONPath sem matches responde OK sem mudar nada, portanto merge retorna true mesmo nesse caso; não prometa que houve alteração. Um caminho legado inexistente gera erro. A raiz do documento é recusada para preservar identidade e TTL, e uma chave ausente/expirada não é criada.

## Atomicidade, índices e falhas

Cada comando isolado é aplicado sob a trava da chave no servidor. Incrementos concorrentes não fazem o read/modify/write no cliente, portanto não perdem incrementos. Appends concorrentes preservam os elementos acrescentados e merges em membros distintos preservam os outros membros; ordem e resultados intermediários dependem da ordem real das execuções.

Uma sequência de comandos não é uma transação. Se um append tiver sucesso e o incremento seguinte falhar por UNIQUE, o append permanece. Duas alterações no mesmo membro de um documento sem versão obedecem à ordem de execução, sem controle de versão. Não há garantia de que uma consulta feita depois observe apenas a alteração da própria conexão.

Todas as alterações preservam a data absoluta de expiração, sem reaplicar TTL ou exigir PEXPIRE. IDX/FT são mantidos pelo servidor. UNIQUE pode recusar números, valores acrescentados em arrays ou membros alterados por merge. Pop/trim também podem causar conflito quando há índice sobre uma posição de array: ao remover o primeiro elemento, o segundo passa a ocupar a posição indexada. A recusa gera DuplicateKeyException e preserva documento, TTL e índices.

Erros de sintaxe/tipo, índice inválido, resultado numérico não finito e ACL mantêm as exceções nativas traduzidas por DatadamCommands. Uma falha de transporte continua podendo deixar o resultado incerto; não há retry automático.

Há ainda a conversão do **resultado de uma mutação já enviada**. Se, por exemplo, o cliente declarar um array de String e fizer pop de um número, o elemento pode ter sido removido antes da conversão falhar. A biblioteca gera JsonMutationDecodingException com getKey()/getCommand(), distinguindo esse caso de uma recusa do comando. Releia o estado para decidir a recuperação; repetir o pop removeria outro elemento.

O Datadam ainda interpreta e regrava o documento inteiro internamente. Modificar um único elemento evita a transferência/reconstrução na aplicação, mas não torna o custo interno independente do tamanho do documento. Essas APIs não acrescentam transações, rollback de sequências ou novas garantias de durabilidade após crash.

## Exemplo HTTP

CustomerJsonController expõe incremento da idade, append/insert/pop/trim de tags e um objeto JSON adicional details. Esse membro é acessado pelas operações JSON, sem ampliar o DTO Customer retornado pelo repositório.

```powershell
# Use o ID de um Customer já criado.
$url = "http://127.0.0.1:8080/customers/$id/json"
Invoke-RestMethod "$url/age/increment" -Method Post -ContentType application/json -Body '{"amount":1}'
Invoke-RestMethod "$url/tags/append" -Method Post -ContentType application/json -Body '{"values":["datadam"]}'
Invoke-RestMethod "$url/tags/insert?index=0" -Method Post -ContentType application/json -Body '{"values":["spring"]}'
Invoke-RestMethod "$url/tags/pop" -Method Delete
Invoke-RestMethod "$url/tags/trim?start=0&stop=1" -Method Put
Invoke-RestMethod "$url/details" -Method Put -ContentType application/json -Body '{"keep":true,"obsolete":"old"}'
Invoke-RestMethod "$url/details" -Method Patch -ContentType application/json -Body '{"obsolete":null,"added":42}'
Invoke-RestMethod "$($url)?path=$.details"
```

O DTO de idade usa Integer e o modelo Customer usa int. A API de números segue o servidor e não valida o schema completo; evite levar a idade além do intervalo do campo, pois o repositório deixaria de materializá-lo corretamente. Para counters grandes, escolha Long/Number no seu próprio modelo.

O usuário app no Docker recebe explicitamente os comandos novos. O usuário ttl-restricted permanece sem essas permissões para os testes de ACL. Não é necessário liberar administração de índices para a aplicação.

## Validação

JsonMutationTest cobre serialização, envelopes, tipos, valores null, promoção numérica, guards, merge com NON_NULL e falha de conversão após mutação. JsonMutationIntegrationIT testa Datadam real, incluindo duas conexões independentes para incrementos/appends/merges concorrentes, UNIQUE em números/arrays/posições/merge, TTL absoluto, IDX/FT, expiração, ACL e HTTP. As chaves de teste usam prefixo demo:mutation: e são removidas individualmente; índices de teste são criados idempotentemente.

Execute `./scripts/run-local.ps1 -Restart -IntegrationTests -Background`.
