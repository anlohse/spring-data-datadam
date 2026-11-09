# Consumidor Spring Boot independente

Este projeto usa somente `com.anlohse.datadam:datadam-spring-boot-starter` instalado no Maven local. Seu parent é o Spring Boot, com `<relativePath/>`; não faz parte dos módulos do reactor, não herda o POM Datadam e não importa classes do demo. Você pode copiar esta pasta para outro diretório e executar o mesmo build depois de instalar os artefatos.

Em versões anteriores deste checkout, groupId e pacotes Java usavam `io.datadam`. Agora a dependência usa `com.anlohse.datadam` e os imports usam `com.anlohse.datadam.spring.*`. O smoke test lê os metadados Maven do novo groupId nos três JARs e inicia a autoconfiguração com os novos nomes de classes.

## Verificação automática

Na raiz do repositório principal:

```powershell
.\scripts\verify-consumer.ps1 -Install
```

`-Install` compila/testa/instala o núcleo, a autoconfiguração e o starter. Em outra chamada Maven, o script constrói este POM sem `-am`, resolve os JARs instalados e executa o JAR empacotado contra um Datadam descartável. O script verifica a revisão da imagem, usa seu ID e porta efêmera restrita a loopback. Remove somente seu próprio contêiner e restaura as variáveis de ambiente ao terminar.

O bootstrap de índices roda em outro processo com `consumer-schema`; o aplicativo usa `consumer-app`, sem CREATE/DROP. A senha administrativa é removida do ambiente antes de iniciar o aplicativo. Espera-se `CONSUMER_SCHEMA_READY` e depois `CONSUMER_OK crud=true idx=true ftProjection=true unique=true version=true`.

O fluxo usa IDs/SKUs próprios e verifica CRUD, SKU UNIQUE, consulta derivada IDX por autor/faixa de páginas com ordenação e total, projeção FT TAG/NUMERIC, edição versionada e recusa de versão obsoleta. Confere a versão real nos três JARs e executa health CLI por PING. Limpa somente os três IDs de fixture conhecidos antes do envio. Registra resultado/imagem/versão em `target/verification.json`; a árvore de dependências fica em `target/datadam-dependencies.txt`.

Sem `-Install`, o script valida somente o consumidor. `-LibraryVersion` seleciona a versão previamente instalada; não publica artefatos.

Para validar uma release candidata já instalada, use `scripts/smoke-release.ps1 -Version 0.1.0-rc.1` na raiz principal. O resultado inclui `CONSUMER_VERSION`, `CONSUMER_HEALTH_OK` e `RELEASE_SMOKE_OK`. Chaves de assinatura, tokens Central e a senha de schema são removidos do ambiente dos processos da aplicação; o wrapper restaura os valores anteriores depois da execução. Veja o [fluxo de release](../../docs/ci-and-release.md).

## Executar sem o wrapper

Primeiro, na raiz principal, instale os artefatos:

```powershell
mvn -B -ntp -pl datadam-spring-boot-starter -am install
```

Depois, nesta pasta, como em um projeto separado:

```powershell
mvn -B -ntp -Ddatadam.version=0.1.0-SNAPSHOT clean package
docker compose up -d --no-build
$env:DATADAM_HOST = '127.0.0.1'
$env:DATADAM_PORT = '6381'
$env:CONSUMER_SCHEMA_PASSWORD = 'local-consumer-schema-password'
$jar = (Resolve-Path .\target\consumer-boot-1.0.0-SNAPSHOT.jar).Path
java '-Dloader.main=example.datadam.SchemaApplication' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher
$env:CONSUMER_SCHEMA_PASSWORD = $null
$env:CONSUMER_APP_PASSWORD = 'local-consumer-app-password'
java -jar $jar
docker compose down
```

O Compose é exclusivo deste consumidor: porta 6381, usuários próprios, prefixo `consumer:book:` e volume `datadam-consumer_consumer-data`. `down` preserva o volume. As credenciais no arquivo Docker são apenas de desenvolvimento local. A imagem padrão é a revisão de desenvolvimento fixada no repositório; prepare-a pelo script principal `build-test-image.ps1` se ainda não existir. O wrapper automatizado também verifica o label da revisão e fixa o ID observado.

## Contrato da aplicação

O aplicativo é CLI, sem servidor HTTP, Actuator nem Micrometer Core. O starter não ativa varredura de repositórios, auditoria ou DDL por conta própria: `@EnableDatadamRepositories` aponta para `BookRepository` e o bootstrap é explícito. O esquema requer Datadam com RETURNJSON; Redis comum não substitui esse servidor.

O JAR usa Java release 17 e Boot 3.5.16. Para outras versões, consulte a matriz e a política de compatibilidade do projeto principal. Um erro de transporte em escrita não causa retry automático; o caller deve reconciliar pelo ID/SKU. O exemplo usa IDs de entrada conhecidos para permitir essa investigação e limpeza. Não há transação entre cadastros, consulta e exclusão.
