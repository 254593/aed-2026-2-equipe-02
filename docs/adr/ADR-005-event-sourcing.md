# ADR-005 — Event Sourcing no ciclo de tarifação

## Status

Aceita · 2026-09-06 · Equipe 02

Depende do [ADR-003](ADR-003-chave-de-particao.md): a numeração de versão por stream só é correta
enquanto a chave de partição serializar o processamento por empresa.

## Contexto

O serviço de tarifação decide cada Pix contra o acumulado da competência, e não contra o payload que
chegou. [`docs/regra-de-tarifacao.md`](../regra-de-tarifacao.md) enuncia duas invariantes:

```
unidadesFranquiaConsumidas  <=  franquia do plano
valorTarifadoNaCompetencia  <=  teto do plano
```

Nenhuma delas é expressável sobre um Pix isolado: a violação só existe em relação ao acumulado da
competência. O candidato intuitivo a agregado é o `PixTarifado`, que tem ciclo de vida próprio — o
ADR-002 nomeia `estorno_solicitado`, `estorno_confirmado`, `estornada` e `nao_tarifavel`, e a chamada
`estornar` ao Parceiro de Lançamentos é por lançamento. Promovê-lo poria as duas invariantes **entre**
agregados, o que exigiria reserva de unidades de franquia com compensação — mecanismo que o ADR-003
revogou por destruir *quais* Pix receberam isenção, que é o que determina o valor da fatura.

O código já calcula o estado como *fold* do histórico, sem chamar assim: `contarFranquiaConsumida()`
é um `COUNT` e `totalTarifadoNaCompetencia()` é um `SUM`, ambos sobre a tabela `tarifa`. Não há
contador mutável no serviço; o javadoc do repositório registra que a razão foi idempotência —
*"contar linhas é naturalmente idempotente, somar +1 não"*. A coluna `situacao` já discrimina o tipo
do fato em cinco valores.

O domínio exige cinco coisas de forma irredutível: decidir sobre o acumulado; reproduzir o total anos
depois, para auditoria, contestação comercial e exposição do CDC; tolerar entrega *at-least-once* com
fold idempotente; decidir contra a oferta da competência; e acrescentar fatos na exceção, nunca
remover. As cinco já estão atendidas — tabela append-only, fold por `COUNT`/`SUM`, deduplicação por
`ce_id`, oferta buscada por competência, e `CHECK (valor >= 0)` impedindo o acumulado de retroceder.

Das três propriedades que definem um event store, portanto, duas faltam:

| Propriedade | Situação |
|---|---|
| Append-only | atendida — só `INSERT`; os dois `DELETE` do repositório são auxiliares de teste |
| Ordem por stream | ausente — a ordem é implícita em `liquidado_em` |
| Versão por stream | ausente — não há detecção de escrita concorrente |

As forças estão em tensão. Nenhuma leitura de hoje deixa de ser respondida sem a mudança, e o
sistema tem um único escritor por empresa por construção. Ao mesmo tempo, a compensação está no
roteiro do projeto, e ela põe um segundo escritor no mesmo stream: um orquestrador emitindo estornos
enquanto Pix novos chegam pela partição.

## Decisão

**Nós adotamos o `CicloDeTarifacao`, um por `(idEmpresa, competência)`, como o agregado event sourced
deste sistema.** A fronteira é a competência, escopo das duas invariantes do contexto.

**Nós formalizamos a `tarifa` como event store desse agregado**, com uma coluna e uma constraint:

```sql
ALTER TABLE tarifa ADD COLUMN versao BIGINT NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_tarifa_stream_versao
    ON tarifa (id_empresa, competencia, versao);
```

Índice único em vez de `ADD CONSTRAINT` porque o `schema.sql` roda a cada subida e precisa ser
idempotente; a garantia é a mesma.

Não há coluna `stream_id`: `(id_empresa, competencia)` é a identidade do stream.

**A versão é atribuída no `TarifacaoRepository`, dentro da transação que já existe**, imediatamente
antes do `INSERT` e depois do registro de deduplicação:

```sql
SELECT COALESCE(MAX(versao), 0) + 1 FROM tarifa WHERE id_empresa = ? AND competencia = ?
```

**Na violação da `UNIQUE`, a transação inteira é desfeita e a mensagem não é confirmada.** O offset
não avança, o Kafka reentrega, e a reentrega encontra um de dois estados: a gravação concorrente
comitou, e a deduplicação por `ce_id` descarta em silêncio; ou não comitou, e a versão é recalculada.
Nenhum tratamento especial é escrito para esse caso — ele cai no comportamento *at-least-once* que o
serviço já tem.

**A projeção `fatura_competencia` é atualizada por um projetor assíncrono, fora da transação de
decisão.** Uma linha por `(idEmpresa, competência)`, com total tarifado, quantidade de Pix, contagem
por situação e `versao_projetada` — a última versão incorporada.

**O projetor avança por versão, não por tempo.** Para cada stream com fatos pendentes, lê
`WHERE id_empresa = ? AND competencia = ? AND versao > versao_projetada ORDER BY versao`, aplica o
fold sobre a linha existente e grava a nova `versao_projetada` na mesma transação da projeção. O
avanço é idempotente: reexecutar sem fatos novos não altera nada.

**A reconstrução é `DELETE FROM fatura_competencia` seguido do mesmo projetor, partindo da versão
zero.** É o mesmo código do avanço incremental, sem caminho especial de *rebuild* — um caminho
separado poderia divergir do incremental sem que nada acusasse.

**O replay ordena por `(id_empresa, competencia, versao)`, nunca por `liquidado_em`.**

**`versao` e projeção são antecipação, e o gatilho que as converte em necessidade é a compensação**,
que põe um segundo escritor no mesmo stream.

**A compensação não entra nesta decisão.** Este ADR não vale para outros agregados: `oferta` e
`oferta_faixa` seguem tabelas comuns, `evento_processado` segue um índice de deduplicação.

## Alternativas consideradas

- **Não formalizar nada** — recusada por custo assimétrico: a mesma mudança custa muito mais depois
  da compensação, que já está no roteiro.
- **Tabela `evento_ciclo` separada** — recusada por criar duas fontes da verdade sobre o mesmo fato,
  sem constraint que impeça a divergência. Não é questão de *dual write*: as duas escritas
  compartilhariam a transação.
- **O tópico Kafka como event store** — recusada por faltar versão por stream e leitura por agregado,
  e por ele carregar dado pessoal que a `tarifa` não carrega.
- **Um event store dedicado** — recusada por proporção: um sistema com operação e backup próprios
  para substituir uma coluna e uma constraint.
- **Projeção síncrona, na transação da decisão** — recusada por acoplar a leitura à escrita e
  eliminar a fronteira que torna a projeção descartável.

## Consequências aceitas

**1 ·** Uma leitura a mais por evento. `SELECT MAX(versao)+1` no stream, dentro da transação: o custo
passa de cinco ou seis para seis ou sete idas ao Postgres, somando-se à dívida de dimensionamento do
ADR-003.

**2 ·** A versão é `read-then-write`, e quem a protege é a chave de partição. A `UNIQUE` é rede de
segurança, não mecanismo: transforma corrupção silenciosa em erro visível.

**3 ·** Ordenar o replay por `versao` tem efeito colateral bem-vindo: `liquidado_em` é gravado no
fuso default da JVM numa coluna sem *time zone*, e ordenar por ele reproduziria o defeito dentro do
replay. O defeito continua como dívida — a coluna deveria ser `TIMESTAMPTZ`.

**4 ·** A projeção pode ser tratada como cache: bug no projetor se corrige no código e se reconstrói,
sem migração de dados.

**5 ·** O event store nasce sem dado pessoal, por consequência de uma decisão anterior: o
`PixRealizadoEvent` do serviço declara cinco campos e descarta `chavePix`, `pagadorNome` e os demais
na desserialização; a `tarifa` não tem uma coluna sequer. Isso evita a colisão entre "o log é
imutável" e o direito de eliminação do titular. **Passa a valer a regra:** nenhum dado pessoal entra
na `tarifa`; se um dia for necessário, entra cifrado por titular, e o que se elimina é a chave.

**6 ·** O esquema envelhece. Acrescentar um valor de `situacao` é compatível; renomear ou remover não
é, e a saída é traduzir na leitura, não migrar o passado. Nunca renomear um valor já gravado.

**7 ·** O log não pode ser expurgado — `evento_processado` tem política de expurgo porque é índice, a
`tarifa` não admite a mesma. A saída é arquivamento frio de competências encerradas, preso ao prazo
de guarda fiscal, e **não está implementado**.

**8 ·** Sem snapshot, de propósito: o stream fecha no fim do mês. Revisar quando o replay de um ciclo
passar de algumas centenas de milissegundos. Se houver snapshot um dia, apagar todos tem de
continuar seguro.

**9 ·** Consistência eventual entre a decisão e a fatura, administrada por tela: o fechamento suporta
minutos, porque o ciclo é mensal; a consulta self-service suporta segundos e precisa dizer na
interface que o número se move; a auditoria não lê a projeção, lê o log. Acelerar o projetor reduz a
janela e não a elimina.

**10 ·** Esta fronteira precisa ser reaberta se o negócio permitir **troca de plano no meio da
competência**: um ciclo passaria a ter duas ofertas e a franquia teria de ser rateada. Enquanto a
vigência for mensal, a troca cai entre ciclos.
