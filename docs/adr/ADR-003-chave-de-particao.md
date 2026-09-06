# ADR-003 — Chave de partição do tópico `pagamentos.pix.realizado.v1`

## Status

Aceita · 2026-09-05 · Equipe 02

Substitui, na parte que trata de partição quente, a mitigação anunciada no
[ADR-002](ADR-002-dominio-do-projeto.md) — ver *Consequências aceitas*, item 5.

## Contexto

O tópico `pagamentos.pix.realizado.v1` tem **três partições** e é publicado com **`idEmpresa` como
chave** desde a primeira versão do publicador (`servico-pix/.../KafkaConfig.java`, `partitions(3)`;
e `servico-pix/.../PixService.java`, no `ProducerRecord`). A escolha foi feita ali e justificada em
javadoc, mas nunca passou por um ADR. Precisa de um porque a chave não decide apenas a ordem de
processamento: decide **o que se pode agregar sem repartir** — e essa segunda consequência só ficou
visível quando um segundo consumidor entrou no tópico.

O tópico tem hoje **dois consumidores, em grupos distintos**, e eles fazem perguntas de negócio
diferentes:

| Consumidor | Grupo | A pergunta que ele responde |
|---|---|---|
| `servico-tarifacao` | `tarifacao` | quanto esta **empresa** deve na competência? |
| `servico-agregador-pix` | `agregador-pix-por-hora` | quanto foi liquidado **por hora**, no total? |

Quatro perguntas decidem uma chave de partição. Respondidas para este domínio:

**1 · Qual é a menor unidade cuja ORDEM o negócio exige?** A **empresa**. A tarifação não é cálculo
sobre o payload: é decisão sobre o acumulado da competência. `contarFranquiaConsumida` e
`totalTarifadoNaCompetencia` são leituras que antecedem uma escrita, e um read-then-write só é
correto se os eventos daquela empresa forem processados **em série**. Quem serializa é a chave de
partição, não um lock no banco.

**2 · Por qual dimensão o negócio pergunta o tempo todo?** Aqui há **duas** perguntas, e é onde a
decisão fica interessante:

- a tarifação, o fechamento mensal, a auditoria e a contestação comercial perguntam **por empresa** —
  os três usos que o ADR-002 já nomeia. Esta dimensão **coincide com a resposta da pergunta 1**;
- o agregador pergunta **por hora**, somando todas as empresas. Esta dimensão **não tem recorte por
  empresa nenhum** — uma janela de hora atravessa, por construção, as três partições.

**3 · Alguma chave concentra muito volume?** Sim. Uma empresa de altíssimo volume — uma adquirente,
um marketplace — concentra o tráfego dela numa partição só.

**4 · Quantas partições, e dá para mudar depois?** Três — **e mudar custa uma janela de parada,
não é impossível.** A distinção importa, porque a versão forte desta frase ("é irreversível") leva a
decisões erradas nos dois sentidos.

O Kafka roteia por `murmur2(chave) % nº de partições`. Aumentar o número **no lugar**, com
`--alter --partitions`, é inseguro: os eventos novos de uma empresa passam a cair numa partição
diferente da que guarda os antigos, e enquanto houver backlog nas duas a mesma empresa é lida por
**dois consumidores ao mesmo tempo**. Não é a ordem dentro da partição que se perde — cada partição
continua ordenada. O que se perde é a garantia de **um único leitor por empresa a cada instante**,
que é a premissa de que a franquia e o teto dependem: dois consumidores leriam "4 de 10 isenções
usadas" e os dois emitiriam `FRANQUIA`. O mesmo vale para o replay, que passaria a ler o histórico
de uma empresa em duas partições concorrentes e deixaria de reproduzir a sequência original.

A migração segura é um **cutover com dreno**, e é procedimento conhecido:

1. criar o tópico novo, com o número de partições desejado e a mesma chave;
2. pausar a publicação no `servico-pix`;
3. drenar os consumidores até lag zero em todas as partições do tópico antigo;
4. apontar produtor e consumidores para o tópico novo e retomar.

Como nenhuma empresa tem evento em voo no instante da virada, a ordem por chave é preservada e não
há janela de leitura concorrente. O custo é a indisponibilidade de publicação entre os passos 2 e 4,
e a retenção do tópico antigo enquanto ele ainda for necessário para replay — replay que, a partir
da virada, atravessa dois tópicos e precisa respeitar essa fronteira.

Ou seja: o número de partições é caro de mudar, e por isso se escolhe com folga na criação. Não é
irreversível.

## Decisão

**A chave permanece `idEmpresa`.**

Para a tarifação, as perguntas 1 e 2 **convergem**: a unidade cuja ordem o negócio exige é a mesma
dimensão pela qual o negócio pergunta. Quando isso acontece, não há repartição a pagar, e é o
melhor caso possível. O fluxo que cobra dinheiro é o que recebe a chave certa.

**E declaramos, como parte desta decisão, que o `servico-agregador-pix` roda em UMA instância.**

Não é detalhe operacional: é a consequência direta da chave escolhida, e precisa estar escrita. O
agregador soma por janela de hora, e a janela de hora não tem chave. Com uma instância, ela recebe
as três partições e vê a hora inteira — **e é por isso que o número dele está certo hoje**. Com duas
ou três instâncias, cada uma passaria a somar uma parcial da *mesma* janela, cada uma perfeitamente
correta sozinha, e nenhuma correta no total. Nada quebraria: sem exceção, sem erro de
desserialização, sem aviso do broker. O relatório fecharia.

Enquanto isso não estiver escrito, a correção do relatório depende de **quantas instâncias estão no
ar** — que é uma decisão de infraestrutura, não de negócio. Um `docker compose --scale` de alguém
que não leu este arquivo mudaria o faturamento relatado sem mudar uma linha de código.

Por isso a decisão vem acompanhada de uma verificação em código: o agregador registra quantas
partições lhe foram atribuídas e **emite `WARN` quando recebe menos que o total do tópico**. Uma
agregação global que silenciosamente vira parcial é indistinguível de um defeito enquanto ninguém a
mede; com a medida, deixa de ser acidente e passa a ser decisão observável.

## Alternativas consideradas

**`idTransacaoPix`.** Recusada, e é a recusa que mais importa. Dois Pix da mesma empresa cairiam em
partições diferentes e seriam processados em paralelo por consumidores diferentes; os dois leriam
"4 de 10 isenções usadas", os dois sairiam `FRANQUIA`, e a franquia estouraria. O mesmo vale para o
teto mensal, que também lê antes de escrever. É a chave que a intuição sugere — uma chave por
transação distribui lindamente — e ela quebra a única invariante do domínio.

**Chave composta `idEmpresa + competência`.** Espalharia uma empresa grande por várias partições ao
longo dos meses, e preservaria a ordem dentro da competência, que é o recorte de que a franquia
precisa. Recusada porque **não resolve nada quando dói**: em qualquer instante, quase todo o tráfego
está na competência corrente, então a empresa quente continua concentrada numa partição só. Paga
complexidade de roteamento e um caso de borda na virada do mês para comprar folga apenas no
histórico, que é justamente onde não há pressão.

**Sem chave, distribuindo em rodízio.** Maximiza o paralelismo e elimina a partição quente.
Recusada pelo mesmo motivo do `idTransacaoPix`, em grau maior: sem chave não há ordem por empresa
nenhuma, e a tarifação deixa de ser correta.

**Repartir por janela de hora, para o agregador.** Um `pagamentos.pix.realizado.v1.por-hora`
chaveado pelo início da hora, permitindo escalar o agregador. **Recusada**, e por um argumento
específico: a chave seria o relógio, e todo o tráfego da hora corrente cairia numa partição só.
Trocaríamos uma partição quente por empresa — que ao menos varia com o negócio — por uma partição
quente por hora, que é 100% do tráfego, sempre, por construção. Repartir aqui é pagar os custos da
repartição para comprar um problema pior.

## Consequências aceitas

**1 · Partição quente por empresa.** Uma empresa de altíssimo volume não acelera com mais
instâncias: o trabalho dela cabe numa partição, e mais consumidores ficam ociosos enquanto aquela
partição acumula lag. É o outro lado exato do que a chave compra, e não tem contorno dentro desta
decisão. O sinal a monitorar é o **lag da maior partição contra a mediana das outras** — lag
concentrado em uma partição é chave quente ou mensagem envenenada, nunca volume.

**2 · O paralelismo do `servico-tarifacao` tem teto de três — e três é pouco. Isto é dívida, não
restrição.** Três partições significam no máximo três consumidores úteis no grupo `tarifacao`; o
quarto fica ocioso. O número não foi dimensionado: veio da criação do tópico, quando o que se queria
era ver o fluxo funcionar.

Para o volume de Pix PJ de uma instituição real, três é subdimensionado, e o eixo da partição
provavelmente nem é o primeiro a saturar. Cada evento custa **cinco a seis idas ao Postgres dentro
de uma transação** — `INSERT` de deduplicação, `SELECT` da oferta, `SELECT` das faixas, `COUNT` da
franquia, `SUM` do teto quando há teto, e o `INSERT` da tarifa. Esse custo, e não a contagem de
partições, é o que define a vazão por consumidor, e ele precisa ser **medido** antes de qualquer
decisão de dimensionamento.

**A ação que este ADR registra:** subir o número de partições **agora**, enquanto o tópico é pequeno
e o dreno do passo 3 da pergunta 4 leva segundos. Fazer isso depois, com meses de histórico e
retenção longa, transforma um cutover de minutos numa operação de manutenção negociada. A chave não
muda — continua `idEmpresa`; muda só a folga. Um ponto de partida da ordem de 24 a 48 partições
mantém o mesmo desenho e tira este item da lista de limites estruturais, ao custo de mais
consumidores possíveis do que instâncias que pretendemos rodar — que é exatamente a folga que se
quer ter, porque adicionar consumidor é barato e adicionar partição não é.

**3 · O `servico-agregador-pix` fica preso a uma instância.** Ele não escala. Uma instância dá conta
com folga do volume atual, mas o limite é estrutural, não de capacidade: escalar exigiria o
mecanismo do item seguinte.

**4 · A pergunta por hora não é respondível sem repartir, e por ora é atendida por uma instância
única e declarada.** O relatório horário está correto porque a topologia é de uma instância, e não
porque a arquitetura o garanta em qualquer topologia. O `WARN` de partições torna a violação visível,
mas não a impede.

**5 · A mitigação de partição quente anunciada no ADR-002 fica revogada.** Aquele ADR previa, para o
caso de a partição quente aparecer, "tornar o consumo de franquia comutativo (reserva por token)".
Esta ADR recusa aquela saída: consumo comutativo significa processar Pix da mesma empresa **fora de
ordem**, e a ordem total por empresa é a primeira das cinco condições que
[docs/regra-de-tarifacao.md](../regra-de-tarifacao.md#determinismo-da-fatura) declara obrigatórias
para a fatura ser reproduzível. Reserva por token preserva **quantas** isenções foram concedidas,
mas não **quais** Pix as receberam — e, com tarifa por faixa, *quais* altera o total do mês. Compraria
vazão ao preço da reprodutibilidade, que é o quarto critério pelo qual o ADR-002 escolheu este
domínio. Se a partição quente aparecer, a saída aceita passa a ser negociar a granularidade
comercial (segregar a empresa quente num tópico próprio, com o acumulador dela isolado), não relaxar
a ordem.

## Se a pergunta por hora virar prioridade

A pergunta que a chave **não** responde é *"quanto foi liquidado por hora, somando todas as
empresas?"*. Se ela passar a exigir escala, a saída **não** é o repartition topic recusado acima. É a
**agregação em dois estágios**:

1. cada instância do agregador soma o que vê nas partições que lhe couberam e publica um parcial por
   `(janela, instância)`;
2. um segundo estágio soma os parciais e produz o total da janela.

O que torna isso legítimo é uma propriedade do domínio, e não uma esperteza de implementação: **a
soma é comutativa e associativa**, então o resultado independe de ordem e de particionamento — a
mesma propriedade que sustenta a afirmação de que reprocessar o tópico do início devolve o mesmo
número.

E é exatamente o que **não** vale para a tarifação. A franquia não é comutativa: qual Pix recebe a
isenção muda o valor da fatura. É essa diferença que autoriza tratar os dois consumidores de formas
opostas — um pode ser dividido e recomposto, o outro não pode nem ser dividido. A chave de partição
é a mesma; o que muda é a álgebra da agregação de cada um.

Não há chave que sirva a todas as perguntas. Há a escolha, e a consequência aceita.
