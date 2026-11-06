# Mapeamento e CRUD HASH

O item 4.1 permite escolher o formato de persistência por entidade. JSON continua sendo o padrão, sem mudança nas entidades existentes:

```java
@DatadamDocument(prefix = "note:hash:", storage = DatadamStorage.HASH, timeToLive = 300)
public record Note(@Id @DatadamGeneratedId String id, String text, @Version Long version) {}

public interface NoteRepository extends DatadamRepository<Note, String> {}

Note first = repository.save(new Note(null, "original", null)); // HASH, versão 0
Note next = repository.save(new Note(first.id(), null, first.version())); // remove o campo text, versão 1
repository.delete(next); // exige a versão atual
```

Use prefixos exclusivos por tipo/formato. Alterar a anotação de uma entidade já persistida não converte suas chaves. Ler/salvar HASH sobre uma chave JSON gera WRONGTYPE e preserva o valor existente. A migração deve usar outro prefixo ou uma conversão explícita e coordenada; não há migração automática.

## Requisito do servidor

A gravação HASH requer **HREPLACE**, uma extensão Datadam adicionada nesta entrega, junto da correção do mecanismo de undo de transações HASH. O código C++ foi alterado e a imagem local foi reconstruída. O patch reproduzível está em [server-patches/hreplace.patch](../server-patches/hreplace.patch), com instruções em [server-patches/README.md](../server-patches/README.md).

HREPLACE substitui o conjunto completo de campos em um único KeyTxn, preserva expiração, recusa outro tipo de chave e valida UNIQUE sobre o estado final. Não é um comando Redis padrão. A biblioteca não simula esse contrato com HSET seguido de HDEL nem apaga a chave antes de recriar: essas sequências podem expor estado parcial, perder TTL ou falhar por uma combinação intermediária de campos que não existe no resultado final.

Servidores sem a extensão recusam a escrita sem substituí-la por um caminho menos seguro. Entidades JSON continuam usando os comandos anteriores. Para o ambiente local, execute `./scripts/run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background`.

## Conversão dos campos

Cada propriedade serializada na raiz vira um campo HASH com o nome Jackson, incluindo JsonProperty/naming strategy. Não são usados campo `_class`, flattening de objetos ou chaves auxiliares por entidade.

| Valor Java | Campo HASH |
|---|---|
| String/char | Texto sem aspas JSON; escapes são do conteúdo real |
| Números e booleanos | Representação textual do valor JSON, por exemplo `42`, `12.50`, `true` |
| Enum padrão | Nome textual, por exemplo `ACTIVE` |
| UUID | Texto UUID |
| Datas/horas/Duration | Representação Jackson configurada, textual ou numérica; os módulos da aplicação são mantidos |
| Objeto/record aninhado | JSON do objeto dentro de um único campo HASH |
| Lista, array ou Map | JSON completo dentro de um único campo HASH, preservando a estrutura |
| byte[] | Base64 de Jackson, em um campo textual |
| null na raiz | Campo omitido/removido |

A leitura usa o tipo declarado da propriedade para reconstruir o valor. Uma String literal `"null"` continua sendo o texto `null`, diferente de um campo omitido. Números integrais e BigDecimal preservam precisão; o codec evita converter decimais para double durante a passagem pelo JSON. Objetos/coleções aninhados não ficam disponíveis como campos HASH separados; seus caminhos internos ainda pertencem ao JSON armazenado naquele campo.

Ao salvar, o conjunto de campos é substituído integralmente. Campos antes presentes e agora null, excluídos pela configuração Jackson ou removidos do modelo deixam de existir, inclusive campos externos que não sejam reserializados. Campo ausente na leitura segue a configuração Jackson: normalmente null em wrappers/referências e zero/false em primitivos; construtores ou opções mais restritas podem recusar a ausência.

Propriedades da raiz com tipo Object são recusadas, pois uma string nativa como `true` não permite distinguir String de boolean sem metadados adicionais. Declare o tipo concreto, Map ou List. char[] na raiz também é recusado por ter representações Jackson ambíguas entre texto e array; use String ou List de Character. Valores dinâmicos dentro de containers JSON mantêm o seu formato JSON. Serializadores personalizados que mudam a forma esperada de um tipo precisam de conversão de leitura compatível; o contrato desta etapa cobre as representações da tabela, não qualquer codec arbitrário. ID deve fazer parte dos campos serializados e manter seu nome/valor de forma estável.

## CRUD e ciclo da entidade

Os repositórios e o template mantêm a mesma API: save/saveAll, findById/findAll/findAllById, existsById/count, delete/deleteById/deleteAll e persist. HGETALL materializa cada entidade; SCAN usa TYPE hash e o prefixo do modelo. Hash e JSON com o mesmo ID podem coexistir em prefixos distintos. Ler um formato não inclui chaves do outro.

IDs gerados, records, classes mutáveis, Persistable, auditoria e callbacks seguem os contratos anteriores. Antes da escrita: BeforeConvert, auditoria e BeforeSave. Depois da confirmação: aplicação dos valores gerenciados em classes mutáveis e AfterSave. AfterConvert também é aplicado às leituras HASH. Records retornam uma nova instância com ID/versão; use sempre o retorno de save.

Os dados de criação recebidos são preservados em atualizações, com os mesmos limites documentados de confiança nos valores fornecidos pela aplicação. O exemplo HTTP carrega os metadados de criação do estado armazenado, mas mantém a versão esperada enviada pelo cliente. Falhas BeforeSave recusam a escrita; falhas AfterSave continuam sendo AfterSaveCallbackException porque a escrita já foi confirmada. Efeitos em memória de auditoria/callbacks não têm rollback automático.

### Versão e criação exclusiva

`@Version` aceita os mesmos tipos: Long/Integer começam em null e são persistidos em zero; long/int começam em zero e são persistidos em um. Na atualização, o contador incrementa em Java e conteúdo/versão são escritos juntos por HREPLACE. O script compara HGET como texto inteiro exato, sem conversão para o número de Lua, inclusive acima de 2^53.

Entidades novas versionadas, IDs gerados e Persistable.isNew=true usam criação exclusiva. Uma criação duplicada versionada gera OptimisticLockingFailureException; uma colisão de ID gerado sem versão gera DuplicateKeyException. Atualizações/exclusões versionadas exigem versão correspondente. Chaves removidas/expiradas não são recriadas por uma atualização. deleteById lê a versão corrente e faz exclusão condicionada, como em JSON; uma chave já ausente é uma operação sem efeito.

Documentos antigos sem campo de versão exigem migração explícita. Reutilizar um ID após exclusão/expiração mantém o limite ABA do contador numérico. Comandos HSET/HDEL/HREPLACE diretos não passam pelo ciclo de entidades; escritores externos devem respeitar o mesmo protocolo para obter controle de versão.

### TTL, UNIQUE e falhas

A comparação/criação exclusiva, substituição e eventual TTL acontecem sob o isolamento de um script. Antes de gravar com TTL, o script verifica PEXPIRE sem alterar a chave. Sem política explícita, HREPLACE preserva a data de expiração existente; TTL por tipo ou instância é reaplicado somente após uma substituição bem-sucedida. Valores Duration também são aceitos e convertidos para milissegundos pela política de TTL. persist continua sendo a remoção explícita de expiração, sem incremento da versão.

Uma recusa por UNIQUE preserva todos os campos, versão, auditoria, expiração e donos dos índices. O servidor valida o estado final: remover um campo de um índice composto e alterar outro na mesma substituição não tenta publicar uma combinação intermediária. A correção de undo incluída no patch evita que a recusa transforme campos antigos em exclusões persistidas.

HREPLACE grava campos e versão na mesma transação nativa. Continuam válidos os limites de durabilidade: um índice UNIQUE pode antecipar commit, portanto HASH + TTL não recebem uma garantia adicional de commit durável conjunto após crash. Uma falha de transporte pode deixar o resultado incerto. Não há retry automático nem transação entre entidades; operações em lote são sequenciais e podem ter sucesso parcial.

Permissões da aplicação: EVAL, HLEN, HGETALL, HREPLACE; HGET para versão; PEXPIRE com TTL; DEL para excluir; SCAN para listar; PERSIST para remover expiração. A configuração Docker declara essas permissões e o prefixo demo:hash:*. O usuário ttl-restricted continua sem PEXPIRE para testar recusa antes da escrita.

## Índices e consultas

O item 4.1 entrega o CRUD; o item 4.2 acrescenta schema ON HASH e consultas IDX/FT declaradas, derivadas e programáticas. Consulte [índices e consultas HASH](hash-indexes-and-queries.md) para aliases, separadores, diferenças de TAG/NUMERIC e limites de derivação. Os testes específicos do CRUD ainda usam fixtures administrativas para isolar a proteção UNIQUE da escrita.

As operações template.json(...) continuam recusando modelos HASH. Não há uma API de mudanças parciais de campos HASH nesta etapa. O exemplo `/hash-customers` amplia `/hash-notes` com buscas nativas equivalentes às do Customer JSON, respeitando as diferenças dos formatos.

## Exemplo HTTP

`/hash-notes` usa HashNote com ID gerado, versão Long, auditoria, normalização por callback, conteúdo opcional, lista e Map aninhado e TTL padrão de cinco minutos:

```powershell
$data = @{ reference = [guid]::NewGuid().ToString(); text = ' rascunho '; tags = @('hash'); details = @{ enabled = $true } }
$first = Invoke-RestMethod http://127.0.0.1:8080/hash-notes -Method Post -ContentType application/json -Body ($data | ConvertTo-Json -Depth 5)
$data.version = $first.version
$data.text = $null # remove o campo HASH text
$current = Invoke-RestMethod "http://127.0.0.1:8080/hash-notes/$($first.id)" -Method Put -ContentType application/json -Body ($data | ConvertTo-Json -Depth 5)
# Repetir o PUT com version=0 recebe HTTP 409.
Invoke-RestMethod "http://127.0.0.1:8080/hash-notes/$($current.id)?version=$($current.version)" -Method Delete
```

POST exige versão ausente/null; PUT e DELETE exigem a versão esperada. Conflitos de versão/UNIQUE retornam HTTP 409. Auditoria não é recebida no DTO de entrada.

## Validação

HashMappingTest cobre escolha de formato, conversões, null, alias, precisão decimal/temporal, managed values e falhas de gravação. HashRepositoryIntegrationIT usa Datadam real no Docker: ciclo completo, substituição sem campos antigos, UNIQUE com preservação integral e avaliação do estado final, versão concorrente por pools independentes, precisão int64, TTL numérico/Duration, mutáveis/records, tipos, coexistência/WRONGTYPE, ACL e HTTP.

O patch C++ inclui testes de TTL, campos duplicados, reabertura do banco, WRONGTYPE e recusa UNIQUE dentro de script preservando os dados antes e depois de reabrir o banco. O estágio Docker `test` executa a suíte nativa e os testes de integração/persistência do servidor.
