# IDs e identificação de entidades novas

O item 1.2 adiciona geração opcional de IDs e detecção local de entidade nova. IDs informados pela aplicação continuam sendo preservados.

## Ativar a geração

```java
@DatadamDocument(prefix = "note:")
public record Note(@Id @DatadamGeneratedId String id, String text) {}

Note input = new Note(null, "rascunho");
Note saved = repository.save(input);
// saved.id() contém um UUID; input.id() continua null.
```

`@DatadamGeneratedId` deve estar no mesmo campo que `@Id`. O gerador padrão usa UUID aleatório e aceita `String` ou `java.util.UUID`. O tipo UUID é preservado ao ler a entidade novamente. A chave continua sendo `prefix + id.toString()`.

Sempre use o retorno de `save` e `saveAll`. Para records, a biblioteca chama o construtor canônico com o ID gerado e os valores dos demais componentes. Esse construtor precisa aceitar o novo ID sem transformá-lo; a biblioteca recusa uma transformação que faria o ID retornado divergir da chave. Outros construtores imutáveis, builders e métodos wither ainda não são usados.

Para classes mutáveis, o campo ID deve ser gravável e fazer parte do JSON serializado. Após sucesso, a instância efetivamente salva recebe o ID e é retornada. Sem substituição por callbacks, ela é a mesma instância de entrada. Um ID final em uma classe que não é record não suporta geração. Os nomes JSON de Jackson, inclusive `@JsonProperty` e naming strategy, são respeitados.

Sem a anotação de geração, a aplicação continua responsável por informar um ID válido antes de salvar. Um ID existente nunca é substituído pelo gerador, mesmo que o campo tenha a anotação.

## Como a entidade nova é identificada

O módulo expõe a mesma decisão em `EntityModel.isNew` e no `EntityInformation` usado pelo Spring Data. A inspeção segue as [estratégias de estado do Spring Data](https://docs.spring.io/spring-data/commons/reference/is-new-state-detection.html), com String em branco também tratada como ID ausente.

| Entidade/ID | Decisão local |
|---|---|
| Implementa `Persistable` | Usa `Persistable.isNew()` |
| ID null | Nova |
| ID String vazio ou somente espaços | Nova |
| ID numérico primitivo igual a zero | Nova |
| ID numérico wrapper igual a zero | Não nova: zero é um valor atribuído |
| Demais IDs presentes | Não nova |

Essa decisão não consulta o Datadam e não comprova que a chave existe. Uma entidade com ID atribuído pode ainda não estar no banco. Para distinguir explicitamente esse caso, implemente `Persistable`.

`@Version` ainda não participa da detecção; seu suporte pertence à etapa 2.1. A auditoria e os callbacks do item 1.3 usam essa decisão local; consulte a documentação em docs/auditing-and-callbacks.md.

### Política de gravação

| Caso | Comportamento |
|---|---|
| ID ausente e geração ativada | Gera e tenta criar com `JSON.SET ... NX` |
| ID já informado, entidade comum | Mantém o comportamento anterior: save cria ou substitui a chave |
| `Persistable.isNew() == true`, ID informado | Tenta somente criar, usando NX |
| `Persistable.isNew() == false`, ID informado | Permite criar ou substituir, como save normal |
| `Persistable.isNew() == false`, ID ausente com geração ativada | Recusa por estado inconsistente antes de gravar |
| ID ausente, geração desativada | Null/String em branco são recusados; não há geração implícita |

Para compatibilidade, um ID numérico primitivo zero sem geração continua podendo ser salvo como chave terminada em `0`; a decisão local de estado não altera esse save para inserção exclusiva. Com geração ativada, zero primitivo é o valor ausente que solicita geração. Prefira wrappers quando precisar representar a ausência explicitamente e aceitar zero como ID atribuído.

Se você usa `Persistable`, mantenha `getId()` coerente com o campo `@Id`. Controle também a mudança do indicador de novo para existente: este item não muda flags arbitrárias da aplicação. Por exemplo, uma flag de nova entidade pode ser ignorada no JSON e assumir false ao carregar. Salvar repetidamente a mesma instância que continua retornando true tentará inserir novamente e poderá resultar em conflito.

## Geradores personalizados

Registre um bean que implemente `DatadamIdGenerator` e informe seu nome na anotação:

```java
@Configuration
class IdentityConfiguration {
    @Bean("documentIds")
    DatadamIdGenerator documentIds() {
        return context -> "doc-" + UUID.randomUUID();
    }
}

@DatadamDocument(prefix = "document:")
public record Document(
    @Id @DatadamGeneratedId(generator = "documentIds") String id,
    String title
) {}
```

O contexto informa `entityType`, `idType` e `keyPrefix`. Não é necessário acessar o servidor nem modificar a entidade para gerar o valor. Geradores compartilhados por beans precisam ser seguros para uso concorrente.

Além de String e UUID, geradores personalizados podem produzir Byte, Short, Integer, Long e seus primitivos. O valor precisa ter o tipo correspondente: a biblioteca não converte silenciosamente Integer para Long, por exemplo. Null, String em branco e zero para ID primitivo são recusados. Wrapper numérico zero é aceito como valor presente.

O método opcional `validate(context)` permite recusar tipos/configurações durante a criação do repositório. Ele também é executado antes de saves diretos pelo template e deve limitar-se à validação de metadados. Um nome de gerador inexistente ou um tipo incompatível com o gerador UUID impede a criação do repositório.

O bean `datadamIdGenerator` é o padrão para anotações sem nome de gerador. Você pode substituí-lo por um bean com esse nome. A implementação substituta deve suportar os tipos usados pelas entidades que dependem dela ou delegar os demais casos ao `UuidDatadamIdGenerator`.

Fora do Spring, o construtor de dois argumentos do template fornece o gerador UUID. Para estratégias próprias, forneça o mapa completo dos geradores necessários:

```java
var template = new DatadamTemplate(commands, mapper, Map.of(
    "datadamIdGenerator", new UuidDatadamIdGenerator(),
    "documentIds", context -> "doc-" + UUID.randomUUID()
));
```

O projeto não inclui uma sequência numérica distribuída pronta. Um gerador numérico precisa definir sua própria unicidade e coordenação; um contador em memória não garante isso entre processos ou reinícios.

## Colisões, TTL e falhas

A criação com ID gerado usa NX para que uma colisão não sobrescreva um documento existente. Colisão de ID em documento JSON resulta em `DuplicateKeyException`; não há geração automática de outro ID nem repetição da operação. Uma chave existente de tipo incompatível pode ser recusada com erro de tipo pelo servidor, preservando seu conteúdo.

Com TTL positivo, o script do item 1.1 também usa NX. Se a chave já existir, o script termina sem renovar seu prazo. Conflitos de índice UNIQUE e recusas de ACL continuam preservando o documento e a expiração nos casos documentados.

O ID só é aplicado à entrada mutável depois que a gravação responde com sucesso. Falhas de validação, colisão, UNIQUE, ACL ou transporte não preenchem esse campo. Em records, a entrada nunca muda. Um gerador pode ter consumido um valor antes de uma falha; valores não são devolvidos nem reaproveitados automaticamente.

Falha de transporte é um resultado incerto: o servidor pode ter aplicado a gravação sem a resposta chegar. O chamador não recebe uma entidade salva nessa situação, e a biblioteca não repete a operação automaticamente. Se o ID foi gerado nessa tentativa, ele não foi aplicado à entrada. Para fluxos que exijam reconciliação por chave conhecida, atribua o ID na aplicação ou mantenha uma referência de negócio consultável. Não considere um novo save de uma entrada sem ID uma repetição idempotente.

`saveAll` continua sequencial e pode ter êxito parcial. O resultado bem-sucedido contém todas as entidades com seus IDs. Se ocorrer falha no meio, não há rollback do lote nem retorno de sua lista parcial; em records, referências de negócio ajudam a localizar itens já gravados. Os limites de durabilidade de documento + TTL após crash com UNIQUE permanecem os descritos em [TTL de entidades](entity-ttl.md).

## Exemplo HTTP

`TemporaryNote` agora aceita POST sem ID, combinando geração UUID, TTL e índices nativos:

```powershell
$noteData = @{
    reference = [guid]::NewGuid().ToString('N')
    category = 'demo'
    text = 'Nota com ID gerado'
    ttlMillis = 60000
}
$saved = Invoke-RestMethod http://127.0.0.1:8080/temporary-notes -Method Post -ContentType application/json -Body ($noteData | ConvertTo-Json)
$saved.id
Invoke-RestMethod "http://127.0.0.1:8080/temporary-notes/$($saved.id)"
```

O endpoint continua aceitando um ID informado explicitamente. Os índices não precisam de migração para ativar a geração: o formato da chave e dos documentos existentes permanece o mesmo.

## Validação

`EntityIdentityTest` cobre a política de estado e geração, tipos, JSON, configuração Spring e falhas sem transporte. `EntityIdentityIntegrationIT` usa Datadam real para CRUD, registros imutáveis, classes mutáveis, IDs personalizados, saveAll, colisões com e sem TTL, UNIQUE, ACL, concorrência e HTTP.
