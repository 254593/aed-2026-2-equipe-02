# Entregas da Aula 04 — A janela e a chave de partição

**Equipe 02 · AED · Turma ASDO 11.1 · PUC Minas / IEC**

| O quê | Onde |
|---|---|
| A justificativa da chave | [docs/adr/ADR-003-chave-de-particao.md](../adr/ADR-003-chave-de-particao.md) |
| O agregador e a janela | [servico-agregador-pix/](../../servico-agregador-pix/) |
| Registro de uso de IA | [docs/IA.md](../IA.md) |
| O domínio | [docs/adr/ADR-002-dominio-do-projeto.md](../adr/ADR-002-dominio-do-projeto.md) |

---

## 1. O que foi agregado

**Valor total liquidado e quantidade de Pix, por hora** — as duas coisas juntas, e não só uma.

A quantidade sozinha não responde à pergunta do financeiro, e o valor sozinho esconde o que
distingue uma hora de mil Pix pequenos de uma hora de dois Pix grandes. As duas saem na mesma linha
de log, com a janela, a partição e o offset, para que se possa conferir na mão qual mensagem entrou
em qual janela:

```
17:42:10 INFO  AgregadorListener : pix agregado  transacao=pix-102  liquidadoEm=2026-08-23T14:20:00Z
                                   ->  janela [2026-08-23T14:00:00Z, 2026-08-23T15:00:00Z) | R$ 300.50 | 2 Pix
                                   (particao=1 offset=6)
```

A agregação já estava feita desde a aula 03, com a pergunta de negócio e a escolha do relógio
justificadas em [aula-03.md](aula-03.md). Esta aula não mudou o que se agrega: mudou o que sabemos
sobre **por que aquilo estava certo**.

---

## 2. Qual janela, e por que essa janela

> **Tumbling de 1 hora**, alinhada em UTC, início inclusivo e **fim exclusivo** — `[14:00, 15:00)`.

Implementada em
[`JanelaDeHoraVO`](../../servico-agregador-pix/src/main/java/br/pucminas/aed/agregador/domain/JanelaDeHoraVO.java),
com `truncatedTo(ChronoUnit.HOURS)` sobre o `liquidadoEm` do evento.

**A razão é que a pergunta pede uma soma que signifique alguma coisa.** A pergunta do financeiro é
*"quanto foi liquidado em Pix por hora?"*, e quem a faz vai somar as horas para chegar ao dia, e os
dias para chegar ao mês. Das quatro janelas, **a tumbling é a única em que essa soma reproduz o
faturamento real**, porque os intervalos são justapostos: cada Pix pertence a exatamente uma janela.

O contraste é aritmético, e vale a pena escrever porque não é intuitivo. O teste 2 de
[`AgregacaoPorHoraTest`](../../servico-agregador-pix/src/test/java/br/pucminas/aed/agregador/AgregacaoPorHoraTest.java)
publica três Pix — R$ 100,00 às 14:10, R$ 200,50 às 14:20 e R$ 300,00 às 14:59:59 — e exige
**R$ 600,50 numa janela só**. Se trocássemos por uma janela *hopping* de 5 horas com salto de 1 hora,
cada um desses Pix entraria em **cinco** janelas sobrepostas, e a soma dos relatórios daria
**R$ 3.002,50** para um faturamento real de R$ 600,50. Cinco vezes, que é `tamanho ÷ salto`.

E o que torna isso perigoso não é o erro: é que **cada janela do hopping estaria correta**. Nenhuma
exceção, nenhum aviso, nenhum log de erro — apenas um relatório plausível, e um relatório plausível
é mais caro que um quebrado, porque ninguém vai investigá-lo.

**O fim exclusivo é parte da mesma decisão.** O Pix das 14:59:59 pertence à janela das 14h; o das
15:00:00 pertence à das 15h. Sem a exclusividade, um Pix na fronteira entraria em duas janelas e a
soma deixaria de fechar — o mesmo defeito do hopping, em escala menor e mais difícil de achar. É a
mesma decisão que a especificação da regra de tarifação já tinha tomado nas faixas de valor
("limite inferior inclusivo, superior exclusivo"), e o `JanelaDeHoraVOTest` fixa as duas bordas:
o teste 2 exige que 14:00:00 e 14:59:59.999 caiam na mesma janela, e o teste 3 exige que 15:00:00
**não** caia nela.

---

## 3. O que essa janela deixa de responder

Nenhuma das quatro janelas está errada. Errada é só a pergunta que não foi feita — e escolher a
tumbling é assumir três perguntas que este relatório não responde:

| Pergunta | Janela que a responderia | Por que não é a nossa |
|---|---|---|
| qual a média móvel das últimas 5 h, a cada hora? | hopping | detecção de tendência; a nossa pergunta é de fechamento, e somar hopping não significa nada |
| quantos Pix ocorreram nas 5 h anteriores a **este**? | sliding | uma janela por evento, com limites que não são redondos; nenhum relatório de receita fecha assim |
| o que cada empresa fez numa mesma "visita"? | session | é a única que agrega **por empresa** — e é justamente ela que a chave de partição tornaria barata, e a nossa cara. Ver o ADR-003 |

A terceira linha é a interessante: a janela de sessão é a que a nossa chave de partição serviria
bem, e é a que o nosso negócio não pergunta. A que o negócio pergunta é a que a chave não serve.

---

## 4. A chave de partição — e o que descobrimos sobre ela

A decisão está no [ADR-003](../adr/ADR-003-chave-de-particao.md); aqui fica o que a aula fez a
equipe enxergar no próprio código.

O tópico é chaveado por **`idEmpresa`** desde a aula 02. Para a tarifação isso é o melhor caso
possível: a menor unidade cuja ordem o negócio exige (a empresa, por causa da franquia e do teto) é
**a mesma** dimensão pela qual o negócio pergunta (o fechamento mensal por empresa). As duas
perguntas convergem, e a tarifação não paga repartição nenhuma.

**Mas a janela de hora não tem chave.** Ela atravessa, por construção, as três partições. O
`servico-agregador-pix` acerta o total hoje porque roda com **uma instância**, que recebe as três
partições e enxerga a hora inteira — ou seja, **ele estava correto por acidente**. Com duas ou três
instâncias, cada uma somaria uma parcial da mesma janela de hora, cada uma perfeitamente coerente
consigo mesma, e o total estaria errado em todas. Sem exceção, sem erro, sem aviso do broker.

O que isso significa na prática: até esta aula, a correção do nosso relatório dependia de **quantas
instâncias estavam no ar** — uma decisão de infraestrutura, não de negócio.

---

## 5. O que mudou no código

Uma coisa só, e ela decorre diretamente do ADR-003: uma **guarda de partições** no agregador
([`KafkaConfig.guardaDeInstanciaUnica()`](../../servico-agregador-pix/src/main/java/br/pucminas/aed/agregador/KafkaConfig.java)).
No rebalanceamento, o consumidor compara quantas partições recebeu com quantas o tópico tem, e
emite `WARN` quando recebe menos:

```
INFO  KafkaConfig : agregacao completa: 3 de 3 particoes de pagamentos.pix.realizado.v1
```

```
WARN  KafkaConfig : AGREGACAO PARCIAL: recebi 1 de 3 particoes de pagamentos.pix.realizado.v1.
                    As janelas deste processo somam apenas a fatia dele, e o total por hora esta
                    ERRADO em todas as instancias. [...]
```

**O aviso não impede a violação** — quem atribui partição é o coordenador do grupo, não o processo.
Ele a torna visível no instante em que acontece, que é o que faltava. É o mesmo critério que a aula
aplica ao descarte: perder dado é uma decisão legítima, perder dado sem contador não é. Uma
agregação global que silenciosamente vira parcial é indistinguível de um defeito enquanto ninguém a
mede.

Os 13 testes do módulo continuam passando, e o log da bateria mostra a guarda registrando
`3 de 3` — o caso correto, afirmado em vez de suposto.

---

## 6. O que decidimos não fazer

**Não repartimos por janela de hora.** Seria a saída óbvia para escalar o agregador, e ela é pior
que o problema: a chave passaria a ser o relógio, e todo o tráfego da hora corrente cairia numa
partição só. Trocaríamos uma partição quente por empresa — que ao menos varia com o negócio — por
uma partição quente por hora, que é 100% do tráfego, sempre. A saída que o ADR-003 registra para o
dia em que essa pergunta virar prioridade é a **agregação em dois estágios**, que funciona porque a
soma é comutativa e associativa. Ela não foi implementada: a pergunta não é prioridade hoje, e o
ADR existe para que a decisão esteja pronta quando for.

**Não introduzimos programação reativa.** A aula apresenta o modelo, e a tentação é adotá-lo por
aderência ao tema. O `servico-tarifacao` decide consultando o Postgres por `JdbcTemplate`, que é
bloqueante: uma chamada bloqueante no meio de uma cadeia reativa anula o modelo e ainda esconde o
problema, porque o compilador não avisa. Somado a isso, o nosso gargalo não é thread ociosa
esperando I/O — é a serialização por empresa, que é uma exigência do domínio e não some com
framework nenhum. O registro completo dessa recusa está em [IA.md](../IA.md).

**Não escalamos o agregador.** Ele continua em uma instância, agora por decisão declarada no
ADR-003 e verificada em runtime, e não por acidente de topologia.

**Não persistimos o estado da janela**, e aqui há um caso que esta aula deixou mais claro. O
`auto-offset-reset: earliest` só vale para um grupo **sem** offset commitado; num reinício comum o
agregador retoma do offset e reconstrói as janelas a partir do zero, devolvendo totais parciais.
É a mesma classe de falha do "correto por acidente": nada quebra e o relatório fecha. O
procedimento correto para reprocessar continua sendo apagar o grupo, como
[aula-03.md](aula-03.md#4-se-o-fluxo-fosse-reprocessado-do-começo-amanhã-o-resultado-seria-o-mesmo)
documenta — mas o caminho não deliberado ficou registrado como pendência, junto com outros pontos
levantados numa auditoria do código contra a própria documentação. Persistir a janela, ou reler
sempre do início, entra no escopo da aula 05, com o Event Sourcing.

---

## Resumo

| Decisão | Escolha |
|---|---|
| O que se agrega | valor total **e** quantidade de Pix, por hora |
| Janela | **tumbling** de 1 h, UTC, fim exclusivo |
| Por que tumbling | é a única cujas janelas **somam** ao faturamento real — hopping somaria `tamanho ÷ salto` vezes |
| Chave de partição | **`idEmpresa`**, mantida — ver [ADR-003](../adr/ADR-003-chave-de-particao.md) |
| Responde sem repartir | *quanto esta empresa deve na competência?* |
| Deixou de responder | *quanto foi liquidado por hora, somando todas as empresas?* |
| Se virar prioridade | agregação em dois estágios, **não** repartition topic por hora |
| Instâncias do agregador | **uma**, por decisão declarada e verificada em runtime |
| Reativo | recusado — trecho bloqueante (JDBC) na cadeia, e o gargalo não é espera |
