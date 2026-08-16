<#
.SYNOPSIS
Gera carga para a verificacao manual do servico-pix.

.DESCRIPTION
Isto NAO e uma fabrica dentro da aplicacao: e so um atalho para publicar Pix
pela API quando voce precisa de varios eventos, ou do MESMO evento repetido.
O caminho oficial sao os comandos colaveis de docs/entregas/aula-02.md; este
script existe para poupar digitacao, e depende de PowerShell (no macOS e Linux,
exige pwsh instalado).

DOIS MODOS

  -Quantidade N     publica N Pix DIFERENTES. O servidor gera um eventoId novo
                    para cada um, e o consumidor tarifa todos.

  -EventoId <x>     publica o MESMO evento -Vezes vezes. Como a identidade vem
    -Vezes N        do request, as replicas chegam ao consumidor com o mesmo
                    ce_id e sao descartadas: efeito UMA vez. E o item 5 do
                    checklist do enunciado.

O que faz um Pix ser "outro" e o eventoId, nao o idTransacaoPix: a deduplicacao
olha o eventoId, porque dois eventos diferentes podem falar do mesmo Pix.

.EXAMPLE
.\servico-pix\novo-pix.ps1 -Quantidade 12
Publica 12 Pix da emp-0001: os 10 primeiros saem FRANQUIA, os 2 ultimos FAIXA.

.EXAMPLE
.\servico-pix\novo-pix.ps1 -EventoId demo-dedup-1 -Vezes 3 -IdEmpresa emp-0002
Publica o mesmo evento 3 vezes. Esperado: UMA linha em tarifa.

.EXAMPLE
.\servico-pix\novo-pix.ps1 -IdEmpresa emp-0006 -Valor 9000 -Quantidade 4
Satura o teto da emp-0006: FAIXA, FAIXA, TETO_PARCIAL, TETO_ATINGIDO.
#>
[CmdletBinding()]
param(
    [string] $IdEmpresa = "emp-0001",
    [double] $Valor = 100.00,
    [int]    $Quantidade = 1,

    # vazio = o servidor gera a identidade; informado = repete o mesmo evento
    [string] $EventoId = "",
    [int]    $Vezes = 1,

    [string] $IdTransacaoPix = "",
    [string] $Url = "http://localhost:8080/pix/realizados"
)

$ErrorActionPreference = "Stop"

# Formatacao com InvariantCulture: nesta maquina o separador decimal pode ser a
# virgula, e "100,90" nao e JSON valido.
$inv = [System.Globalization.CultureInfo]::InvariantCulture
$valorJson = $Valor.ToString("0.00", $inv)

# Datas: nao ha nenhuma neste corpo de proposito. O liquidadoEm e carimbado pelo
# servidor, em ISO-8601. Se um dia este script passar a enviar data, ela precisa
# ser ISO-8601 e nunca epoch — e o item 4 do checklist.

$repetindo = -not [string]::IsNullOrWhiteSpace($EventoId)
$total = if ($repetindo) { $Vezes } else { $Quantidade }
$carimbo = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")

if ($repetindo) {
    Write-Host "publicando o MESMO evento ($EventoId) $total vez(es) — esperado: efeito 1x" -ForegroundColor Cyan
} else {
    Write-Host "publicando $total Pix diferentes para $IdEmpresa" -ForegroundColor Cyan
}

for ($i = 1; $i -le $total; $i++) {

    # No modo repeticao o idTransacaoPix tambem e fixo: as replicas precisam ser
    # a MESMA mensagem, nao mensagens parecidas.
    if ($repetindo) {
        $pix = if ($IdTransacaoPix) { $IdTransacaoPix } else { "pix-$EventoId" }
    } elseif ($IdTransacaoPix) {
        $pix = "$IdTransacaoPix-$i"
    } else {
        $pix = "pix-$carimbo-$i"
    }

    $corpo = [ordered]@{
        idTransacaoPix = $pix
        idEmpresa      = $IdEmpresa
        valor          = [decimal]::Parse($valorJson, $inv)
        chavePix       = "fulano@exemplo.com"
        tipoChave      = "EMAIL"
        bancoDestino   = "999"
        endToEndId     = "E999000002026$carimbo$i"
        pagadorNome    = "Empresa Ficticia"
    }
    if ($repetindo) {
        $corpo.Insert(0, "eventoId", $EventoId)
    }

    $json = $corpo | ConvertTo-Json -Compress

    try {
        # Invoke-RestMethod, e nao curl.exe: o apelido curl do PowerShell nao
        # entende -X nem -d, e curl.exe so existe no Windows. Este cmdlet e
        # nativo e funciona igual no PowerShell 5.1 e no pwsh de macOS/Linux.
        $resposta = Invoke-RestMethod -Method Post -Uri $Url `
                        -ContentType "application/json" -Body $json
        Write-Host ("  {0,2}/{1}  202  {2}  eventoId={3}" -f $i, $total, $pix, $resposta.eventoId)
    }
    catch {
        Write-Host ("  {0,2}/{1}  FALHOU: {2}" -f $i, $total, $_.Exception.Message) -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "confira o resultado com:" -ForegroundColor Yellow
Write-Host "  docker exec e02-postgres psql -U tarifacao -d tarifacao -c ""SELECT id_transacao_pix, situacao, valor FROM tarifa WHERE id_empresa='$IdEmpresa' ORDER BY id_transacao_pix;"""
