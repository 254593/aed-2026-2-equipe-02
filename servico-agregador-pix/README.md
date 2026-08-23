# servico-agregador-pix — o segundo consumidor

Consome o **mesmo tópico** que a tarifação, num **grupo próprio**, e responde a uma pergunta sobre o
fluxo que nenhum evento isolado responderia:

> **Quanto foi liquidado em Pix por hora?** — em reais e em quantidade de transações.

As decisões (relógio, retardatário, reprocessamento) estão em
[docs/entregas/aula-03.md](../docs/entregas/aula-03.md). O contrato do evento que ele lê está em
[docs/contrato.md](../docs/contrato.md).

---

## Rodar

```bash
mvn -f servico-agregador-pix/pom.xml spring-boot:run
```

Pré-requisito: `docker compose up -d` na raiz. O `servico-pix` só é necessário para produzir
eventos novos — o agregador lê o tópico **desde o início**, então já encontra o que houver lá.

Não expõe porta HTTP: `web-application-type: none`. O resultado sai no log.

---

## Grupo próprio: os dois consumidores convivem

| Serviço | `group.id` | O que faz |
|---|---|---|
| `servico-tarifacao` | `tarifacao` | decide isenção, faixa e teto por competência |
| `servico-agregador-pix` | `agregador-pix-por-hora` | soma o liquidado por hora |

Grupos diferentes, mesmo tópico: **cada um tem o próprio ponteiro de leitura**, e nenhum consome a
mensagem do outro. É a propriedade do log que a aula 02 estabeleceu — ler não consome. Subindo os
dois ao mesmo tempo, os dois processam todos os eventos.

---

## A janela

**Uma hora, alinhada em UTC, com fim exclusivo:** `[14:00, 15:00)`. Um Pix liquidado às 14:35 cai na
janela das 14h; um liquidado às 15:00:00 em ponto cai na das 15h, nunca nas duas.

O alinhamento é por **tempo**, não pela hora em que o processo subiu. É isso que faz o mesmo evento
cair sempre na mesma janela, hoje ou num replay daqui a seis meses.

## O relógio: event time

A janela vem do `liquidadoEm` **do evento** — o instante em que o Pix liquidou no SPI —, não da hora
em que a mensagem chegou. Um Pix atrasado soma na janela dele, e não na janela de agora.

Consequência boa: **reprocessar o tópico do início produz exatamente os mesmos números.**

## O retardatário

É somado na janela a que pertence, sem reiniciar a contagem. O agregador mantém um **mapa de
janelas**, e não uma janela corrente:

```java
janelas.compute(janela.getInicio(), (chave, atual) ->
        (atual == null ? AgregacaoPorHoraVO.vazia(janela) : atual).somar(evento.getValor()));
```

Guardar só a janela mais recente pareceria bastar enquanto os eventos chegassem em ordem — e eles
não chegam: o tópico tem três partições e várias empresas publicam ao mesmo tempo. Com uma janela
só, cada troca de hora descartaria o acumulado.

Não há watermark: nenhum evento é descartado por chegar tarde. A contrapartida é que nenhum total é
definitivo. Para uma pergunta de fechamento contábil, é a troca certa.

---

## O que sai no log

Cada evento imprime a janela **já atualizada**, com partição e offset — o que permite conferir na
mão, pelo `kafka-console-consumer`, qual mensagem entrou em qual janela:

```
17:42:10 INFO  AgregadorListener  : pix agregado  transacao=pix-001  liquidadoEm=2026-08-23T14:10:00Z
                                    ->  janela [2026-08-23T14:00:00Z, 2026-08-23T15:00:00Z) | R$ 100.00 | 1 Pix
                                    (particao=1 offset=0)
17:42:11 INFO  AgregadorListener  : pix agregado  transacao=pix-002  liquidadoEm=2026-08-23T14:20:00Z
                                    ->  janela [2026-08-23T14:00:00Z, 2026-08-23T15:00:00Z) | R$ 300.50 | 2 Pix
```

Para reprocessar do zero e conferir que o resultado se repete:

```bash
docker exec e02-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9094 --delete --group agregador-pix-por-hora
```

(com o agregador parado; depois é só subir de novo)

---

## Estrutura

```
src/main/java/br/pucminas/aed/agregador/
├── AgregadorApplication.java            raiz
├── KafkaConfig.java                     raiz
├── controller/AgregadorListener.java    adaptador de entrada — sem estado
├── domain/
│   ├── PixRealizadoEvent.java           o fato, como ESTE serviço o enxerga
│   ├── JanelaDeHoraVO.java              a janela alinhada
│   └── AgregacaoPorHoraVO.java          o acumulado de uma janela
└── service/AgregadorService.java        o mapa de janelas e a soma
```

Quatro pacotes, os do padrão. O listener **não guarda estado**: o acumulado mora no service, porque
é ele quem decide. Estado no adaptador de entrada seria regra de negócio no listener.

## Consumidor tolerante

`PixRealizadoEvent` declara **4 dos 10 campos** que o `servico-pix` publica, e ignora os demais com
`@JsonIgnoreProperties(ignoreUnknown = true)`. É o que sustenta a regra **FORWARD** do
[contrato](../docs/contrato.md): o produtor pode acrescentar campos sem quebrar este consumidor.

## Desserialização — o ponto que já quebrou

O produtor publica com `setAddTypeInfo(false)`: **não há cabeçalho `__TypeId__`** na mensagem. O
consumidor precisa dizer sozinho qual classe usar:

```yaml
spring.json.use.type.headers: false
spring.json.value.default.type: br.pucminas.aed.agregador.domain.PixRealizadoEvent
```

Sem isso: `No type information in headers and no default type provided` — e como
`SerializationException` acontece **antes** do listener, o `DefaultErrorHandler` não consegue
tratá-la, a mensagem é reentregue para sempre e a partição trava. Por isso o
`ErrorHandlingDeserializer` envolve o `JsonDeserializer`: carga malformada custa um registro, não a
partição inteira.

---

## Testes

```bash
mvn -f servico-agregador-pix/pom.xml test
```

**12 testes**, sem Docker (Kafka embutido). Os de integração publicam **JSON cru**, como o
`servico-pix` publica — publicar objeto Java provaria que o serviço funciona com um produtor que
não existe.

| Teste | O que prova |
|---|---|
| 1 | desserializa o JSON do produtor real, sem cabeçalho de tipo |
| 2 | Pix da mesma hora somam na mesma janela |
| 3 | horas diferentes ficam em janelas separadas |
| 4 | **o retardatário soma na janela dele, sem reiniciar a contagem** |
| 5 | campos não declarados são ignorados |
| 6 | **usa event time**: três Pix que chegam juntos, liquidados em horas distintas, viram três janelas |
| `JanelaDeHoraVOTest` | alinhamento na hora cheia, fim exclusivo, cálculo determinístico |
| `AgregacaoPorHoraVOTest` | imutabilidade; valor nulo soma zero mas conta como Pix |
