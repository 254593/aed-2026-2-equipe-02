# ADR-002 — Domínio do projeto

## Status

Aceita · 2026-08-14 · Equipe 02

## Contexto

Estamos implementando o Pix para contas PJ, e cada Pix liquidado precisa ser tarifado conforme a
oferta comercial vigente. A liquidação é regulada pelo BCB, quase instantânea. O problema que esta
ADR resolve é onde a tarifação vive: dentro do fluxo de pagamento ou fora dele. A decisão é agora
porque o fluxo de Pix está sendo construído neste momento; definir essa fronteira depois significa
refazê-lo.

O domínio foi trazido por um integrante que trabalhou na esteira de tarifação de uma instituição
financeira: contas PJ que emitem Pix, franquia mensal de isenções por plano, débito da tarifa em
parceiro bancário e envio ao faturamento. As regras, os modos de falha e o estorno descritos nesta
ADR se baseiam nessa operação, inclusive a distinção entre recusa definitiva e indisponibilidade,
da qual depende todo o caminho de compensação.

## Decisão

O processo é a **tarifação de Pix de contas PJ**: o gatilho é um Pix liquidado no SPI pelo Core de
Pagamentos; consultamos a oferta comercial vigente, aplicamos a política de franquia e decidimos
entre isentar e tarifar; quando tarifamos, a tarifa é aplicada, debitada na conta da empresa através
do Parceiro de Lançamentos e enviada ao Faturamento. O desfecho é a tarifa faturada, ou, quando o
Faturamento recusa a competência, a tarifa estornada.

Regra que sustenta o recorte, declarada explicitamente: **empresa sem contrato de tarifação vigente
na data de competência não é cobrada.** Não há tabela padrão de fallback; cobrar sem contrato é
cobrança indevida, com exposição a devolução em dobro (CDC, art. 42, parágrafo único).

Como ele atende cada um dos quatro critérios:

- **ponto de decisão com regra de negócio:** a `PoliticaDeTarifacao` decide, para cada Pix, entre
  *isentar* (dentro da franquia mensal do plano), *tarifar por faixa de valor* ou *não cobrar por
  teto mensal atingido*. Aprova, recusa e limita: é decisão, não cálculo.

- **sistema externo:** o **Parceiro de Lançamentos**, serviço do banco parceiro que efetiva o débito
  da tarifa na conta da empresa. Expõe duas operações, `lançar` e `estornar`, e nada além disso.
  É de terceiro: não negociamos a janela de manutenção, não coordenamos deploy e não temos a quem
  ligar quando cai. É o que torna a indisponibilidade real e dá sentido ao assíncrono.

- **caminho de exceção com compensação:** o cálculo usa uma **réplica local** do Cadastro de Ofertas,
  alimentada por eventos e portanto eventualmente consistente. Quando ela está defasada, tarifamos
  uma empresa cujo contrato já não vigia, e o Faturamento, que consulta a fonte autoritativa, recusa
  a competência. Isso obriga a desfazer, em ordem inversa, efeitos já commitados em dois sistemas:
  `estornar` no Parceiro de Lançamentos, `PixTarifado` para `estornada` com motivo e correlação,
  devolução da unidade de franquia consumida, e o Pix marcado `nao_tarifavel` para que replay não
  recobre. Nada é apagado: compensação é operação inversa com rastro, não rollback, e o rastro é a
  defesa contra a acusação de cobrança indevida. Recusa por contrato inativo é definitiva: repetir
  não muda o resultado, o que a distingue da indisponibilidade do parceiro, que é retry e DLQ.

- **algo que valha reprocessar:** o **fechamento mensal de tarifas por empresa**, reconstruído do
  histórico de aplicações e estornos. Serve à auditoria ("por que esta empresa pagou este valor"), à
  contestação comercial e à simulação de troca de plano: três leituras do mesmo histórico.

## Alternativas consideradas

**Análise de viabilidade de compra**, candidato trazido do EventStorming da aula 01. É mais forte que
o escolhido no primeiro critério: a `Análise de Compra` classifica em *viável*, *viável com ajustes*
ou *inviável* a partir de comprometimento de renda, saldo projetado e impacto nas metas, decisão de
três saídas com limiares explícitos. Atende o quarto com folga, porque toda a análise é projeção
reprocessável. Recusado pelo terceiro: o fluxo produz **informação**, não efeito colateral, e
informação não se compensa. O caminho de exceção existe, `DadosFinanceirosInsuficientes` interrompe
e pede complemento ao usuário, mas interromper não desfaz nada. A ADR da aula 01 já registrava a
conclusão: Saga foi descartada porque "o fluxo de análise não executa transações distribuídas que
exijam compensações entre etapas". Some-se que nenhum sistema externo chegou a ser modelado, o
agregador de Open Finance ficou como hotspot no mural e não como ator, o que deixaria também o
segundo critério em aberto.

## Consequências aceitas

**O que nos custa.** Consistência eventual em dois lugares. Entre o Pix liquidado e a tarifa, o que
impede qualquer tela de prometer saldo de tarifas exato em tempo real. E na réplica local do
Cadastro de Ofertas, que é justamente o que produz o caminho de compensação acima: o custo da
arquitetura escolhida *é* o caso de exceção do domínio, não uma coincidência. Além disso, o
consumidor passa a ser obrigatoriamente idempotente, com uma escrita a mais por evento e um caminho
de "já processado" que precisa ser testado como caminho feliz.

**O que fica de fora do escopo.** A precificação e a negociação da oferta, das quais apenas
consumimos o resultado no Cadastro de Ofertas. A emissão fiscal e a régua de inadimplência, que
consomem o nosso resultado. A devolução de Pix e o MED: são fato de negócio posterior, com processo
próprio, e não compensação desta saga. E Pix de contas PF, que tem regra de gratuidade distinta.

**O que vai ficar difícil na aula 04.** A franquia mensal é um contador por empresa, e contador
exige ordenação: particionamos por `clienteId`, o que limita a vazão ao número de partições e
transforma uma empresa de altíssimo volume em *hot partition* que não acelera com mais instâncias.
Mitigação: publicamos com essa chave desde a aula 02 e vamos monitorar o lag da maior partição
contra a mediana; se a hot partition aparecer, a saída é tornar o consumo de franquia comutativo
(reserva por token), não aumentar partições.

**O que vai ficar difícil na aula 05.** Três coisas, já mapeadas. Primeira: a compensação atravessa
dois sistemas sem atomicidade, e uma falha no meio dela deixa lançamento estornado com franquia não
devolvida, ou o inverso; a mitigação é compensação idempotente por chave de correlação, com
`estorno_solicitado` e `estorno_confirmado` como estados explícitos da tarifa, nunca um booleano.
Segunda: o **estado indeterminado**, quando o Parceiro de Lançamentos dá timeout e não se sabe se
lançou; não se pode estornar um lançamento que talvez não exista, então o passo obrigatório é
consultar por chave de idempotência antes de decidir. Terceira: vamos usar um **orquestrador dentro
do nosso contexto** para conduzir a compensação, embora a comunicação entre domínios siga
coreografada, e a aparente contradição é deliberada, porque compensação coreografada não deixa saber
em que ponto a saga parou. O dual write entre banco e broker se resolve com outbox nessa mesma aula;
até lá, reconciliação diária.

**Risco de escopo, aceito conscientemente.** O domínio tem três fluxos: tarifar, compensar e fechar.
A aula 02 entrega apenas o tarifar. A saga de compensação e o fechamento entram em novas iterações.
