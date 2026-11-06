# Consultas derivadas: operadores e limites

Métodos sem `@IdxQuery`/`@FtQuery` são compilados pelo `PartTree` do Spring Data em consultas nativas. Os nomes usam propriedades Java; Jackson resolve o caminho persistido e o schema fornece o alias nativo. Todos os filtros, limites e ordenações são executados no Datadam. A biblioteca não filtra páginas em memória.

## Matriz de operadores

| Família Spring Data | Tipo da propriedade | IDX | FT | Contrato |
|---|---|---|---|---|
| Sem sufixo, `Is`, `Equals` | String, enum, boolean, número | Sim | Sim | Igualdade; TAG escalar exato, NUMERIC conforme a representação numérica nativa |
| `Between` | Número | Sim | Sim | Ambos os limites inclusivos |
| `GreaterThan`, `GreaterThanEqual`, `LessThan`, `LessThanEqual` | Número | Sim | Sim | Limites exclusivos/inclusivos correspondentes |
| `True`, `False` | Boolean | Sim | Sim | Igualdade TAG escalar |
| `In` | Escalar | Não | Sim | União de igualdades; Collection ou array não vazio |
| `StartingWith`, `StartsWith`, `IsStartingWith` | String | Sim | Sim | **Novo em 5.2:** prefixo literal do campo inteiro |
| `Not`, `IsNot` | Número | Não | Sim | **Novo em 5.2:** presença NUMERIC e diferença do valor |
| `NotIn`, `IsNotIn` | Número | Não | Sim | **Novo em 5.2:** presença NUMERIC e exclusão da união de valores |
| `Not`, `NotIn` | String, enum, boolean | Não | Adiado | Falta teste de presença TAG que preserve os casos vazio/ausente/null |
| `IgnoreCase`, `AllIgnoreCase` | String | Não | Sim | TAG sem CASESENSITIVE; parâmetros ASCII; também se aplica a prefixos |
| `And` | Predicados suportados | Sim | Sim | Interseção; todos os predicados precisam caber no mesmo índice |
| `Or` | Predicados suportados | Não | Sim | União; grupos AND/OR preservados com parênteses |
| `IsNull`, `IsNotNull` | Qualquer | Adiado | Adiado | Índices não representam de modo uniforme null, ausência e valores não indexáveis |
| `Containing`, `EndingWith`, `Like`, `NotLike`, regex | String | Não | Não | Substring/sufixo/padrões não têm equivalente correto no subconjunto nativo atual |
| `Before`, `After` | Datas | Não | Não | Não há conversão implícita de datas em NUMERIC; use uma propriedade numérica explícita |
| `Near`, `Within`, buscas TEXT/GEO/VECTOR | Qualquer | Não | Não | Fora das capacidades implementadas pelo servidor |

“Sim” exige schema compatível. Um método incompatível falha na criação do repositório; parâmetros inválidos ou ordem dinâmica incompatível falham antes de enviar a consulta. Projeções derivadas, exclusão derivada e parâmetros Limit/scrolling continuam recusados. Projeções fixas estão disponíveis em [consultas anotadas e na API programática](projections.md).

## Prefixos literais

```java
Page<Customer> findByEmailStartingWithOrderByEmailAsc(String prefix, Pageable pageable);
List<Customer> findByCityStartingWithIgnoreCaseOrderByAgeAsc(String prefix);
Page<Customer> findByCityStartingWithAndAgeNotOrderByAgeAsc(
    String prefix, int excludedAge, Pageable pageable);
```

IDX usa a faixa TAG `>= prefix` e `< sucessor(prefix)`, com argumentos RESP separados. O sucessor incrementa o último ponto Unicode incrementável e remove o sufixo máximo. A ordem por bytes UTF-8 preserva a ordem dos pontos Unicode; a faixa ignora a região de surrogates. Um prefixo composto somente por U+10FFFF não precisa de limite superior. Strings com surrogates isolados são recusadas.

FT usa `@alias:{$p0*}` com o valor separado em `PARAMS`. Somente o último `*`, escrito pelo compilador, é operador. Asteriscos, barras, chaves e pipes recebidos no parâmetro permanecem literais, sem interpolação ou escape construído pelo chamador.

Regras:

- Prefixo null ou vazio é recusado. `StartingWith("")` não significa “todos”. Campos ausentes, null e vazios não correspondem a um prefixo não vazio.
- A versão padrão diferencia maiúsculas. FT exige `caseSensitive=true`; `IgnoreCase` exige TAG sem CASESENSITIVE e valores ASCII, conforme o folding do servidor.
- A TAG precisa representar o campo inteiro, sem separador. TAGs divididas em tokens não implementam `String.startsWith` do valor original.
- IDX HASH sem separador suporta prefixos do campo inteiro, inclusive vírgulas literais. FT HASH divide TAG por vírgula por padrão; esses campos são recusados para derivação escalar, inclusive prefixos e IgnoreCase.
- O parser FT remove espaços/tabs iniciais de uma TAG. Prefixos que começam por esses caracteres são recusados quando o plano escolhido é FT; IDX preserva-os. Não há fallback automático após a seleção do plano. Espaços finais do prefixo são preservados.
- A ordenação IDX segue a tupla do índice. Um prefixo não fixa o campo por igualdade: num índice `(city, age)`, ordenar somente por age após um prefixo de city requer outro plano, como FT com ordenação numérica. FT aceita um campo de ordenação.

## Negação numérica e campos ausentes

```java
Page<Customer> findByAgeNot(int excludedAge, Pageable pageable);
Page<Customer> findByAgeNotIn(Collection<Integer> excludedAges, Pageable pageable);
```

`Not(25)` compila para `(@age:[-inf +inf] -@age:[$p0 $p0])`. `NotIn([20,30])` compila para `(@age:[-inf +inf] -(@age:[$p0 $p0] | @age:[$p1 $p1]))`.

A faixa de presença é necessária: a negação FT isolada também pode incluir documentos sem o campo. O contrato entregue inclui somente documentos com valor NUMERIC indexável diferente dos valores excluídos. Ausência, JSON null e valores inválidos não passam. Essa regra é aplicada antes da paginação e da contagem, em JSON e HASH.

Valores null, membros null, coleção/array vazio e números NaN/infinitos são recusados. `NotIn` aceita Collection e arrays, inclusive primitivos. A comparação segue a representação numérica do índice Datadam; não acrescenta precisão decimal ou de int64 à representação nativa.

Negação de TAG permanece adiada. Um complemento de igualdade não oferece o mesmo contrato de presença para null, ausência e String vazia. A biblioteca rejeita esses métodos na inicialização, sem executar consultas complementares ou filtrar resultados em memória.

## Resultados, totais e schema

List, Page, Slice, entidade, Optional, count, exists e Top/First mantêm os contratos existentes. Count/exists não materializam entidades; Slice busca um item adicional; Top/First usa limite nativo. Top/First não combina com Pageable/count/exists. Resultado único não aceita Pageable.

Page IDX usa QUERY e COUNT separados: sob concorrência, conteúdo e total podem refletir momentos distintos. FT retorna conteúdo e total no mesmo comando. Todos os campos de um IDX composto precisam ser exigidos pelos predicados para não perder documentos com campos ausentes. Nenhuma consulta derivada cria ou migra índices.

## Aplicação de exemplo e validação

Os repositórios `CustomerRepository` e `HashCustomerRepository` expõem prefixos por email e negação por idade. `CustomerRepository` demonstra também prefixos FT, IgnoreCase e combinação com negação e OR. Os endpoints aceitam paginação Spring:

```powershell
Invoke-RestMethod 'http://localhost:8080/customer-queries/json/prefix?prefix=ana&page=0&size=10'
Invoke-RestMethod 'http://localhost:8080/customer-queries/hash/prefix?prefix=ana&page=0&size=10'
Invoke-RestMethod 'http://localhost:8080/customer-queries/json/age-not-in?excluded=20,30&page=0&size=10'
Invoke-RestMethod 'http://localhost:8080/customer-queries/hash/age-not-in?excluded=20,30&page=0&size=10'
```

`DerivedOperatorsTest` verifica planos, parâmetros, aliases, limites Unicode e recusas antes de I/O. `DerivedOperatorsIntegrationIT` executa os operadores contra Datadam real no Docker, cobrindo JSON/HASH, caracteres literais, combinações, ausências, ordenação, totais, TTL e HTTP. Execute `scripts/run-local.ps1 -Restart -IntegrationTests -Background`.

Esta entrega usa os operadores IDX/FT existentes no servidor; não acrescenta uma extensão C++. As dependências de HREPLACE/RETURNJSON das etapas anteriores estão descritas em [server-patches](../server-patches/README.md).
