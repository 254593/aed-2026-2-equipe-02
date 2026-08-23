# Entregas da Aula 03 — Contrato e Agregador

**Equipe 02 · AED · Turma ASDO 11.1 · PUC Minas / IEC**

| O quê | Onde |
|---|---|
| Parte A — contrato do evento | [docs/contrato.md](../contrato.md) |
| Parte B — o agregador | [servico-agregador-pix/](../../servico-agregador-pix/) |
| Registro de uso de IA | [docs/IA.md](../IA.md) |
| Como rodar | [README.md](../../README.md#agregador-quanto-foi-liquidado-por-hora) |

---

## 1. Qual pergunta de negócio a agregação responde

> **"Quanto foi liquidado em Pix por hora?"** — em reais e em quantidade de transações.

Quem faz essa pergunta é o time financeiro, e ela tem consequência: alimenta a projeção de receita
de tarifas, sustenta a conversa de capacidade com o Parceiro de Lançamentos (que cobra por volume) e
serve de linha de base para detectar anomalia — uma hora de terça à tarde com um décimo do volume
das outras terças é incidente operacional, não sazonalidade.

Não é `"quantos eventos por minuto"`. Essa é métrica de infraestrutura, se responde com processing
time sem pensar, e não muda decisão nenhuma do negócio.

---

## 2. Qual relógio foi escolhido, e por quê

> **Event time** — o campo `liquidadoEm` do evento, que é o instante em que o Pix liquidou no SPI.

Não é a hora em que a mensagem chegou ao broker, nem a hora em que o agregador a processou.

**A razão é que a pergunta é sobre o negócio, não sobre o sistema.** "Quanto foi liquidado das 14h
às 15h" é uma afirmação sobre transações que aconteceram naquela hora. Se um pico de tráfego
atrasar o consumo em vinte minutos, o dinheiro não mudou de hora — só a nossa ciência dele mudou.
Com processing time, uma indisponibilidade nossa apareceria no relatório como se fosse queda de
faturamento do cliente.

É a mesma decisão que o `servico-tarifacao` já tinha tomado ao derivar a competência do
`liquidadoEm`, e não de `now()`. Os dois serviços usam o mesmo relógio do domínio, e é isso que
permite conciliar o que o agregador mostra com o que a fatura cobra.

**O custo que aceitamos:** latência. Um Pix liquidado agora só entra no número quando o evento
chega. Para receita, chegar certo importa mais do que chegar rápido.

---

## 3. O que acontece com um evento atrasado

**Ele é somado na janela do próprio `liquidadoEm`, e não na hora em que chegou. E, por deduplicação, apenas uma vez.**

Um Pix liquidado às 14:35 que só chega ao agregador às 15:50 entra na janela `[14:00, 15:00)`, que
já tinha eventos. O total daquela hora **aumenta** — a janela não é recriada nem substituída.

Se o mesmo Pix for reenentregue (mesmo `eventoId`), é ignorado na segunda passagem. Um Pix de R$ 150
reenentregue 3 vezes soma R$ 150, não R$ 450. Isso garante que a métrica reflete o número **real**
de Pix, não a quantidade de entregas do Kafka.

O que torna isso possível é:

```java
janelas.compute(janela.getInicio(), (chave, atual) ->
        (atual == null ? AgregacaoPorHoraVO.vazia(janela) : atual).somar(evento.getValor()));
```

Guardar só a janela mais recente pareceria suficiente enquanto os eventos chegassem em ordem — e
eles não chegam. O tópico tem três partições, várias empresas publicam ao mesmo tempo, e horas
diferentes se intercalam o tempo todo. Com uma janela só, cada troca de hora descartaria o
acumulado e o retardatário reiniciaria a contagem do zero. O teste 4 de
[`AgregacaoPorHoraTest`](../../servico-agregador-pix/src/test/java/br/pucminas/aed/agregador/AgregacaoPorHoraTest.java)
publica exatamente essa sequência (14:10, 14:20, 15:05, 14:30) e exige `700,00` na janela das 14h.

**Não há watermark, e a janela nunca fecha.** Qualquer retardatário é aceito, por mais tarde que
chegue. A consequência é que nenhum número é definitivo: a janela das 14h pode crescer amanhã. Para
a pergunta que fazemos isso é adequado — é melhor um total que se corrige do que um total que
descarta receita real por chegar tarde. Fechar a janela com watermark é o desafio opcional da
seção 7, e a decisão interessante lá é o que fazer com quem chega depois do corte.

---

## 4. Se o fluxo fosse reprocessado do começo amanhã, o resultado seria o mesmo?

> **Sim, idêntico.**

A janela de cada evento é uma função pura do `liquidadoEm`, que viaja dentro do evento e é imutável.
A soma é comutativa e associativa, então nem a ordem de chegada nem o particionamento alteram o
total. Não há dependência de:

- hora em que o agregador subiu;
- hora em que a mensagem chegou ao broker;
- relógio da máquina que roda o consumidor;
- estado anterior do processo.

**Isto é verificável, e não só afirmado.** O `auto-offset-reset: earliest` faz o grupo
`agregador-pix-por-hora` ler o tópico desde o offset zero. Apagando o grupo e subindo de novo, ele
reprocessa tudo e chega aos mesmos números:

```bash
docker exec e02-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9094 --delete --group agregador-pix-por-hora
```

O teste 6 da bateria prova a propriedade que sustenta isso: três Pix que **chegam juntos**, mas
foram liquidados às 09:30, 14:30 e 15:30, produzem **três janelas distintas**. Com processing time
os três cairiam na mesma.

**O estado é em memória e some no reinício, e isso é aceito.** O log do Kafka é a fonte da verdade:
reconstruir custa um replay, e o replay dá o mesmo resultado — justamente porque é event time.
Persistir o estado da janela seria otimização de tempo de recuperação, não de correção. É o segundo
item do desafio opcional, e o trade-off que ele pede para comparar é este.

---

## Como observar o resultado

O agregador loga a janela atualizada a cada evento, com partição e offset, para que se possa
conferir na mão qual mensagem entrou em qual janela:

```
17:42:10 INFO  AgregadorListener : pix agregado  transacao=pix-001  liquidadoEm=2026-08-23T14:10:00Z
                                   ->  janela [2026-08-23T14:00:00Z, 2026-08-23T15:00:00Z) | R$ 100.00 | 1 Pix
                                   (particao=1 offset=0)
```

---

## Resumo

| Decisão | Escolha |
|---|---|
| Pergunta | quanto foi liquidado em Pix por hora, em reais e em quantidade |
| Relógio | **event time** (`liquidadoEm`) |
| Janela | 1 hora, alinhada em UTC, fim exclusivo |
| Grupo | `agregador-pix-por-hora` — próprio, distinto de `tarifacao` |
| Deduplicação | por `eventoId` — Pix único = uma soma, mesmo se reenentregue |
| Retardatário | somado na janela dele; janela sem fechamento |
| Reprocessamento | resultado idêntico |
| Estado | em memória; o log é a fonte da verdade |
