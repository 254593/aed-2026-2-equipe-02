# ADR-006 — Retenção da memória de deduplicação

## Status

Proposta parcial para incorporação ao ADR-006 do projeto final · 2026-09-10 · Amanda Bouzan (255369)

## Contexto

O consumidor registra cada `ce_id` em `evento_processado` na mesma transação que grava o efeito de
negócio. Essa memória torna a reentrega idempotente, mas crescia sem expurgo. Guardá-la para sempre
tem custo ilimitado; removê-la cedo demais permite que um evento ainda disponível no Kafka seja
aceito novamente e repita uma cobrança.

Até esta decisão, o `schema.sql` sugeria sete dias num `DELETE`, enquanto a retenção do tópico não
era declarada pelo projeto. Os prazos, portanto, não formavam uma política verificável. Além disso,
igualar os dois prazos não cria margem para diferenças entre o instante de processamento e a
expiração física de segmentos no broker.

Esta decisão trata somente do índice de deduplicação. A tabela `tarifa` é o log de negócio definido
no ADR-005 e não pode ser expurgada pela mesma rotina.

## Decisão

O tópico `pagamentos.pix.realizado.v1` declara retenção de **7 dias** (`604800000 ms`). O consumidor
guarda o `ce_id` por **30 dias** e executa o expurgo uma vez por dia, removendo somente linhas cujo
`processado_em` seja estritamente anterior ao limite.

A relação obrigatória é:

```text
retenção da deduplicação > retenção do tópico de origem
```

Os prazos são configuração, não constantes do SQL. Alterar a retenção do tópico exige revisar a
retenção da deduplicação no mesmo pull request. Trinta dias dão 23 dias de margem sobre o histórico
normal do tópico para diagnóstico e operação sem transformar a memória de `ce_id` em guarda
indefinida. O volume real da tabela deve ser acompanhado; a equipe poderá ampliar ou reduzir o
prazo mantendo a relação acima.

O expurgo é habilitado por padrão e pode ser desligado em testes. Ele usa tempo UTC e nunca executa
`DELETE` sem predicado. Eventos no instante exato do limite permanecem armazenados.

Em tópico já existente, a configuração deve ser conferida e, se necessário, aplicada
operacionalmente: criar um bean `NewTopic` não é evidência suficiente de alteração retroativa do
broker.

## Alternativas consideradas

- **Não expurgar:** recusada porque o custo de armazenamento e dos índices cresce sem limite.
- **Guardar por sete dias, igual ao tópico:** recusada porque não há margem operacional e a remoção
  física no Kafka não acontece em sincronia exata com cada linha do banco.
- **Deduplicar por `idTransacaoPix`:** recusada porque eventos diferentes podem se referir ao mesmo
  Pix; a identidade do fato continua sendo o `ce_id`.
- **Usar a tabela `tarifa` como única memória:** recusada porque nem toda decisão produz cobrança e
  porque acoplar deduplicação ao formato do log impediria a evolução independente dos dois papéis.
- **Expurgo manual:** recusada como política principal porque depende de ação humana recorrente e
  torna o crescimento silencioso. O comando manual permanece apenas como procedimento de
  contingência.

## Consequências aceitas

1. `evento_processado` deixa de crescer indefinidamente, ao custo de uma exclusão periódica no
   Postgres.
2. O índice de deduplicação ocupa até 30 dias de identificadores, prazo maior que o histórico
   normal do tópico.
3. Um replay realizado depois de 30 dias não está protegido por essa tabela e exige reconciliação
   antes de ser autorizado.
4. A retenção do tópico passa a fazer parte do contrato operacional do sistema. Ambientes antigos
   precisam ser verificados separadamente.
5. O expurgo não reduz o prazo de guarda do log de negócio e nunca remove linhas de `tarifa` ou
   `fatura_competencia`.
6. Os testes unitários provam cálculo do limite e SQL emitido, mas a execução com Kafka e Postgres
   reais continua sendo uma verificação de integração a cargo do ambiente completo da equipe.
