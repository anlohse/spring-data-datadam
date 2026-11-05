# Auditoria e callbacks

O item 1.3 usa `AuditingHandler` e `EntityCallbacks` do Spring Data Commons para oferecer auditoria opcional e callbacks síncronos. Não depende de notificações de keyspace nem de comandos administrativos.

## Ativar auditoria

```java
@Configuration
@EnableDatadamAuditing(auditorAwareRef = "currentActor")
class AuditConfiguration {
    @Bean AuditorAware<String> currentActor() {
        return () -> Optional.of("application");
    }
    @Bean Clock auditClock() { return Clock.systemUTC(); }
}

@DatadamDocument(prefix = "note:")
public record Note(
    @Id @DatadamGeneratedId String id,
    String text,
    @CreatedDate Instant createdAt,
    @LastModifiedDate Instant modifiedAt,
    @CreatedBy String createdBy,
    @LastModifiedBy String modifiedBy
) {}
```

As quatro anotações são de `org.springframework.data.annotation` e podem ser usadas seletivamente. Para registrar apenas datas, não é necessário fornecer `AuditorAware`. Sem `@EnableDatadamAuditing`, as anotações não ativam preenchimento automático. Os callbacks funcionam independentemente da auditoria.

### Relógio e autor

Um `DateTimeProvider` fornece o instante da operação. A biblioteca usa um bean disponível desse tipo; `dateTimeProviderRef` seleciona um provedor específico. Sem provedor, obtém um Clock disponível ou usa UTC e produz `Instant.now(clock)`. O relógio de auditoria não altera o relógio do Datadam nem o cálculo de TTL.

O autor vem de `AuditorAware<T>`, cujo tipo deve ser compatível com os campos de autor. Quando houver múltiplos candidatos, escolha por `auditorAwareRef` ou `@Primary`. Optional vazio preserva os valores existentes; o mesmo vale para datas quando o provedor temporal não fornece um valor.

`modifyOnCreate = true` preenche também os campos de alteração na criação. Com false, preenche apenas os de criação nesse momento. `setDates = false` permite preencher autores sem datas. Ambos são atributos de `@EnableDatadamAuditing`.

Prefira Instant para persistência. Também foram validadas conversões para Long em milissegundos e Date. Outros tipos dependem da conversão disponível no Spring para o valor fornecido; incompatibilidades falham antes da escrita.

### Estado novo e campos de criação

O estado é capturado uma vez na entrada de save, antes de callbacks, auditoria e geração de ID, pela política do item 1.2. Entidades novas recebem dados de criação; atualizações recebem apenas dados de alteração. Classes mutáveis, campos herdados e records são suportados. Em records, o handler retorna novas instâncias; utilize sempre o retorno de save/saveAll.

A detecção não consulta o banco. Um ID atribuído normalmente indica estado existente, mesmo que a chave ainda não esteja armazenada. Use `Persistable.isNew() == true` para declarar explicitamente uma criação com ID atribuído.

Os campos de criação fornecidos na entidade são preservados em atualizações. Isso não significa buscar os valores originais no banco nem impedir que a aplicação entregue valores forjados antes de chamar a biblioteca. Uma API deve receber DTOs sem campos de auditoria e carregar a entidade existente antes de modificar seus campos de negócio, como faz `/audited-notes`.

Nesta versão, anotações de auditoria devem estar na raiz da entidade. Metadados embutidos e implementações de `Auditable` são recusados. Cada campo deve ter uma única função de auditoria, ser serializado no JSON e não pode ser simultaneamente ID ou TTL.

## Callbacks e ordem

As interfaces em `io.datadam.spring.callback` estendem `EntityCallback<T>` do Spring Data.

| Callback | Momento |
|---|---|
| BeforeConvert | Antes da auditoria e da serialização |
| BeforeSave | Depois da auditoria, antes da geração de ID e da escrita |
| AfterSave | Depois da confirmação da escrita, com a chave efetiva |
| AfterConvert | Após desserializar cada entidade lida |

O ciclo de save é:

1. Validar mapeamento e capturar ID/estado novo.
2. Executar BeforeConvert.
3. Aplicar auditoria, se habilitada.
4. Executar BeforeSave.
5. Validar TTL, gerar ID quando necessário e preparar o JSON.
6. Gravar com a política NX/TTL correspondente.
7. Após sucesso, aplicar o ID gerado à instância mutável, quando aplicável.
8. Executar AfterSave e retornar a entidade resultante.

`DatadamSaveContext` informa tipo mapeado, prefixo, chave e estado novo capturado. A chave é null nas etapas anteriores à gravação quando o ID está ausente. Use DatadamIdGenerator para gerar IDs; callbacks anteriores não podem trocar a identidade ou o estado novo.

Callbacks da mesma etapa obedecem a Ordered ou @Order, com menores valores primeiro. A ordem vale dentro de cada operação e não serializa chamadas concorrentes. Beans de callback precisam ser seguros para uso simultâneo.

```java
@Component
@Order(100)
class ValidateNote implements BeforeSaveCallback<Note> {
    @Override
    public Note onBeforeSave(Note note, DatadamSaveContext context) {
        if (note.text() == null || note.text().isBlank()) {
            throw new IllegalArgumentException("Text is required");
        }
        return note;
    }
}
```

A configuração automática encontra os beans no ApplicationContext. Prefira classes com tipo genérico declarado para que o Spring filtre as entidades. Lambdas são aceitas, mas podem perder essa informação de tipo em runtime.

Um callback deve retornar uma entidade compatível e não null, podendo devolver outra instância. ID não pode mudar. Com auditoria ativa, callbacks também não podem alterar criação de uma entidade existente ou sobrescrever os campos preenchidos pelo handler. Se substituírem uma instância mutável, o ID será aplicado à instância efetivamente salva; use sempre o retorno.

### Leituras e outras operações

AfterConvert é aplicado em findById, findAll, findAllById, lookup e consultas IDX/FT, anotadas ou derivadas. Pode enriquecer o retorno sem regravar o documento; identidade e campos de auditoria são preservados.

Contagens e existência nativas sem materialização não disparam AfterConvert. O método CRUD existsById atualmente usa findById e, portanto, dispara o callback se encontrar uma entidade; existsBy derivado usa índices e não o dispara.

Comandos de DatadamCommands, persist(id) e exclusões ficam fora do ciclo de save. Este item não adiciona callbacks de exclusão nem auditoria a comandos nativos executados diretamente.

### Exceções e efeitos em memória

BeforeConvert e BeforeSave podem recusar a operação com uma exceção; a biblioteca não escreve o documento nesse caso. Falhas UNIQUE, de ACL ou de transporte também não executam AfterSave.

Alterações diretas no objeto e outros efeitos executados pelo próprio callback não recebem rollback. O handler pode preencher campos da instância mutável antes da escrita; isso não comprova persistência. O ID gerado pela biblioteca continua sendo aplicado à instância mutável somente após sucesso.

Uma falha de AfterSave é propagada como `AfterSaveCallbackException`: a escrita já foi confirmada, e getKey() identifica a chave gravada. Não há rollback ou repetição automática. Alterações de negócio feitas nessa etapa afetam apenas o retorno; prefira usá-la para observação ou informação transitória.

AfterConvert não grava. Enriquecimentos devem preferir campos ignorados no JSON para não serem persistidos por um save posterior. Autores complexos devem ser tratados como valores imutáveis: a proteção não é um mecanismo de segurança nem um snapshot profundo de todo o grafo.

Os limites de transporte, lote sequencial e durabilidade documento/TTL com UNIQUE continuam documentados nos itens anteriores. Não há optimistic locking ou transações nesta entrega.

## Configuração manual

Os construtores antigos continuam sem auditoria e sem callbacks registrados. Para configurar explicitamente:

```java
var auditing = new DatadamAuditing(
    () -> Optional.of(Instant.now(clock)),
    () -> Optional.of("application"), true, true
);
var template = new DatadamTemplate(commands, mapper,
    Map.of("datadamIdGenerator", new UuidDatadamIdGenerator()),
    EntityCallbacks.create(new ValidateNote()), auditing);
```

Um parâmetro de auditoria null desativa apenas o preenchimento automático; callbacks explícitos continuam funcionando.

## Exemplo HTTP e validação

A API `/audited-notes` usa records, ID gerado, TTL padrão de cinco minutos, índices IDX/FT e referência UNIQUE. O autor fixo de demonstração é `demo-application`; uma aplicação real deve fornecer sua identidade autenticada. AuditedNoteCallbacks remove espaços externos e recusa campos de negócio vazios. O DTO HTTP não recebe campos de auditoria.

```powershell
$noteData = @{ reference=[guid]::NewGuid().ToString('N'); category='demo'; text='  Nota auditada  ' }
$saved = Invoke-RestMethod http://127.0.0.1:8080/audited-notes -Method Post -ContentType application/json -Body ($noteData | ConvertTo-Json)
$saved
$noteData.text = 'Nota atualizada'
Invoke-RestMethod "http://127.0.0.1:8080/audited-notes/$($saved.id)" -Method Put -ContentType application/json -Body ($noteData | ConvertTo-Json)
```

AuditingLifecycleTest valida relógio, autores, conversões, configuração, proteções e ordem. AuditingIntegrationIT usa Datadam real para persistência, TTL, UNIQUE, recusas, materialização por índices e HTTP.
