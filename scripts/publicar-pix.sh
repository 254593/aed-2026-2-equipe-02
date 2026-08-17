#!/usr/bin/env bash
#
# Publica Pix na API do servico-pix e confere o efeito no servico-tarifacao.
#
# Equivalente em PowerShell: publicar-pix.ps1 (mesmos parametros, mesma saida).
#
#   ./scripts/publicar-pix.sh                          um Pix com os padroes
#   ./scripts/publicar-pix.sh -e emp-0002 -v 750.00    parametrizado
#   ./scripts/publicar-pix.sh -n 11                    onze Pix seguidos
#   ./scripts/publicar-pix.sh --roteiro                as cinco saidas da politica
#   ./scripts/publicar-pix.sh --idempotencia           o MESMO evento 3x -> efeito 1x
#   ./scripts/publicar-pix.sh --conferir               so mostra a tabela tarifa
#
# Pre-requisitos: docker compose up -d, e os dois servicos rodando.
# Ver README.md, "Como subir o projeto numa maquina limpa".

set -euo pipefail

API="${API:-http://localhost:8080/pix/realizados}"
CONTAINER_DB="${CONTAINER_DB:-e02-postgres}"
DB_USER="${DB_USER:-tarifacao}"
DB_NAME="${DB_NAME:-tarifacao}"

CONTAINER_KAFKA="${CONTAINER_KAFKA:-e02-kafka}"
TOPICO="${TOPICO:-pagamentos.pix.realizado.v1}"
ORIGEM="/pagamentos/servico-pix"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

EMPRESA="emp-0001"
VALOR="250.00"
TRANSACAO=""
QUANTIDADE=1
ENTREGAS=3

# Sorteado por padrao. Informe um valor para REPETIR um evento de proposito e
# ver o descarte acontecer.
EVENTO_ID=""

# No Git Bash, o caminho /opt/... dentro do container e convertido para caminho
# do Windows e o docker exec falha. MSYS_NO_PATHCONV desliga essa conversao.
export MSYS_NO_PATHCONV=1

vermelho() { printf '\033[31m%s\033[0m\n' "$1"; }
verde()    { printf '\033[32m%s\033[0m\n' "$1"; }
titulo()   { printf '\n\033[1m%s\033[0m\n' "$1"; }

uso() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

exigir_api() {
  if ! curl -s -o /dev/null --max-time 3 "$API" -X POST \
        -H 'Content-Type: application/json' -d '{}' 2>/dev/null; then
    vermelho "A API nao respondeu em $API"
    echo "  Suba o publicador:  mvn -f servico-pix/pom.xml spring-boot:run"
    exit 1
  fi
}

# publicar <idTransacaoPix> <idEmpresa> <valor>
publicar() {
  local transacao="$1" empresa="$2" valor="$3" codigo

  # O eventoId NAO vai no corpo: quem o sorteia e o servico-pix, porque a
  # identidade do fato nasce com o fato. O corpo e um RealizacaoPixVO (o
  # pedido); a resposta e um PixRealizadoEvent (o fato).
  codigo=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API" \
    -H 'Content-Type: application/json' \
    -d "{
          \"idTransacaoPix\": \"$transacao\",
          \"idEmpresa\":      \"$empresa\",
          \"valor\":           $valor,
          \"chavePix\":       \"fulano@exemplo.com\",
          \"tipoChave\":      \"EMAIL\",
          \"bancoDestino\":   \"999\",
          \"endToEndId\":     \"E999000002026081413$(printf '%011d' $((RANDOM * RANDOM % 100000000000)))\",
          \"pagadorNome\":    \"Empresa Ficticia\"
        }")

  if [ "$codigo" = "202" ]; then
    verde "  202  $transacao  $empresa  R\$ $valor"
  else
    vermelho "  $codigo  $transacao  $empresa  R\$ $valor"
  fi
}

conferir() {
  titulo "Tabela tarifa"
  docker exec "$CONTAINER_DB" psql -U "$DB_USER" -d "$DB_NAME" -c \
    "SELECT id_empresa, id_transacao_pix, competencia, situacao, valor
       FROM tarifa ORDER BY id_empresa, liquidado_em;"

  titulo "Resumo por motivo"
  docker exec "$CONTAINER_DB" psql -U "$DB_USER" -d "$DB_NAME" -c \
    "SELECT situacao, count(*) AS pix, sum(valor) AS total
       FROM tarifa GROUP BY situacao ORDER BY situacao;"
}

# A demonstracao do B.3: o MESMO evento, entregue N vezes, produz efeito UMA vez.
#
# Nao da para fazer isto pela API HTTP, e a razao e de modelagem, nao limitacao:
# o corpo do POST e um RealizacaoPixVO — o PEDIDO —, e quem cria o FATO, sorteando
# o eventoId, e o PixService. Cada chamada e um fato novo. Para reentregar o mesmo
# evento e preciso publicar direto no topico, com o ce_id fixo, que e o que o
# consumidor usa como chave de deduplicacao.
idempotencia() {
  local evento_id="${EVENTO_ID:-11111111-1111-1111-1111-111111111111}"
  local carga="$AQUI/eventos/pix-reentrega.json"

  if [ ! -f "$carga" ]; then
    vermelho "carga nao encontrada: $carga"; exit 1
  fi

  # jq nao e pre-requisito da disciplina: troca o eventoId com sed e compacta o
  # JSON numa linha so, que e o que o console-producer espera.
  local corpo
  corpo=$(sed "s/\"eventoId\":  *\"[^\"]*\"/\"eventoId\": \"$evento_id\"/" "$carga" \
          | tr -d '\n' | tr -s ' ')

  # A empresa vem da carga, nao de um literal — e o que o publicar-pix.ps1 ja
  # faz. Literal aqui sairia do lugar no dia em que a carga mudasse, e a
  # mensagem iria para a particao de uma empresa e o corpo para outra.
  local empresa
  empresa=$(sed -n 's/.*"idEmpresa": *"\([^"]*\)".*/\1/p' "$carga")

  titulo "Idempotencia — o MESMO evento entregue $ENTREGAS vezes"
  echo "  ce_id / eventoId : $evento_id"
  echo "  topico           : $TOPICO"
  echo "  chave de particao: $empresa  (idEmpresa)"

  local antes
  antes=$(docker exec "$CONTAINER_KAFKA" true 2>/dev/null && echo ok || echo falhou)
  if [ "$antes" = "falhou" ]; then
    vermelho "container $CONTAINER_KAFKA nao esta no ar — rode: docker compose up -d"; exit 1
  fi

  local cabecalhos="ce_specversion=1.0;ce_id=$evento_id;ce_source=$ORIGEM;ce_type=$TOPICO;ce_time=2026-08-14T13:00:00.000Z"

  for i in $(seq 1 "$ENTREGAS"); do
    printf '%s#%s~%s\n' "$cabecalhos" "$empresa" "$corpo" \
      | docker exec -i "$CONTAINER_KAFKA" /opt/kafka/bin/kafka-console-producer.sh \
          --bootstrap-server kafka:9094 --topic "$TOPICO" \
          --property parse.headers=true --property headers.delimiter='#' \
          --property headers.separator=';' --property headers.key.separator='=' \
          --property parse.key=true --property key.separator='~' 2>/dev/null
    verde "  entrega $i publicada"
  done

  echo
  echo "Aguardando o consumidor processar..."
  sleep 5

  titulo "Efeito no banco — deve haver UMA linha"
  docker exec "$CONTAINER_DB" psql -U "$DB_USER" -d "$DB_NAME" -c \
    "SELECT evento_id, id_transacao_pix, situacao, valor
       FROM tarifa WHERE evento_id = '$evento_id';"

  local linhas
  linhas=$(docker exec "$CONTAINER_DB" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "SELECT count(*) FROM tarifa WHERE evento_id = '$evento_id';" | tr -d '[:space:]')

  if [ "$linhas" = "1" ]; then
    verde "  OK: $ENTREGAS entregas, 1 efeito. At-least-once com consumidor idempotente."
  else
    vermelho "  FALHOU: esperava 1 linha, encontrou $linhas"
    exit 1
  fi

  echo
  echo "  No log do consumidor aparecem $((ENTREGAS - 1)) descartes:"
  echo "    'evento $evento_id JA PROCESSADO, descartando em silencio'"
  echo
  echo "  Rodando de novo, o efeito continua sendo 1 — o evento ja esta na tabela"
  echo "  evento_processado. Para um fato novo, use --evento-id com outro valor."
}

roteiro() {
  exigir_api

  titulo "1. Franquia — emp-0001 tem 10 Pix isentos por mes"
  for i in $(seq 1 11); do publicar "pix-fr-$i" emp-0001 250.00; done
  echo "  esperado: 10 FRANQUIA (0,00) e o 11o FAIXA (0,50)"

  titulo "2. Faixa por valor — o valor do Pix escolhe a tarifa"
  publicar pix-vl-1 emp-0001 100.00
  publicar pix-vl-2 emp-0001 700.00
  publicar pix-vl-3 emp-0001 3000.00
  publicar pix-vl-4 emp-0001 90000.00
  echo "  esperado: 0,50 / 1,00 / 5,00 / 10,00"

  titulo "3. Fronteira EXCLUSIVA — R\$ 500,00 paga a faixa de cima"
  publicar pix-fx-1 emp-0001 499.99
  publicar pix-fx-2 emp-0001 500.00
  echo "  esperado: 0,50 e 1,00 — nao 0,50 e 0,50"

  titulo "4. Teto mensal — emp-0006 tem teto de R\$ 25,00"
  for i in 1 2 3 4; do publicar "pix-tt-$i" emp-0006 250.00; done
  echo "  esperado: 10,00 / 10,00 / TETO_PARCIAL 5,00 / TETO_ATINGIDO 0,00"
  echo "  o acumulado para EXATAMENTE em 25,00"

  titulo "5. Sem contrato — emp-9999 nao tem oferta vigente"
  publicar pix-sc-1 emp-9999 250.00
  echo "  esperado: SEM_CONTRATO, 0,00 — nao ha plano padrao (ver ADR-002)"

  echo
  echo "Aguardando o consumidor processar..."
  sleep 5
  conferir

  titulo "6. Idempotencia — o MESMO evento tres vezes"
  idempotencia
}

while [ $# -gt 0 ]; do
  case "$1" in
    -e|--empresa)   EMPRESA="$2"; shift 2 ;;
    -v|--valor)     VALOR="$2"; shift 2 ;;
    -p|--transacao) TRANSACAO="$2"; shift 2 ;;
    -n|--quantidade) QUANTIDADE="$2"; shift 2 ;;
    --evento-id)    EVENTO_ID="$2"; shift 2 ;;
    --entregas)     ENTREGAS="$2"; shift 2 ;;
    --roteiro)      roteiro; exit 0 ;;
    --idempotencia) idempotencia; exit 0 ;;
    --conferir)     conferir; exit 0 ;;
    -h|--help)      uso ;;
    *) vermelho "opcao desconhecida: $1"; uso ;;
  esac
done

exigir_api
titulo "Publicando $QUANTIDADE Pix"
for i in $(seq 1 "$QUANTIDADE"); do
  if [ -n "$TRANSACAO" ]; then
    publicar "$TRANSACAO" "$EMPRESA" "$VALOR"
  else
    publicar "pix-$(date +%H%M%S)-$i" "$EMPRESA" "$VALOR"
  fi
done

echo
echo "Aguardando o consumidor processar..."
sleep 3
conferir
