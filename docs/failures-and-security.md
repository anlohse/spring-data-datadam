# Falhas de transporte, TLS e ACL

A entrega 7.2 amplia a validação com servidores Datadam reais e contêineres descartáveis. Os testes não pausam/reiniciam o serviço usado pela aplicação, não usam FLUSHALL e não alteram ACLs ou certificados do host. A correção de produção desta etapa habilita verificação de identidade do endpoint TLS no pool automático.

## Resultados incertos e ausência de repetição automática

Quando uma conexão cai ou a resposta excede o timeout, a exceção pode ter o mesmo tipo em situações diferentes:

| Ponto da falha no teste | O que o servidor recebeu | Resultado persistido |
|---|---|---|
| Proxy fecha antes de encaminhar JSON.NUMINCRBY | Nenhuma mutação | Contador permanece 0 |
| Proxy recebe a resposta e a descarta | Uma mutação concluída | Contador passa a 1 |
| Proxy retém a resposta após a execução e o cliente sofre timeout | Uma mutação concluída | Contador passa a 1 |

Os três cenários resultam em `DataAccessResourceFailureException` para o consumidor. O cliente **não pode inferir, somente pela exceção, se a escrita foi aplicada**. `connection_error` nas métricas também não resolve essa ambiguidade.

A biblioteca não repete gravações automaticamente. O teste conta as requisições efetivamente encaminhadas e confirma uma única tentativa. Repetir manualmente um incremento depois da perda da resposta pode incrementá-lo duas vezes. O mesmo cuidado vale para append/pop de arrays, scripts, operações em lote e comandos com efeitos externos de callbacks.

Depois de um erro:

1. Preserve a identificação da operação e evite repetição cega no cliente HTTP, job ou política genérica de retry.
2. Consulte o estado atual por uma conexão utilizável e faça reconciliação conforme as regras do domínio. Sob concorrência, a simples leitura de um contador não identifica necessariamente qual operação foi aplicada.
3. Se o domínio exigir repetição segura, modele uma chave de idempotência/deduplicação e a atomicidade necessária no servidor. A biblioteca não acrescenta essa garantia por conta própria.
4. Para lotes, considere êxito parcial: operações anteriores podem ter sido confirmadas antes da falha.

Na falha de aquisição antes do envio de um comando, esse comando ainda não foi enviado por essa chamada de transporte. Entretanto, uma operação de repositório pode já ter executado comandos anteriores; não generalize essa observação para rollback da operação inteira.

## Versão e confirmação perdida

O teste de save com `@Version` derruba a resposta do EVAL **depois** de o Datadam confirmar a execução. O documento no servidor passa de versão 0 para 1; o objeto recebido pelo cliente continua na versão anterior, pois não houve confirmação de sucesso. A gravação não é repetida internamente.

Uma repetição explícita com a versão antiga falha por optimistic locking, e uma nova leitura encontra a gravação original. Isso ajuda a detectar estado desatualizado; não transforma todas as operações em idempotentes nem desfaz callbacks já executados. Reconcilie o objeto e a intenção da operação antes de decidir se deve gravar novamente.

## Reinício, conexão quebrada e pool

`TransportFailuresIT` aquece um pool, reinicia seu servidor descartável e executa novamente comandos de negócio. Com `validate-connection=true`, o empréstimo testa a conexão ociosa, descarta a conexão encerrada pelo reinício e usa outra. Isso ocorre antes do envio do comando de negócio; não é uma repetição de uma gravação já enviada.

Depois de erro durante a leitura da resposta, os testes verificam também que a conexão quebrada não impede o próximo PING. Essa recuperação de conectividade não informa o resultado da mutação que perdeu a resposta.

O teste concorrente de saturação ocupa a única conexão de um pool próprio. Uma chamada espera e prossegue quando ela é devolvida; outra, sem devolução, falha dentro do limite configurado. Ao final, o contador de espera volta a zero e o pool continua utilizável. O health check sob saturação já é coberto pela entrega 7.1.

Configure conexão, leitura e espera conforme [diagnóstico operacional](library-diagnostics.md). Max-wait limita a espera pelo pool; não é um prazo único de autenticação, validação, comando e operação inteira de repositório. Os testes usam folga de tempo para escalonamento do SO; não são benchmarks nem garantias de latência.

## TLS: cadeia, validade e identidade

```yaml
datadam:
  host: datadam.internal.example
  port: 6380
  ssl: true
```

O pool automático usa o truststore JSSE da JVM e configura `SSLParameters.endpointIdentificationAlgorithm=HTTPS`. O nome/IP configurado precisa corresponder ao certificado do servidor. Apesar do nome do algoritmo, o protocolo transportado continua sendo RESP sobre TLS, não HTTP.

**Mudança de comportamento:** habilitar apenas criptografia sem validar o nome do endpoint não era suficiente na configuração anterior. A partir desta entrega, certificados de outra identidade são recusados, mesmo que sua CA seja confiável. Ajuste host e SAN do certificado ao atualizar; não desative a validação para contornar a recusa. Um pool externo fornecido pela aplicação continua responsável por sua própria configuração TLS.

Para uma CA privada, configure um truststore apropriado antes de iniciar a JVM, por exemplo com `javax.net.ssl.trustStore`, `javax.net.ssl.trustStoreType` e `javax.net.ssl.trustStorePassword`. Mantenha senhas e chaves na configuração segura do ambiente. Não é instalado um trust manager que aceite qualquer certificado, nem há opção da biblioteca para desligar a verificação de nome.

`TlsSecurityIT` gera CA e certificados efêmeros com `keytool`, disponível no JDK, e testa:

| Cenário | Truststore do cliente | Certificado do servidor | Resultado exigido |
|---|---|---|---|
| Válido | CA correta | Válido, SAN localhost/127.0.0.1 | PING autenticado responde PONG |
| CA desconhecida | Outra CA | Mesmo certificado válido | Falha de handshake |
| Identidade incorreta | CA correta | SAN wrong.invalid | Falha de handshake |
| Expirado | CA correta | SAN correto, validade encerrada | Falha de handshake |

Cliente e servidor TLS rodam no Docker, compartilhando a rede do contêiner. O cliente usa as classes/dependências realmente compiladas pelo Maven, em uma JVM separada com truststore próprio. Isso evita que inspeção HTTPS/TLS do host substitua o certificado usado no teste; nesta máquina, o Avast de fato substituiu o certificado de localhost durante a primeira tentativa fora do Docker.

Os testes não alteram o antivírus, a confiança global da JVM de testes ou o repositório de certificados do Windows. A chave da CA permanece somente no diretório temporário do processo de testes; o servidor recebe apenas certificado/chave de servidor e o cliente somente truststores públicos. Os arquivos e contêineres são descartados ao final.

O cenário usa autenticação ACL sobre TLS e `tls-auth-clients no`: valida autenticação do **servidor** e senha do cliente. Não constitui teste de mTLS, rotação de certificados, todos os provedores JSSE ou todas as versões TLS. Esses comportamentos não são inferidos pelos testes desta entrega.

## Matriz ACL ampliada

`AclSecurityIT` cria seu próprio Datadam com protected-mode habilitado, usuário default desligado e três papéis:

| Papel | Dados | Consultas | Administração |
|---|---|---|---|
| writer | JSON/HASH no prefixo secure:* | Sem permissões de índices | Sem DDL/metadados de índices |
| reader | Somente chaves visible de JSON/HASH | IDX/FT restritos às chaves legíveis; EVAL autorizado somente para testar checagens internas | Metadados explicitamente permitidos, sem DDL |
| schema | Sem leitura/escrita de documentos | Sem consultas a documentos | CREATE/INFO/LIST/DROP de índices |

São verificados:

- Autenticação obrigatória e recusa de escrita fora do prefixo do writer.
- Leitura de chaves permitidas e recusa de chaves ocultas em JSON e HASH.
- Recusa de JSON.SET, HREPLACE e DEL pelo reader, mesmo em chaves legíveis.
- Conteúdo, projeção nativa e totais IDX/FT restritos às chaves autorizadas nos dois formatos.
- Recusa de escrita ou leitura de chave oculta por EVAL: autorizar o script não dispensa a ACL dos comandos/chaves chamados dentro dele.
- Separação entre administração de índices e acesso a documentos; DROP de índice mantém documentos.

Metadados IDX.INFO/FT.INFO são globais: um reader que recebeu FT.INFO vê contagens de todas as chaves do índice, embora suas consultas retornem apenas as chaves autorizadas. O teste afirma essa diferença; não promete isolamento de metadados por padrão de chave. Privilégios administrativos de índice também não são limitados pelo nome do índice via padrões de chaves.

Esses usuários existem somente no contêiner descartável. A ACL da aplicação local não é ampliada para executar os testes. As suítes anteriores continuam cobrindo usuários sem JSON.GET/HGETALL, escrita com TTL negado, callbacks, UNIQUE e as credenciais separadas de migração.

## Como reproduzir

Pré-requisitos: JDK com keytool, Maven, Docker CLI/Desktop e a imagem do Datadam do projeto. Desde a entrega 7.3, revisões e digests ficam em [config/test-images.properties](../config/test-images.properties); o servidor usa uma revisão C++ explícita e as JVMs usam digests. A JVM TLS isolada pode ser indicada por `DATADAM_TLS_JAVA_IMAGE` e precisa executar bytecode Java 17 e fornecer o comando java. Veja a [matriz de compatibilidade](compatibility-and-performance.md) para o escopo validado.

```powershell
./scripts/run-local.ps1 -BuildDatadam -Restart -IntegrationTests -Background
```

O script prepara a imagem da JVM TLS quando ela ainda não existe. Para executar Maven diretamente:

```powershell
./scripts/build-test-image.ps1
docker compose --env-file config/test-images.properties up -d --no-build
# Prepare as imagens Java do contrato, ou use run-local.ps1 -IntegrationTests.
mvn verify -Pintegration
```

Se a aplicação estiver usando o JAR no Windows, pare/reinicie-a com o script antes de reempacotar. Para uma execução focada, use `-Dit.test=TransportFailuresIT,TlsSecurityIT,AclSecurityIT` e `-Dfailsafe.failIfNoSpecifiedTests=false` no reactor. A execução padrão não faz skip dos testes se Docker, imagem, chave/certificado ou handshake não estiverem disponíveis: falha explicitamente.

Os contêineres têm nomes UUID próprios e portas aleatórias publicadas somente em loopback. O helper usa uma porta explicitamente reservada para que o endereço não mude no reinício. O proxy de falhas tem limites de framing e encerra sockets/workers ao final. Não serve como proxy de produção nem modelo de transporte da biblioteca.

Referências primárias: [SSLParameters e identificação de endpoint](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/net/ssl/SSLParameters.html#setEndpointIdentificationAlgorithm(java.lang.String)), além de `docs/security.md` e `docs/secondary-indexes.md` no código Datadam usado pelos testes.
