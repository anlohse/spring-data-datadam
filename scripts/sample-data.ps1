param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
$customers = @(
    @{id='demo-ana'; name='Ana'; email='ana@example.test'; city='SP'; age=25; tags=@('java','database')},
    @{id='demo-bruno'; name='Bruno'; email='bruno@example.test'; city='SP'; age=32; tags=@('spring','java')},
    @{id='demo-caio'; name='Caio'; email='caio@example.test'; city='RJ'; age=28; tags=@('database')}
)
foreach ($customer in $customers) {
    Invoke-RestMethod "$BaseUrl/customers" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes(($customer | ConvertTo-Json -Compress)))
}
Invoke-RestMethod "$BaseUrl/customers?city=SP&minimum=18&maximum=40&descending=true" | ConvertTo-Json -Depth 10
Invoke-RestMethod "$BaseUrl/customers/search?city=sp&minimum=18&maximum=40" | ConvertTo-Json -Depth 10
Invoke-RestMethod "$BaseUrl/customers/derived?city=SP&minimum=18&maximum=40" | ConvertTo-Json -Depth 10
