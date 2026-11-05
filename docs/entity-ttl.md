# TTL de entidades

O item 1.1 adiciona expiração declarativa às entidades JSON. O Datadam mantém os índices atualizados quando o documento recebe um prazo, perde a expiração ou expira.

## Padrão por tipo e substituição por entidade

```java
@DatadamDocument(prefix = "note:", timeToLive = 60)
public record Note(
    @Id String id,
    String text,
    @DatadamTimeToLive(unit = TimeUnit.MILLISECONDS) Long ttlMillis
) {}
```

O padrão do tipo acima é de 60 segundos. `ttlMillis = 1500L` substitui esse padrão por 1,5 segundo; `ttlMillis = null` usa o padrão. `timeToLiveUnit` altera a unidade do padrão:

```java
@DatadamDocument(prefix = "session:", timeToLive = 30, timeToLiveUnit = TimeUnit.MINUTES)
public record Session(@Id String id, String userId) {}
```

O campo `@DatadamTimeToLive` aceita byte, short, int e long, seus wrappers e `Duration`. Para Duration, a duração já informa a unidade:

```java
@DatadamDocument(prefix = "draft:")
public record Draft(@Id String id, @DatadamTimeToLive Duration lifetime) {}
```

Há no máximo um campo de TTL por entidade, inclusive por herança. Ele não pode ser estático nem compartilhar o campo `@Id`. Classes mutáveis e records são aceitos. Campos com wrappers ou Duration podem ser null; primitivos precisam ser preenchidos com um valor válido antes de salvar.

## Regras de criação e atualização

| Situação | Efeito de `save` |
|---|---|
| Campo TTL com valor positivo | Aplica esse prazo novamente, contado a partir da gravação no servidor |
| Campo null ou inexistente, com padrão positivo no tipo | Aplica o padrão novamente |
| Campo null ou inexistente, sem padrão no tipo | Preserva a expiração existente; uma chave nova fica sem expiração |
| `timeToLive = -1` no tipo | Desabilita o padrão; é o valor padrão da anotação |
| Campo TTL zero ou negativo | Recusa antes de enviar qualquer comando |
| Padrão do tipo zero ou menor que -1 | Recusa o mapeamento ao criar o repositório |
| Precisão inferior a um milissegundo ou overflow | Recusa sem truncar ou saturar o valor |

TTL positivo é uma política de renovação por gravação. Um `save` bem-sucedido reinicia o prazo, mesmo quando os outros campos não mudam. Ler uma entidade ou executar uma busca não renova o prazo.

A resolução é de um milissegundo, e o intervalo aceito é de 1 até `Long.MAX_VALUE / 2` milissegundos. O limite deixa espaço para o servidor somar o prazo ao seu relógio de época sem overflow no uso normal. As conversões entre unidades são exatas: 2000 microssegundos são aceitos como 2 ms; 2001 microssegundos são recusados.

O campo anotado continua sendo um campo JSON normal, sujeito às regras do ObjectMapper. Quando serializado, guarda a política solicitada, não uma contagem regressiva. Ler `ttlMillis = 1500` não significa que ainda restam 1500 ms; salvar novamente com esse valor renova o prazo para 1500 ms.

## Remover a expiração

```java
boolean removed = repository.persist(id);
// Ou:
boolean removedByTemplate = template.persist(id, new EntityModel<>(Note.class));
```

`persist` executa somente PERSIST, preservando conteúdo e índices. Retorna true quando remove um prazo, false se a chave não existe ou já está sem expiração. Não faz uma leitura seguida de uma regravação.

O nome significa tornar a chave persistente em relação à expiração; não significa forçar um fsync. O próximo `save` com uma política positiva, explícita ou padrão, aplicará a expiração novamente. Para preservar a chave sem prazo em futuras gravações, use uma entidade sem política de TTL.

Zero e negativos no campo não são atalhos para excluir a chave ou remover a expiração. Exclusão continua sendo feita por `deleteById` e remoção de expiração por `persist`.

## Gravação, UNIQUE e permissões

Sem política de TTL, `save` mantém o caminho anterior: um JSON.SET, que preserva o prazo existente.

Com política positiva, a biblioteca envia um EVAL com script fixo e chave/documento/prazo em argumentos separados. O script verifica permissão de PEXPIRE sem alterar a chave, executa JSON.SET e, se a gravação tiver sucesso, aplica PEXPIRE.

A verificação usa um argumento deliberadamente não numérico de PEXPIRE. No Datadam, ACL é verificada antes do parse do valor; o erro esperado de número inválido comprova que a verificação de permissão passou sem modificar a chave. Uma recusa ou resposta inesperada encerra o script antes de JSON.SET. Esse comportamento foi validado no servidor local e é uma dependência explícita da implementação.

Uma recusa por UNIQUE acontece em JSON.SET e encerra o script antes de alterar a expiração. Os testes comparam a data absoluta de expiração, não apenas um TTL aproximado, para verificar que uma atualização recusada não renovou nem encurtou o prazo. Uma inserção recusada não cria o documento.

Além das permissões normais de gravação, o usuário precisa de `+eval +pexpire` e acesso à chave. `persist(id)` precisa de `+persist`. Inspecionar o prazo requer `+pttl` ou `+pexpiretime`; o save não precisa dessas permissões de leitura. Não é necessário conceder administração de índices ou toda a categoria de scripting.

### Limite de atomicidade e durabilidade

Outros clientes não observam o documento entre JSON.SET e PEXPIRE, porque o script tem execução isolada. TTL inválido é recusado antes de EVAL; permissões insuficientes e UNIQUE são tratados antes de uma alteração parcial nos casos documentados e testados.

Isso não cria rollback de scripts. Uma falha inesperada após uma escrita pode mantê-la, conforme o contrato do servidor. A documentação e a implementação do Datadam também indicam que uma escrita em índice UNIQUE pode forçar um commit antes do fim do bloco. Portanto, esta API **não garante que documento e novo TTL sejam sempre uma única gravação durável em caso de crash** entre esses comandos quando há UNIQUE. Essa garantia exigiria uma operação nativa adicional ou uma mudança explícita no servidor, fora do item 1.1.

Uma falha de transporte também pode deixar o cliente sem saber se a operação terminou. A biblioteca não repete a gravação automaticamente. Aplicações que precisem de recuperação conjunta após crash devem considerar esse limite.

## Exemplo executável

`TemporaryNote`, `TemporaryNoteRepository` e a API `/temporary-notes` implementam o exemplo: padrão de 60 segundos e substituição opcional em milissegundos, com índices IDX, FT e UNIQUE.

```powershell
$note = @{
    id = 'note-' + [guid]::NewGuid().ToString('N')
    reference = [guid]::NewGuid().ToString('N')
    category = 'demo'
    text = 'Expira em dez segundos'
    ttlMillis = 10000
}
Invoke-RestMethod http://127.0.0.1:8080/temporary-notes -Method Post -ContentType application/json -Body ($note | ConvertTo-Json)
Invoke-RestMethod "http://127.0.0.1:8080/temporary-notes/$($note.id)/ttl"
Invoke-RestMethod 'http://127.0.0.1:8080/temporary-notes?category=demo'

# Opcional: remove o prazo antes de expirar.
Invoke-RestMethod "http://127.0.0.1:8080/temporary-notes/$($note.id)/persist" -Method Post
```

`GET /temporary-notes/{id}/ttl` devolve `remainingMilliseconds`: valor positivo para prazo ativo, -1 para chave sem expiração e -2 para chave ausente. Após expirar, `GET /temporary-notes/{id}` retorna 404 e a chave deixa de aparecer nos índices.

Atualizações inválidas retornam HTTP 400; referência UNIQUE de outra nota retorna 409. O usuário de aplicação continua sem permissão para criar índices. `SchemaApplication` prepara o schema com a credencial de migração.

## Validação

Execute `scripts/run-local.ps1 -Restart -IntegrationTests -Background` para recompilar, validar e substituir a instância iniciada pelo script. `EntityTtlTest` cobre metadados e conversões; `EntityTtlIntegrationIT` cobre expiração real, índices, renovação, preservação, Duration, persist, UNIQUE, ACL, observação por outra conexão e HTTP.

O contrato nativo foi conferido nos documentos locais `docs/json.md`, `docs/commands.md`, `docs/security.md`, `docs/scripting.md` e `docs/transactions.md` do projeto C++ Datadam.
