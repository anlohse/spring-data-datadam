# Transações nativas e integração com Spring

A biblioteca mantém as operações de repositório por entidade. A investigação da etapa 9, com o Datadam `e2e2c0ae4e9282dd4b1d815e9d893931bd41bd3e`, concluiu que `MULTI`/`EXEC` é útil para lotes nativos com resultados individuais. Um gerenciador que incluísse as operações atuais de repositório teria de mudar o momento em que essas operações retornam resultados e erros.

## Comportamento verificado

Todos os comandos de cada bloco foram enviados pela mesma conexão. A verificação de estado e os conflitos de `WATCH` usaram outra conexão.

| Situação | Resultado observado |
|---|---|
| `MULTI`, escrita e leitura da mesma chave | O servidor respondeu `QUEUED`. Antes de `EXEC`, outro cliente leu o valor antigo. A leitura dentro de `EXEC` retornou o valor da escrita enfileirada. |
| Comando recusado durante o enfileiramento por aridade inválida | `EXECABORT`. A escrita já enfileirada ficou sem efeito. |
| `DISCARD` | A fila foi descartada antes da execução. |
| Alteração concorrente de chave observada com `WATCH` | `EXEC` retornou nil e nenhum comando da fila rodou. Uma escrita em chave alheia à observação permitiu a execução. |
| Erro durante `EXEC` | O erro apareceu na posição do comando. As escritas anterior e posterior ficaram aplicadas. |
| Duplicata em índice `UNIQUE` | A criação duplicada foi recusada. A gravação anterior do pedido ficou aplicada. |
| Versão obsoleta no script de estoque | O script retornou `0` na posição correspondente. A quantidade ficou em 10 e a gravação do pedido ficou aplicada. |
| Conexão perdida na fronteira de `EXEC` | Cortar o pedido antes do servidor deixou os dados intactos. Cortar a resposta depois do servidor deixou as duas escritas aplicadas. O cliente recebeu falha de conexão nos dois casos. |

Isolamento descreve o que outros clientes podem observar durante o bloco. O servidor documenta isolamento de leituras e escritas para as chaves cobertas. Rollback descreveria desfazer escritas já executadas caso uma operação falhasse. Os resultados acima mostram que erros de execução não têm esse efeito. A durabilidade após crash é outra propriedade. A revisão do servidor documenta commits antecipados para comandos como `IDX.QUERY`, `FT.SEARCH` e escritas em `UNIQUE`, ainda sob os locks do bloco. Os testes Java exercitam os resultados do protocolo e não simulam crash no meio do commit. A documentação nativa também distingue uma escrita que altera a expiração da expiração natural de uma chave observada. [Contrato nativo na revisão testada](https://github.com/anlohse/datadam/blob/e2e2c0ae4e9282dd4b1d815e9d893931bd41bd3e/docs/transactions.md).

## Decisão sobre `@Transactional`

O transporte atual empresta uma conexão do pool por comando. Uma transação nativa exigiria reservar a mesma conexão de `WATCH` até `EXEC` ou `DISCARD`, além de devolver ao pool apenas conexões com estado de sessão limpo. Vincular a conexão à thread resolveria essa parte da integração, mas manteria as seguintes incompatibilidades:

- `save` devolve o ID, a versão e o estado persistido depois da confirmação. Com `MULTI`, o servidor responde `QUEUED` e só confirma em `EXEC`. Isso afetaria retornos, callbacks pós-save e a detecção de versão obsoleta.
- Leituras de repositório retornam entidades e páginas no ponto da chamada. Uma leitura enfileirada só teria resultado em `EXEC`. Executá-la fora da fila não daria acesso às escritas pendentes.
- `REQUIRED` precisaria compartilhar a mesma sessão e confirmar apenas no escopo externo. `REQUIRES_NEW` precisaria suspender esse vínculo e usar outra conexão. `NESTED` precisaria de savepoints, ausentes no protocolo nativo. Mesmo com apenas `REQUIRED`, erros de `EXEC` continuariam permitindo êxito parcial e perda de resposta continuaria exigindo reconciliação.

A etapa adia o gerenciador de transações Spring. Um futuro lote explícito poderia expor resultados por posição e estados distintos para conflito, recusa de fila, êxito parcial e resultado desconhecido. Para retomar uma integração de repositório, é necessário um contrato de leitura, escrita, callbacks e propagação que possa ser comprovado com o transporte e as garantias do servidor disponíveis naquele momento.

## Pedido e estoque

O caso 9.1 é ilustrado pela suíte nativa. Ela enfileira a gravação de um pedido e uma alteração de estoque. `WATCH` permite recusar a fila diante de uma escrita concorrente na chave observada. Se a fila roda e o estoque recusa a alteração por versão, o pedido pode existir sem a alteração da quantidade. Se a resposta se perde, a aplicação precisa ler o pedido pelo ID e conferir o estoque antes de decidir outra tentativa. Um exemplo de checkout com `PurchaseOrder`, `StockItem` e `@Transactional` fica adiado até existir um contrato de aplicação que trate esses estados.

Com a imagem fixada no projeto e o Docker iniciado, execute a investigação pela suíte de integração. O runner cria e remove seu próprio Datadam.

```powershell
mvn -B -ntp -pl datadam-demo -am -Pintegration '-Dit.test=NativeTransactionsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```
