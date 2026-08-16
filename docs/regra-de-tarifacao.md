# Regra de tarifação de Pix PJ

Especificação da oferta comercial e do algoritmo de decisão implementado pelo serviço de tarifação.
A ADR-002 registra por que este domínio foi escolhido; este documento registra **como a regra
funciona**.

## Oferta comercial — Plano PJ (exemplo)

**Franquia mensal:** 10 Pix isentos por competência.

**Tabela de faixas**, aplicada a partir do 11º Pix da competência:

| Faixa | Tarifa |
|---|---|
| valor < R$ 500,00 | R$ 0,50 |
| R$ 500,00 ≤ valor < R$ 1.000,00 | R$ 1,00 |
| R$ 1.000,00 ≤ valor < R$ 5.000,00 | R$ 5,00 |
| valor ≥ R$ 5.000,00 | R$ 10,00 |

Limite inferior inclusivo, superior exclusivo. Não há intervalo sem faixa, e nenhum valor pertence a
duas.

**Teto mensal:** R$ 2.000,00 em tarifas por competência.

## Competência

A competência é o **mês de `liquidadoEm`**, nunca a data de processamento. Um Pix liquidado em 31/07
e processado em 01/08 pertence a julho. A consolidação da fatura usa `(idEmpresa, mês de
liquidadoEm)` como chave.

Consequência: um Pix que chegue depois do fechamento da competência exige ajuste retroativo na
fatura já emitida, e não pode ser jogado no mês corrente.

## Ordem de avaliação

```
1. unidadesFranquiaConsumidas < 10 ?
       sim -> ISENTA  (motivo: FRANQUIA)
               unidadesFranquiaConsumidas += 1

2. tarifa = faixa(valor do Pix)

3. valorTarifadoNaCompetencia >= 2.000,00 ?
       sim -> NAO COBRA  (motivo: TETO_ATINGIDO)

4. valorTarifadoNaCompetencia + tarifa > 2.000,00 ?
       sim -> COBRA PARCIAL: 2.000,00 - valorTarifadoNaCompetencia
               (motivo: TETO_PARCIAL)
       nao -> COBRA a tarifa integral  (motivo: FAIXA)

   valorTarifadoNaCompetencia += valor cobrado
```

**A franquia é sempre consumida primeiro.** Uma empresa de alto volume termina o mês com 10 de 10
isenções usadas, independentemente do teto. Isso mantém o relatório de fechamento fiel.

**O estouro do teto é cobrado parcialmente**, até completar exatamente R$ 2.000,00. A alternativa —
não cobrar nada e marcar o teto como atingido — exigiria um sinalizador de estado separado do
acumulador, que precisaria ser ressincronizado a cada estorno. Com a cobrança parcial, "teto
atingido" é derivável de `valorTarifadoNaCompetencia >= 2.000,00`, e o estado se recompõe sozinho
quando uma compensação devolve espaço.

Na fatura, a linha aparece como tarifa limitada pelo teto contratual.

## Motivo da decisão

Toda tarifa persiste o **motivo** que a produziu, junto com o valor:

| Motivo | Significado |
|---|---|
| `FRANQUIA` | isento por haver unidade de franquia disponível |
| `FAIXA` | tarifado pelo valor integral da faixa |
| `TETO_PARCIAL` | tarifado por valor reduzido, até completar o teto |
| `TETO_ATINGIDO` | não cobrado, teto da competência já completo |

O motivo não é log: é dado do domínio. É ele que identifica **qual cobrança bateu o teto** — a única
linha `TETO_PARCIAL` da competência —, permite responder "por que esta empresa pagou este valor" sem
recalcular nada, e distingue no fechamento os Pix gratuitos por benefício contratual dos gratuitos
por limite atingido. São duas gratuidades com significados comerciais opostos.

## Estado acumulado — `CicloDeTarifacao`

Um por `(idEmpresa, competência)`. Os acumuladores se dividem em dois grupos com papéis distintos:

| Campo | Unidade | Incrementado por | Quem lê |
|---|---|---|---|
| `unidadesFranquiaConsumidas` | contagem | Pix **isento** | a ordem de avaliação (passo 1) |
| `valorTarifadoNaCompetencia` | monetário | Pix **tarifado** | a ordem de avaliação (passos 3 e 4) |
| `unidadesFranquiaEstornadas` | contagem | estorno de Pix isento | apenas o fechamento |
| `valorEstornadoNaCompetencia` | monetário | estorno de Pix tarifado | apenas o fechamento |

**Todos os acumuladores são monotônicos: só incrementam, nunca decrementam.** Os dois primeiros são
estado de decisão; os dois últimos são ajuste de fatura, invisíveis para a ordem de avaliação.

Invariantes: `unidadesFranquiaConsumidas ≤ franquia do plano` e `valorTarifadoNaCompetencia ≤ teto
do plano`. A fatura final é o líquido: `valorTarifadoNaCompetencia − valorEstornadoNaCompetencia`.

A decisão depende deste acumulado, e não do evento que chegou. É o que torna a tarifação uma decisão
sobre agregado em vez de cálculo sobre payload — e é o que obriga a serializar o processamento por
empresa.

## Determinismo da fatura

Com tarifa por faixa, **quais** Pix recebem a isenção altera o total do mês: dez Pix de R$100 e um de
R$6.000 produzem fatura de R$0,50 ou de R$10,00 conforme qual deles ficou isento. A fatura só é
reproduzível se a ordem de avaliação for estável.

Ela é estável porque os eventos de uma empresa são consumidos na ordem em que foram publicados, e o
replay do log reproduz exatamente a mesma sequência. **Isso depende de quatro condições, todas
obrigatórias:**

1. **Chave de partição `idEmpresa`.** Todos os eventos de uma empresa na mesma partição, consumidos
   por um único consumidor do grupo, em ordem total.
2. **O produtor publica em ordem de liquidação.** Premissa acordada com o time do core: o Pix
   liquidado é publicado no tópico imediatamente, sem lote nem fila intermediária, de modo que a
   ordem de publicação acompanha a de liquidação. A partição preserva a ordem de *publicação*, não a
   de liquidação no SPI — se essa premissa cair, a fatura continua determinística, mas deixa de
   corresponder à cronologia real, e é isso que a auditoria questionaria.
3. **Produtor idempotente ligado** (`enable.idempotence=true`). Sem isso, retry com múltiplas
   requisições em voo reordena mensagens dentro da própria partição.
4. **Número de partições estável.** Alterá-lo rebate o hash das chaves e espalha os eventos de uma
   empresa por partições diferentes, destruindo a ordem total das chaves existentes.
5. **Retry bloqueante.** A retentativa pausa o consumo da partição e repete o mesmo registro em
   posição, sem tirá-lo da fila. Retry por tópico de retentativa está **vedado** neste consumidor:
   ele republica a mensagem falha e a reintroduz fora de ordem, o que altera qual Pix recebe a
   isenção e, por consequência, o valor da fatura.

### Custo do retry bloqueante, aceito

Repetir em posição preserva a ordem, mas **pausa a partição inteira** enquanto tenta. Uma mensagem
envenenada não trava apenas os Pix da própria empresa: trava todas as empresas que dividem aquela
partição, até as tentativas se esgotarem. É head-of-line blocking, e é o preço de a fatura ser
reproduzível.

Consequência operacional: o backoff precisa ser curto o suficiente para não acumular lag, e o
monitoramento de lag por partição passa a ser sinal de primeira linha — lag concentrado em uma
partição costuma ser mensagem envenenada, não volume.

### Risco residual: a DLQ

Esgotadas as tentativas, a mensagem vai para a DLQ e, quando reprocessada, reentra **fora de
posição** — podendo receber uma isenção que pertenceria a outro Pix. Com o retry bloqueante, esta é
a única fonte de não-determinismo que sobrevive às cinco condições acima.

**O risco é aceito nesta versão.** A frequência esperada é baixa e o efeito é limitado a *qual* Pix
da competência ficou isento, não ao total de isenções concedidas nem ao número de Pix tarifados.

Saída conhecida, para etapa posterior: **reavaliar no fechamento** a alocação da franquia por ordem
de `liquidadoEm`, com desempate por `idTransacaoPix`, tratando a decisão do consumo como provisória
e a do fechamento como definitiva.

## Compensação

**Todo Pix entra na fatura — o isento entra com valor 0.** É por isso que a recusa do Faturamento
por contrato inativo alcança os dois casos: não existe Pix invisível ao ponto que consulta a fonte
autoritativa.

A regra central da compensação, dita numa frase: **decisão em streaming é imutável; estorno é
ajuste de fatura, nunca reversão de decisão.** Os acumuladores de decisão não são decrementados —
o estorno incrementa o acumulador de ajuste correspondente:

| Pix estornado | Ação |
|---|---|
| tarifado (`FAIXA` ou `TETO_PARCIAL`) | `valorEstornadoNaCompetencia += valor cobrado` |
| isento (`FRANQUIA`) | `unidadesFranquiaEstornadas += 1` |
| não cobrado (`TETO_ATINGIDO`) | nada a estornar; apenas a marcação `nao_tarifavel` |

Isso estende ao estado o princípio que já valia para o registro: compensação é operação inversa com
rastro, não rollback. A fatura fica com as duas linhas — a cobrança e o estorno — e o fechamento
apura o líquido.

**Consequências desta escolha, aceitas:**

- **O teto nunca reabre.** O espaço estornado não é recobrado: se uma tarifa de R$ 5,00 é estornada,
  o mês fecha em R$ 1.995,00 mesmo que houvesse volume para saturar o teto. A direção do erro é
  cobrar de menos, nunca de mais. O custo é quase teórico: o estorno só existe por contrato inativo
  na competência, que raramente afeta um Pix isolado — afeta o mês inteiro, que será estornado de
  qualquer forma.
- **Nenhuma decisão já tomada fica inconsistente**, porque o estado que a produziu nunca muda. Os
  Pix que passaram como `TETO_ATINGIDO` permanecem válidos; a linha `TETO_PARCIAL` permanece única.
- **O determinismo da fatura não depende dos estornos.** Como o estorno não influencia decisões, a
  posição dele no tempo é irrelevante para a ordem de avaliação — o replay do log de `pix.liquidado`
  reproduz as decisões sozinho, e os estornos são reaplicáveis em qualquer ordem, porque soma é
  comutativa.
- **Estorno após o fechamento** não mexe em estado de decisão de mês fechado: é o mesmo lançamento
  de ajuste, processado pelo mecanismo de ajuste retroativo já previsto na seção Competência.
- **Estorno de isento não devolve a unidade nem gera estorno de outro Pix.** Se a unidade "gasta"
  no Pix invalidado fez um Pix posterior pagar faixa, a correção é um **crédito de ajuste no
  fechamento**, no valor da faixa — linha nova na fatura, sem tocar em decisão nem em sistema
  externo. Estorno (da saga) desfaz um efeito indevido de um Pix tarifado recusado; crédito (do
  fechamento) corrige o agregado do mês. Manter a distinção é o que preserva a regra central. Na
  prática o caso é quase vazio: contrato inativo invalida a competência inteira, e o "Pix
  posterior" seria recusado também.

