<#
.SYNOPSIS
Publica Pix na API do servico-pix e confere o efeito no servico-tarifacao.

.DESCRIPTION
Equivalente em PowerShell do publicar-pix.sh — mesmos parametros, mesma saida.

Pre-requisitos: docker compose up -d, e os dois servicos rodando.
Ver README.md, "Como subir o projeto numa maquina limpa".

.EXAMPLE
.\scripts\publicar-pix.ps1
Publica um Pix com os padroes (emp-0001, R$ 250,00).

.EXAMPLE
.\scripts\publicar-pix.ps1 -Empresa emp-0002 -Valor 750.00

.EXAMPLE
.\scripts\publicar-pix.ps1 -Quantidade 11
Onze Pix seguidos: mostra a franquia acabando no 11o.

.EXAMPLE
.\scripts\publicar-pix.ps1 -Roteiro
Executa as cinco saidas da politica de tarifacao, e termina pela idempotencia.

.EXAMPLE
.\scripts\publicar-pix.ps1 -Idempotencia
Entrega o MESMO evento tres vezes e prova que o efeito acontece uma vez so.

.EXAMPLE
.\scripts\publicar-pix.ps1 -Idempotencia -EventoId "33333333-3333-3333-3333-333333333333"
Repete um eventoId de proposito, para demonstrar o descarte.

.EXAMPLE
.\scripts\publicar-pix.ps1 -Conferir
So mostra a tabela tarifa, sem publicar nada.
#>
[CmdletBinding()]
param(
    [string] $Empresa    = "emp-0001",
    [double] $Valor      = 250.00,
    [string] $Transacao  = "",
    [int]    $Quantidade = 1,
    [switch] $Roteiro,
    [switch] $Idempotencia,
    [switch] $Conferir,

    # Sorteado por padrao. Informe um valor para REPETIR um evento de proposito
    # e ver o descarte acontecer.
    [string] $EventoId = "11111111-1111-1111-1111-111111111111",
    [int]    $Entregas = 3,

    [string] $Api           = "http://localhost:8080/pix/realizados",
    [string] $ContainerDb   = "e02-postgres",
    [string] $ContainerKafka = "e02-kafka",
    [string] $Topico        = "pagamentos.pix.realizado.v1",
    [string] $DbUser        = "tarifacao",
    [string] $DbName        = "tarifacao"
)

$ErrorActionPreference = "Stop"

# Nesta maquina o separador decimal pode ser virgula, e "250,00" nao e JSON
# valido. Toda formatacao numerica usa InvariantCulture.
$inv = [System.Globalization.CultureInfo]::InvariantCulture

function Write-Titulo($texto) { Write-Host "`n$texto" -ForegroundColor White }
function Write-Ok($texto)     { Write-Host $texto -ForegroundColor Green }
function Write-Falha($texto)  { Write-Host $texto -ForegroundColor Red }

function Test-Api {
    try {
        Invoke-RestMethod -Uri $Api -Method Post -TimeoutSec 3 `
            -ContentType "application/json" -Body "{}" | Out-Null
    }
    catch {
        # 400 e a resposta esperada para corpo vazio: a API esta no ar.
        if ($_.Exception.Response -and
            $_.Exception.Response.StatusCode.value__ -eq 400) { return }

        Write-Falha "A API nao respondeu em $Api"
        Write-Host  "  Suba o publicador:  mvn -f servico-pix/pom.xml spring-boot:run"
        exit 1
    }
}

function Publicar {
    param([string] $IdTransacaoPix, [string] $IdEmpresa, [double] $ValorDoPix)

    # O eventoId NAO vai no corpo: quem o sorteia e o servico-pix, porque a
    # identidade do fato nasce com o fato. O corpo e um RealizacaoPixVO (o
    # pedido); a resposta e um PixRealizadoEvent (o fato).
    $corpo = @{
        idTransacaoPix = $IdTransacaoPix
        idEmpresa      = $IdEmpresa
        valor          = $ValorDoPix
        chavePix       = "fulano@exemplo.com"
        tipoChave      = "EMAIL"
        bancoDestino   = "999"
        endToEndId     = "E999000002026081413" + (Get-Random -Minimum 10000000000 -Maximum 99999999999)
        pagadorNome    = "Empresa Ficticia"
    } | ConvertTo-Json -Compress

    $rotulo = "  {0}  {1}  R$ {2}" -f $IdTransacaoPix, $IdEmpresa, $ValorDoPix.ToString("0.00", $inv)

    try {
        Invoke-RestMethod -Uri $Api -Method Post -ContentType "application/json" `
            -Body $corpo | Out-Null
        Write-Ok "  202$rotulo"
    }
    catch {
        $codigo = "erro"
        if ($_.Exception.Response) { $codigo = $_.Exception.Response.StatusCode.value__ }
        Write-Falha "  $codigo$rotulo"
    }
}

function Invoke-Conferir {
    Write-Titulo "Tabela tarifa"
    docker exec $ContainerDb psql -U $DbUser -d $DbName -c @"
SELECT id_empresa, id_transacao_pix, competencia, situacao, valor
  FROM tarifa ORDER BY id_empresa, liquidado_em;
"@

    Write-Titulo "Resumo por motivo"
    docker exec $ContainerDb psql -U $DbUser -d $DbName -c @"
SELECT situacao, count(*) AS pix, sum(valor) AS total
  FROM tarifa GROUP BY situacao ORDER BY situacao;
"@
}

# A demonstracao do B.3: o MESMO evento, entregue N vezes, produz efeito UMA vez.
#
# Nao da para fazer isto pela API HTTP, e a razao e de modelagem, nao limitacao:
# o corpo do POST e um RealizacaoPixVO - o PEDIDO -, e quem cria o FATO,
# sorteando o eventoId, e o PixService. Cada chamada e um fato novo. Para
# reentregar o mesmo evento e preciso publicar direto no topico, com o ce_id
# fixo, que e o que o consumidor usa como chave de deduplicacao.
function Invoke-Idempotencia {
    $carga = Join-Path $PSScriptRoot "eventos\pix-reentrega.json"
    if (-not (Test-Path $carga)) {
        Write-Falha "carga nao encontrada: $carga"; exit 1
    }

    docker inspect $ContainerKafka 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Falha "container $ContainerKafka nao esta no ar - rode: docker compose up -d"
        exit 1
    }

    # Le o JSON, troca o eventoId e compacta numa linha so, que e o que o
    # console-producer espera.
    $evento = Get-Content $carga -Raw | ConvertFrom-Json
    $evento.eventoId = $EventoId
    $corpo = $evento | ConvertTo-Json -Compress

    Write-Titulo "Idempotencia - o MESMO evento entregue $Entregas vezes"
    Write-Host "  ce_id / eventoId : $EventoId"
    Write-Host "  topico           : $Topico"
    Write-Host "  chave de particao: $($evento.idEmpresa)  (idEmpresa)"

    $cabecalhos = "ce_specversion=1.0;ce_id=$EventoId;ce_source=/pagamentos/servico-pix;" +
                  "ce_type=$Topico;ce_time=2026-08-14T13:00:00.000Z"
    $linha = "$cabecalhos#$($evento.idEmpresa)~$corpo"

    1..$Entregas | ForEach-Object {
        $linha | docker exec -i $ContainerKafka /opt/kafka/bin/kafka-console-producer.sh `
            --bootstrap-server kafka:9094 --topic $Topico `
            --property parse.headers=true --property headers.delimiter='#' `
            --property headers.separator=';' --property headers.key.separator='=' `
            --property parse.key=true --property key.separator='~' 2>$null
        Write-Ok "  entrega $_ publicada"
    }

    Write-Host "`nAguardando o consumidor processar..."
    Start-Sleep -Seconds 5

    Write-Titulo "Efeito no banco - deve haver UMA linha"
    docker exec $ContainerDb psql -U $DbUser -d $DbName -c @"
SELECT evento_id, id_transacao_pix, situacao, valor
  FROM tarifa WHERE evento_id = '$EventoId';
"@

    $linhas = (docker exec $ContainerDb psql -U $DbUser -d $DbName -tAc `
        "SELECT count(*) FROM tarifa WHERE evento_id = '$EventoId';").Trim()

    if ($linhas -eq "1") {
        Write-Ok "  OK: $Entregas entregas, 1 efeito. At-least-once com consumidor idempotente."
    }
    else {
        Write-Falha "  FALHOU: esperava 1 linha, encontrou $linhas"
        exit 1
    }

    Write-Host ""
    Write-Host "  No log do consumidor aparecem $($Entregas - 1) descartes:"
    Write-Host "    'evento $EventoId JA PROCESSADO, descartando em silencio'"
    Write-Host ""
    Write-Host "  Rodando de novo, o efeito continua sendo 1 - o evento ja esta na tabela"
    Write-Host "  evento_processado. Para um fato novo, use -EventoId com outro valor."
}

function Invoke-Roteiro {
    Test-Api

    Write-Titulo "1. Franquia - emp-0001 tem 10 Pix isentos por mes"
    1..11 | ForEach-Object { Publicar "pix-fr-$_" "emp-0001" 250.00 }
    Write-Host "  esperado: 10 FRANQUIA (0,00) e o 11o FAIXA (0,50)"

    Write-Titulo "2. Faixa por valor - o valor do Pix escolhe a tarifa"
    Publicar "pix-vl-1" "emp-0001"   100.00
    Publicar "pix-vl-2" "emp-0001"   700.00
    Publicar "pix-vl-3" "emp-0001"  3000.00
    Publicar "pix-vl-4" "emp-0001" 90000.00
    Write-Host "  esperado: 0,50 / 1,00 / 5,00 / 10,00"

    Write-Titulo "3. Fronteira EXCLUSIVA - R$ 500,00 paga a faixa de cima"
    Publicar "pix-fx-1" "emp-0001" 499.99
    Publicar "pix-fx-2" "emp-0001" 500.00
    Write-Host "  esperado: 0,50 e 1,00 - nao 0,50 e 0,50"

    Write-Titulo "4. Teto mensal - emp-0006 tem teto de R$ 25,00"
    1..4 | ForEach-Object { Publicar "pix-tt-$_" "emp-0006" 250.00 }
    Write-Host "  esperado: 10,00 / 10,00 / TETO_PARCIAL 5,00 / TETO_ATINGIDO 0,00"
    Write-Host "  o acumulado para EXATAMENTE em 25,00"

    Write-Titulo "5. Sem contrato - emp-9999 nao tem oferta vigente"
    Publicar "pix-sc-1" "emp-9999" 250.00
    Write-Host "  esperado: SEM_CONTRATO, 0,00 - nao ha plano padrao (ver ADR-002)"

    Write-Host "`nAguardando o consumidor processar..."
    Start-Sleep -Seconds 5
    Invoke-Conferir

    Write-Titulo "6. Idempotencia - o MESMO evento tres vezes"
    Invoke-Idempotencia
}

if ($Conferir)     { Invoke-Conferir;     exit 0 }
if ($Idempotencia) { Invoke-Idempotencia; exit 0 }
if ($Roteiro)      { Invoke-Roteiro;      exit 0 }

Test-Api
Write-Titulo "Publicando $Quantidade Pix"
1..$Quantidade | ForEach-Object {
    if ($Transacao) { Publicar $Transacao $Empresa $Valor }
    else            { Publicar ("pix-" + (Get-Date -Format "HHmmss") + "-$_") $Empresa $Valor }
}

Write-Host "`nAguardando o consumidor processar..."
Start-Sleep -Seconds 3
Invoke-Conferir
