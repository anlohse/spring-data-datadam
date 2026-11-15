# CI e preparação de releases — 8.2

O destino definido é **Maven Central, pelo Central Portal**. O projeto prepara um candidato verificável; publicar continua sendo uma ação separada. Não há upload, criação de tag, GitHub Release ou publicação automática em nenhum dos scripts/workflows desta entrega.

Desde 09/11/2026, o groupId e os pacotes Java usam **com.anlohse.datadam**, sob o namespace **com.anlohse**. Atualize dependências, imports e entry points anteriormente em `io.datadam`. Candidatos antigos devem ser reconstruídos, mesmo quando o groupId já estiver atualizado, para incorporar as novas classes, recursos e registros Boot. O montador também verifica o groupId do manifesto e as coordenadas dos POMs.

## CI

`.github/workflows/ci.yml` roda em push para main, pull requests e acionamento manual. Usa Ubuntu 24.04, PowerShell 7 e Java 25, com actions fixadas por SHA e permissão somente de leitura. Não usa `pull_request_target` nem segredos de release. Compila o C++ arquivado da revisão em `config/test-images.properties`; não incorpora alterações de um checkout nativo nem substitui Datadam por Redis.

`scripts/run-ci.ps1` executa os mesmos passos localmente:

```powershell
# Em um checkout de validação, sem o JAR do demo em execução nessa pasta:
.\scripts\run-ci.ps1 -BuildDatadam -Source D:/cpp/workspaces/datadam
# Se a imagem fixada já existe:
.\scripts\run-ci.ps1
```

O runner inicia um servidor descartável com ACL do demo em porta efêmera/loopback, executa `mvn install -Pintegration`, verifica os artefatos e empacota/executa o consumidor independente. A suíte inclui Java 17/21/25, TLS/ACL, reinício, perda de resposta e recuperação de backfill. Os testes de falha criam seus próprios contêineres. Ao terminar, remove somente os recursos que criou e restaura o ambiente. Não altera o servidor local do demo nem limpa seu volume. A execução Maven pode sobrescrever os JARs de target: use outro checkout se estiver executando o demo a partir deles.

O workflow publica relatórios JUnit, logs da matriz e manifestos como artefatos de validação por 14 dias. Isso não publica pacotes Maven. O build C++ pode levar mais tempo que os testes Java; `timeout-minutes` limita a execução. Credenciais TLS de inspeção do Windows são configuradas somente no Windows; Linux usa seu truststore normal.

## Preparar um candidato

```powershell
.\scripts\prepare-release.ps1 -Version 0.1.0-rc.1
# Com chave real configurada fora do repositório, para um candidato destinado à publicação:
.\scripts\prepare-release.ps1 -Version 0.1.0-rc.1 -Sign
```

O script cria uma cópia em `target/releases/<versão>-<UUID>/source`, muda a versão dos POMs e o campo SCM tag apenas nessa cópia, instala o parent e as três bibliotecas e executa o smoke test sobre esses JARs. Não muda a versão do checkout de desenvolvimento. Os testes unitários são reexecutados na versão candidata. A CI completa deve estar aprovada para o mesmo código antes da aprovação de uma release.

É permitido preparar uma prévia local com alterações ainda não commitadas; `manifest.json` registra `dirtySource=true` e o commit de origem. Essa prévia não é uma release publicável. Commitar as alterações, revisar e preparar novamente de um checkout limpo são passos obrigatórios do checklist. O bundle não deve conter o demo nem o consumidor, somente os quatro POMs e os nove JARs de biblioteca/fontes/documentação.

Saídas:

- `bundle/`: POMs/JARs e assinaturas quando `-Sign` foi usado.
- `manifest.json`: versão, commit, estado do checkout, destino, hashes SHA-256 e `published=false`.
- `consumer-verification.json`: startup, versão carregada, health por PING e operações de negócio.
- `central-repository/` e `central-bundle.zip`: layout `com/anlohse/datadam/<artefato>/<versão>`, com checksums MD5/SHA1/SHA256/SHA512 para cada POM/JAR e assinaturas dos payloads quando assinados.
- `central-bundle.json`: hash do ZIP, contagem de payloads e `portalValidation=NOT_RUN`/`submitted=false`.

O comando `new-central-bundle.ps1 -Manifest <arquivo> -RequireSignatures` exige um candidato assinado e verifica os hashes de payload/assinatura antes de montar outro bundle em uma saída ainda não existente. Os hashes detectam alterações acidentais; não provam a identidade de quem assinou. O registro de `signed=true` indica que o perfil de assinatura terminou, e o Central Portal ainda fará suas próprias validações. A validação local desta entrega verificou também criptograficamente as assinaturas usando uma chave descartável.

O starter não expõe classes de API, mas fornece um `-javadoc.jar` com HTML explicando as dependências e apontando para a documentação do núcleo/autoconfiguração. Esse arquivo atende ao formato exigido para artefatos de packaging JAR. Confira os [requisitos do Central](https://central.sonatype.org/publish/requirements/) e o [layout para upload de bundles](https://central.sonatype.org/publish/publish-portal-upload/).

## Smoke test — exemplo 8.2.1

```powershell
# Após instalar/preparar a versão candidata:
.\scripts\smoke-release.ps1 -Version 0.1.0-rc.1
```

O consumidor fora do reactor compila com a versão selecionada e verifica, em tempo de execução, `pom.properties` dos JARs core/autoconfigure/starter. Versões divergentes falham antes de iniciar o fluxo de negócio. O processo inicia o contexto Spring, faz um health PING pelo pool e executa cadastro/leitura, paginação IDX, projeção FT, UNIQUE e controle de versão, com dados próprios e limpeza por IDs conhecidos.

Saída esperada: `CONSUMER_VERSION 0.1.0-rc.1`, `CONSUMER_HEALTH_OK ping=PONG`, `CONSUMER_OK ...` e `RELEASE_SMOKE_OK ...`. Esse health é uma verificação CLI; não há endpoint HTTP/Actuator no consumidor. A senha de schema é removida antes do processo da aplicação. Credenciais de assinatura e tokens Central também são retirados do ambiente dos processos de validação do consumidor e restaurados ao terminar.

## Workflow de candidato e segredos

`.github/workflows/release-candidate.yml` só é acionado manualmente em main. Executa a CI completa, prepara a versão informada e entrega o bundle para revisão. `sign=false` é o padrão. Para `sign=true`, configure o environment **release-preparation** com revisores obrigatórios e acesso restrito a main antes do primeiro uso. Essa configuração de conta/repositório não é criada pelos arquivos YAML e ainda deve ser feita pelo mantenedor.

Use os secrets `MAVEN_GPG_KEY` (chave privada OpenPGP exportada) e `MAVEN_GPG_PASSPHRASE` nesse environment. O perfil `release-sign` usa Maven GPG Plugin 3.2.8, signer BC e `bestPractices=true`; não depende de um executável GnuPG no runner e não recebe senha por argumento/POM. A chave só é disponibilizada no passo de assinatura. Consulte a [documentação oficial de assinatura](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html).

Não commite chaves, senha, settings locais nem tokens. O template `config/maven-settings.xml.example` usa referências a `CENTRAL_TOKEN_USERNAME` e `CENTRAL_TOKEN_PASSWORD`, para ferramentas Maven que precisem do token do Portal. Ele não contém credenciais e não é lido pelos scripts atuais: o fluxo atual usa upload manual do ZIP pelo Portal. Nunca use o token do GitHub como credencial Central. Orientação oficial: [token de publicação](https://central.sonatype.org/publish/generate-portal-token/).

## Checklist de release

### Antes de aprovar

- [ ] Verificar o namespace **com.anlohse** na conta/organização do Central Portal usando o domínio anlohse.com. O groupId do projeto é **com.anlohse.datadam**; a posse do domínio ainda precisa ser verificada no Portal.
- [ ] Configurar os revisores do environment e a chave real de assinatura; publicar a chave pública no keyserver aceito pelo Central. A chave descartável desta validação não serve para releases.
- [ ] Commitar/revisar a entrega, com mensagens/PR em inglês; confirmar checkout limpo e CI aprovada para o commit exato.
- [ ] Escolher versão ainda não publicada; revisar `CHANGELOG.md`, política de compatibilidade e guia de atualização.
- [ ] Preparar novamente com `-Sign`; conferir nove JARs/quatro POMs, 13 assinaturas, checksums e smoke test da versão.
- [ ] Conferir `sourceCommit`, `dirtySource=false`, versão, tag SCM e SHA-256 do ZIP. Criar a tag revisada `v<versão>` apontando para esse commit, conforme o processo aprovado pelo mantenedor.
- [ ] Obter autorização explícita para o conjunto **versão + commit/tag + hash do bundle + Maven Central**.

### Publicação — separada e ainda pendente

- [ ] Somente após autorização, entrar no [Central Portal](https://central.sonatype.com) e enviar o ZIP assinado revisado. Upload já envia os artefatos a um serviço externo; não é executado pelos runners deste projeto.
- [ ] Conferir as validações do Portal. Estar em VALIDATED não equivale a estar publicado; revisar antes da ação Publish.
- [ ] Publicar explicitamente no Portal; aguardar confirmação e conferir as quatro coordenadas e todos os classificadores.
- [ ] Executar o consumidor resolvendo a versão do Central em um Maven local limpo, registrar links/resultado e atualizar changelog/checklist.

Publicações são imutáveis: não se deve corrigir uma versão já publicada sobrescrevendo seus arquivos. A reprodução de consumo depois do upload e a aprovação do Portal não foram realizadas nesta entrega. Os arquivos de workflow ainda precisam ser commitados/enviados para que possam executar no GitHub; a validação registrada aqui é a reprodução local do runner e a análise estática dos YAMLs.

## Atualizar aplicações

Troque a dependência Boot antiga por `com.anlohse.datadam:datadam-spring-boot-starter` e mantenha a mesma versão nos três artefatos. Não misture o núcleo do SNAPSHOT anterior com a nova autoconfiguração. Para atualização local, instale os artefatos antes de construir o consumidor; para versão publicada, remova resíduos da versão candidata do cache de validação e confirme sua origem no Central.

Leia o [changelog](../CHANGELOG.md), a [política da API](public-api-and-compatibility.md) e os limites de transporte, TLS, TTL e scripts antes de atualizar. Não há mudança C++ nesta etapa; a revisão nativa exigida continua fixada no contrato de imagens. Uma versão candidata local não muda a versão SNAPSHOT do desenvolvimento nem constitui uma release anunciada.

## Evidência desta entrega

Em 08/11/2026, o runner foi reproduzido no Windows em uma cópia isolada: 395 testes aprovados e 564 reexecuções unitárias na matriz Docker. Os YAMLs passaram no actionlint e os scripts no parser PowerShell. O candidato local 0.1.0-rc.1 teve 13 assinaturas verificadas criptograficamente com chave descartável (apagada após o teste); o ZIP contém 13 payloads, 13 assinaturas e 52 checksums. As recusas de versão divergente, hash alterado, assinatura ausente e SNAPSHOT passaram. [Evidência e hashes](validation/2026-11-08-ci-release.json).

Esse bundle tem alterações ainda não commitadas e foi assinado com chave de teste, portanto não deve ser publicado. Nenhum upload ao Central, verificação externa de namespace, execução hospedada do workflow ou publicação foi realizado. A etapa pendente está explicitamente aberta no roadmap.
