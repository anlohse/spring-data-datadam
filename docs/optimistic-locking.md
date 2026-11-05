# Concorrência com @Version

O item 2.1 adiciona optimistic locking aos documentos JSON usando `org.springframework.data.annotation.Version`. Duas gravações que apresentam a mesma versão existente não podem ter sucesso: a primeira incrementa a versão; a segunda recebe `OptimisticLockingFailureException`.

## Usar no repositório

```java
@DatadamDocument(prefix = "note:")
public record Note(@Id @DatadamGeneratedId String id, String text, @Version Long version) {}

Note first = repository.save(new Note(null, "original", null)); // version = 0
Note current = repository.save(new Note(first.id(), "alterado", first.version())); // version = 1
repository.save(new Note(first.id(), "atrasado", first.version())); // conflito
repository.delete(first);   // conflito: versão 0
repository.delete(current); // exclui: versão 1
```

Sempre use a entidade retornada por `save`/`saveAll`. Records recebem uma nova instância pelo construtor canônico, que precisa preservar os valores gerenciados de ID e versão. Classes mutáveis recebem ID gerado e versão somente depois da confirmação da escrita. Recusas por versão, UNIQUE ou ACL não avançam esses campos em memória. Auditoria e efeitos próprios de callbacks em objetos mutáveis continuam sujeitos ao contrato documentado de ausência de rollback em memória.

## Estado e valores aceitos

| Campo @Version | Estado novo | Versão da primeira gravação | Atualização |
|---|---|---|---|
| `Long` ou `Integer` | `null` | `0` | valor esperado + 1 |
| `long` ou `int` | `0` | `1` | valor esperado + 1 |

A versão passa a determinar se a entidade é nova, antes de examinar o ID. Assim, um ID já atribuído com versão wrapper null solicita criação exclusiva. Uma versão wrapper zero já representa entidade existente. Na ausência de `@Version`, os comportamentos anteriores de criação/substituição permanecem.

`Persistable.isNew()` continua sendo consultado, mas precisa concordar com o estado indicado pela versão; inconsistências são recusadas antes da escrita. A biblioteca não muda flags próprias de Persistable.

São aceitos somente os quatro tipos da tabela, com um único campo de versão na hierarquia. Valores negativos e overflow são recusados. No máximo do tipo, ainda é possível ler ou excluir a entidade com a versão correta, mas não atualizá-la. A versão deve ser persistente, gravável (ou componente de record) e distinta de ID, TTL e campos de auditoria. A anotação é inspecionada em campos, como `@Id`.

O nome JSON pode ser renomeado com Jackson, inclusive `@JsonProperty("rev.no")`. A versão gravada precisa ser um inteiro JSON exato, legível de volta pela entidade. Serialização numérica como string é recusada antes do transporte. Documentos escritos externamente devem seguir esse mesmo formato; strings, floats e ausência do campo não equivalem à versão inteira esperada.

## Comparação no servidor

Um script EVAL executa, sob o isolamento do Datadam:

1. Lê somente o campo de versão via JSON.GET.
2. Na criação, exige chave ausente; na atualização/exclusão, exige a versão esperada.
3. Para salvar com TTL, verifica a permissão PEXPIRE antes de escrever.
4. Executa JSON.SET da raiz contendo **conteúdo e nova versão juntos**, com NX na criação e XX na atualização; depois aplica o TTL, quando especificado. Para excluir, executa DEL após a comparação.

A comparação não é feita no cliente. A aritmética usa os inteiros de Java e a comparação usa o texto inteiro exato devolvido pelo JSON.GET, sem converter a versão para o número de ponto flutuante de Lua. Isso preserva valores Long acima de 2^53, inclusive Long.MAX_VALUE.

Uma atualização não recria uma entidade removida ou expirada. Criação duplicada e versão obsoleta/ausente resultam em `OptimisticLockingFailureException`. Conflito com índice UNIQUE continua sendo `DuplicateKeyException`; conteúdo e versão pertencem ao mesmo JSON.SET, portanto uma recusa UNIQUE não pode incrementar a versão separadamente.

Recusas por versão e UNIQUE preservam o documento inteiro, seus índices IDX/FT e a data absoluta de expiração. Um save bem-sucedido respeita o TTL por entidade ou por tipo; sem política explícita, preserva a expiração existente. `persist(id)` é uma operação explícita somente de expiração: não incrementa a versão do documento.

Permissões necessárias: EVAL, JSON.GET e JSON.SET para salvar; PEXPIRE quando há TTL; DEL para excluir, além do acesso às chaves. O usuário de aplicação local já possui essas permissões; não precisa administrar índices.

## Auditoria, callbacks e exclusão

A ordem permanece `BeforeConvert → auditoria → BeforeSave → escrita → AfterSave`. Os callbacks anteriores à escrita recebem a versão esperada; AfterSave recebe a nova versão confirmada. AfterConvert recebe a versão lida. Callbacks não podem trocar a versão, a identidade ou o estado novo capturado. Uma violação em AfterSave é reportada por `AfterSaveCallbackException`, pois a escrita já ocorreu.

Datas/autores de criação usam a decisão de estado baseada na versão, mesmo com ID atribuído. Os campos de criação recebidos são preservados em atualizações; a biblioteca não autentica valores que a aplicação já entregou alterados. O exemplo HTTP carrega os metadados existentes e preserva a versão esperada enviada pelo cliente.

`delete(entity)` exige uma versão persistida e correspondente ao documento atual; não incrementa a versão. Uma entidade nova, obsoleta, removida ou expirada gera conflito. `deleteById(id)` lê a versão atual e faz exclusão condicionada a ela: uma atualização entre a leitura e a exclusão é protegida. Se o ID já está ausente, é uma operação sem efeito. Como recebe apenas o ID, esse método não conhece a versão vista anteriormente pelo chamador; para exigir a versão do cliente, use `delete(entity)`.

`saveAll`, `deleteAll(entities)`, `deleteAllById` e `deleteAll()` são sequenciais e param na primeira falha; operações já confirmadas permanecem. Não são transações e não há repetição automática.

## Comandos diretos, migração e limites

Comandos nativos por `DatadamCommands` não executam auditoria, callbacks ou incremento condicionado de versão. Um escritor externo que modifica o conteúdo sem participar do protocolo pode permitir que uma atualização posterior sobrescreva seu trabalho. Todos os escritores devem adotar a mesma comparação/incremento para obter essa proteção. Não use JSON.NUMINCRBY isolado na versão para simular a operação completa.

Documentos antigos sem versão **não são migrados automaticamente**. Adicionar a anotação não autoriza sobrescrevê-los: tanto a tentativa de criação quanto uma atualização com contador atribuído são recusadas. Planeje uma migração explícita com escritores suspensos ou um protocolo coordenado, atribua um inteiro válido e só então ative os repositórios versionados. Para primitivos, escolha um valor inicial persistido maior que zero.

O contador protege uma encarnação da entidade. Se a chave for excluída/expirar e o mesmo ID for reutilizado, o contador reinicia; uma cópia antiga com contador igual ao da nova entidade não pode ser distinguida apenas pelo `@Version` numérico (problema ABA). Evite reciclar IDs; UUID é o padrão do gerador. Uma garantia entre encarnações exigiria também um token de identidade próprio.

O servidor não oferece rollback geral de scripts. Continuam válidos os [limites de durabilidade de documento e TTL](entity-ttl.md): uma escrita UNIQUE pode antecipar commit, então documento + TTL não têm garantia de commit durável conjunto após crash. Conteúdo e versão, por sua vez, são gravados no mesmo JSON.SET. Uma falha de transporte pode ocorrer depois da escrita e deixar o resultado incerto; não há retry automático. Releia o estado antes de decidir como recuperar a operação.

## Exemplo HTTP

`VersionedNote` combina ID gerado, `@Version Long`, auditoria, callbacks, TTL de cinco minutos e índices IDX/FT com referência UNIQUE. A API local usa `/versioned-notes`:

```powershell
$data = @{ reference = [guid]::NewGuid().ToString(); category = 'draft'; text = 'original' }
$first = Invoke-RestMethod http://127.0.0.1:8080/versioned-notes -Method Post -ContentType application/json -Body ($data | ConvertTo-Json)
$data.version = $first.version # zero
$data.text = 'alterado'
$current = Invoke-RestMethod "http://127.0.0.1:8080/versioned-notes/$($first.id)" -Method Put -ContentType application/json -Body ($data | ConvertTo-Json)
# Repetir esse PUT com version=0 retorna HTTP 409; current.version é 1.
Invoke-RestMethod "http://127.0.0.1:8080/versioned-notes/$($current.id)?version=$($current.version)" -Method Delete
```

POST exige versão ausente/null. PUT exige a versão esperada no corpo; DELETE exige `?version=...`. A API retorna HTTP 409 para versão obsoleta e UNIQUE e HTTP 400 para entrada inválida. Uma chave ausente no carregamento HTTP retorna 404. A versão do cliente não é substituída pela versão mais recente durante o carregamento dos campos de auditoria.

## Validação

`EntityVersionTest` cobre metadados, estado, tipos, overflow, callbacks, serialização e aplicação de ID/versão somente após confirmação. `OptimisticLockingIntegrationIT` usa o Datadam real e duas conexões de pools independentes para a disputa, além de TTL, IDX/FT, UNIQUE, exclusão concorrente, expiração, auditoria, ACL, lotes, precisão int64, migração recusada e HTTP.

Execute a suíte completa e suba o exemplo com `./scripts/run-local.ps1 -Restart -IntegrationTests -Background`.
