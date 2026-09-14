# ADR-006 - Resiliencia e compensacao

## Status

Aceita - 2026-09-14 - Equipe 02

## Contexto

O servico recebe fatos de Pix liquidados, decide a tarifa por empresa e competencia e grava a
linha em `tarifa`. Falhas de banco, carga invalida e eventos duplicados precisam ter destinos
diferentes. Alem disso, uma tarifa ja registrada pode precisar de ajuste quando uma validacao
posterior recusar o lancamento. O fato original nao pode ser apagado nem alterado.

## Decisao

Usaremos retry bloqueante com cinco tentativas e backoff exponencial de 1s, 2s, 4s, 8s e 16s.
Falhas transitorias, como indisponibilidade do banco, podem ser tentadas novamente. Falhas
permanentes, como JSON invalido, vao direto para `pagamentos.pix.realizado.v1.dlq`.

A DLQ preserva os cabecalhos CloudEvents originais e adiciona os cabecalhos `kafka_dlt-*` com o
motivo, topico, particao e offset. O operador consulta a causa, corrige o problema e republica
o corpo e os cabecalhos no topico original. A deduplicacao por `ce_id` torna a reinjecao segura.

A compensacao usa coreografia: o servico que detecta a recusa publica `PixEstornado` em
`pagamentos.pix.estornado.v1`, e o servico de tarifacao consome o fato. O consumidor procura o
Pix original, grava uma linha append-only em `estorno` e a projecao recalcula o liquido da fatura.
A tarifa original permanece intacta.

## Alternativas consideradas

- Retry infinito: recusado porque bloqueia a particao e nunca libera a mensagem permanente.
- Tentar novamente por topicos de retry: recusado porque reintroduz eventos fora da ordem da
  empresa e muda a alocacao da franquia.
- Orquestracao central: recusada nesta versao porque criaria um coordenador acoplado ao estado
  interno do consumidor; a coreografia deixa o fato disponivel a qualquer interessado.
- UPDATE ou DELETE na tarifa: recusado porque elimina o historico e pode reabrir o teto mensal.
- Rollback sincrono por REST: recusado porque cria dependencia temporal entre servicos; o estorno
  e um fato assincrono que tambem pode falhar e ir para DLQ.

## Consequencias aceitas

Ganhamos limite de retentativa, visibilidade operacional, reprocessamento manual e fatura com
ajuste observavel. A deduplicacao permite reentregar um evento sem duplicar o efeito.

Aceitamos head-of-line blocking durante os 31 segundos de backoff total, a necessidade de operar
a DLQ e uma janela de consistencia eventual entre `estorno` e `fatura_competencia`. Se a propria
compensacao falhar, ela segue a mesma politica: retry limitado, DLQ do topico de estorno e
reprocessamento manual depois da correcao. O teto nao reabre: o estorno reduz o liquido, mas nao
altera as decisoes de tarifacao que ja foram tomadas.
