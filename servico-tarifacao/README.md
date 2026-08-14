# servico-tarifacao — o consumidor

Consome o fato **`PixRealizado`** e decide, para cada Pix, se ele cabe na franquia mensal do
cliente ou se deve ser tarifado.

Responsável: **Allainn Christiam (1664926)**.

> **Se você está escrevendo o `servico-pix`, leia a seção
> [O contrato que o publicador precisa respeitar](#o-contrato-que-o-publicador-precisa-respeitar).**
> É a única parte deste documento que te obriga a alguma coisa. O resto é contexto.

---

## A regra de negócio

Cada cliente tem um **plano** (tabela `oferta`) que diz duas coisas: quantos Pix ele faz de graça
por mês, e quanto custa cada Pix a partir daí.

```
Pix chega
   ↓
já vi este eventoId?  ──sim──▶  descarta em silêncio, não faz nada
   ↓ não
qual a competência?  →  mês do ocorridoEm DO EVENTO (não de now())
   ↓
quantos Pix este cliente já fez nesta competência?
   ↓
consumo < franquia ?  ──sim──▶  valor = 0.00   (isento, mas CONSOME a franquia)
   ↓ não
valor = valor_tarifa do plano
   ↓
grava a linha em `tarifa`  (mesma transação do registro da deduplicação)
   ↓
commit
   ↓
ack do offset
```

Exemplo com o cliente `cli-0001`, que tem franquia 5 e tarifa R$ 1,90:

| Pix | consumo antes | resultado |
|---|---|---|
| 1º ao 5º | 0, 1, 2, 3, 4 | `0.00` — isento |
| 6º | 5 | **`1.90`** |
| 7º em diante | 6, 7, ... | `1.90` cada |

Clientes de exemplo já cadastrados (dados fictícios, em `schema.sql`):

| cliente_id | pix_gratuitos_mes | valor_tarifa |
|---|---|---|
| `cli-0001` | 5 | 1.90 |
| `cli-0002` | 2 | 3.50 |
| `cli-0003` | 0 | 0.99 |

Cliente sem linha em `oferta` recebe o plano padrão do `TarifacaoRepository` (5 grátis, R$ 1,90)
em vez de derrubar o consumidor — um Pix de cliente desconhecido não pode travar a partição.

---

## O contrato que o publicador precisa respeitar

### 1. O tópico

```
pagamentos.pix.realizado.v1
```

Uma grafia só. O mesmo texto é o nome do tópico **e** o valor do cabeçalho `ce_type`. Duas
grafias para a mesma coisa é um dos erros que o enunciado lista como fáceis de evitar.

O tópico deve ter **3 partições**. Declare-o no `servico-pix` com um bean `NewTopic` — quem
publica é o dono do tópico; o consumidor não o declara. (O `docker-compose.yml` tem
`KAFKA_NUM_PARTITIONS: 3` como rede de segurança, para o caso de o tópico nascer por
auto-criação.)

### 2. A chave de partição: `clienteId`

**Esta é a exigência que não pode ser negociada por conveniência.**

```java
new ProducerRecord<>(topico, evento.getClienteId(), evento);
//                           ^^^^^^^^^^^^^^^^^^^^^ nem pixId, nem eventoId
```

Por quê: decidir se um Pix é isento é um *read-then-write* — o consumidor lê quantos Pix o
cliente já fez no mês, decide, e só então grava. Se dois Pix do mesmo cliente caírem em
**partições diferentes**, eles são processados em paralelo por consumidores distintos do grupo;
os dois leem "4 usados" contra uma franquia de 5 e os dois saem isentos. A franquia estoura, e o
bug só aparece sob concorrência.

Com `clienteId` como chave, todos os Pix de um cliente caem na mesma partição, onde a ordem é
total e o processamento é serial. Não há lock no banco justamente porque a chave já garante isso.

### 3. Os cabeçalhos — CloudEvents 1.0 em modo binário

Cinco cabeçalhos, todos como `byte[]` em UTF-8:

| Cabeçalho | Valor | Obrigatório |
|---|---|---|
| `ce_specversion` | `1.0` | sim (spec) |
| `ce_id` | o `eventoId` — **é a chave de deduplicação** | sim (spec) |
| `ce_source` | `/pagamentos/servico-pix` | sim (spec) |
| `ce_type` | `pagamentos.pix.realizado.v1` | sim (spec) |
| `ce_time` | o `ocorridoEm` em ISO-8601 | sim, para nós |

```java
registro.headers().add("ce_specversion", "1.0".getBytes(UTF_8));
registro.headers().add("ce_id",          evento.getEventoId().getBytes(UTF_8));
registro.headers().add("ce_source",      "/pagamentos/servico-pix".getBytes(UTF_8));
registro.headers().add("ce_type",        "pagamentos.pix.realizado.v1".getBytes(UTF_8));
registro.headers().add("ce_time",        evento.getOcorridoEm().toString().getBytes(UTF_8));
```

**O `ce_id` é o que sustenta a idempotência.** O consumidor lê a identidade do fato do cabeçalho,
não do corpo — é para isso que existe o modo binário: deduplicar sem desserializar o payload.

Se o `ce_id` faltar, o consumidor registra um `WARN` nomeando partição e offset e cai para o
`eventoId` do corpo. É rede de segurança, não permissão: sem o cabeçalho, a mensagem está fora
do contrato.

### 4. O corpo — JSON

```json
{
  "eventoId":     "3f2b8c10-9a4e-4f77-8f31-6b0a2d5e7c11",
  "ocorridoEm":   "2026-08-14T13:00:00.000Z",
  "pixId":        "pix-001",
  "clienteId":    "cli-0001",
  "valor":        150.00,
  "chavePix":     "fulano@exemplo.com",
  "tipoChave":    "EMAIL",
  "bancoDestino": "999",
  "endToEndId":   "E99900000202608141300000000001",
  "pagadorNome":  "Cliente Ficticio"
}
```

| Campo | O consumidor usa? | Observação |
|---|---|---|
| `eventoId` | sim (rede de segurança) | identidade do **fato**; distinto do `pixId` |
| `ocorridoEm` | **sim** | define a competência. ISO-8601, **nunca epoch** |
| `pixId` | sim | identidade da **transação**; vai para o registro da tarifa |
| `clienteId` | **sim** | de quem é a franquia |
| `valor` | grava, não calcula | a tarifa é fixa por plano, não percentual |
| `chavePix`, `tipoChave`, `bancoDestino`, `endToEndId`, `pagadorNome` | **não** | ignorados de propósito |

Os cinco últimos existem **para serem ignorados**: é a demonstração do consumidor tolerante que o
enunciado pede no B.3. `PixRealizadoEvent` declara menos campos do que o `servico-pix` publica, e
`@JsonIgnoreProperties(ignoreUnknown = true)` faz o resto. Pode acrescentar campos novos à
vontade — o consumidor não quebra.

**Os quatro primeiros campos são obrigatórios.** `eventoId`, `ocorridoEm`, `pixId` e `clienteId`
passam por `Objects.requireNonNull` no construtor; faltando qualquer um, a desserialização falha.

### 5. Datas em ISO-8601, nunca epoch

O `JsonSerializer` do spring-kafka, no padrão, serializa `Instant` como número de epoch
(`1786718590.512000000`). O consumidor Java leria de volta sem reclamar e o problema só apareceria
ao abrir a mensagem na Kafka UI.

Injete um `ObjectMapper` com `JavaTimeModule` e `WRITE_DATES_AS_TIMESTAMPS` desabilitado, como o
`PedidoConfig` do demo faz. **Sem isso, o item 4 do checklist do professor falha.**

### 6. A API HTTP responde 202

`POST /pix/realizados` → **202 Accepted**, não 200. No instante da resposta o Pix ainda não foi
tarifado — o consumidor pode nem estar rodando. 202 é honesto sobre a consistência eventual.

### Checklist rápido do publicador

- [ ] tópico `pagamentos.pix.realizado.v1`, 3 partições, declarado via `NewTopic`
- [ ] chave de partição = `clienteId`
- [ ] os cinco cabeçalhos `ce_*`
- [ ] `ce_id` igual ao `eventoId` do corpo
- [ ] `ocorridoEm` em ISO-8601 no corpo (ObjectMapper configurado)
- [ ] os quatro campos obrigatórios sempre presentes
- [ ] retorno do `send()` tratado numa classe nomeada
- [ ] endpoint HTTP responde 202

---

## Como o consumidor está construído

### Estrutura

```
src/main/java/br/pucminas/aed/tarifacao/
├── TarifacaoApplication.java          raiz
├── controller/TarifacaoListener.java  adaptador de entrada
├── domain/PixRealizadoEvent.java      o fato, como ESTE serviço o enxerga
├── domain/OfertaVO.java               o plano do cliente
└── service/
    ├── TarifacaoService.java          a regra + a transação
    └── TarifacaoRepository.java       as três tabelas
```

Quatro pacotes, nenhum a mais. `domain` não importa nada de Kafka nem de Spring.

### As duas linhas que são o assunto da aula

```java
// TarifacaoListener
tarifacaoService.processar(eventoId, registro.value());   // 1. transação commita aqui dentro
ack.acknowledge();                                        // 2. só então confirma o offset
```

Invertendo a ordem, uma falha no commit depois do ack perderia o evento: o offset teria avançado
e o efeito não teria acontecido. Confirmar depois de processar é o que caracteriza
**at-least-once** — e é por isso que a idempotência é obrigatória.

`@Transactional` está no `TarifacaoService`, nunca no listener. Se a transação começasse no
listener, o `ack` estaria dentro dela e o raciocínio acima cairia.

### As três tabelas

| Tabela | Papel |
|---|---|
| `evento_processado` | memória do que já foi visto. PK = `evento_id`. Sustenta a idempotência |
| `oferta` | o plano do cliente — a regra que decide isenção |
| `tarifa` | o efeito de negócio. Uma linha por Pix, com `valor = 0.00` quando isento |

A deduplicação e o efeito acontecem na **mesma transação**. Em transações separadas existe uma
janela em que o processo morre entre as duas e o evento é reprocessado — exatamente o que se
queria evitar.

**Por que a chave de deduplicação é o `eventoId`, e não o `pixId`:** dois eventos *diferentes*
podem falar do mesmo Pix — um `PixRealizado` hoje e um `PixDevolvido` amanhã. Deduplicar pela
entidade descartaria o segundo como se fosse repetição do primeiro.

**Por que o Pix isento também vira linha:** ele consome franquia. Guardando isento e tarifado na
mesma tabela, a contagem sai da própria `tarifa` — sem contador em coluna separada, que seria um
`UPDATE` em delta, o tipo de operação que a reentrega quebra. De brinde, o extrato mensal do
cliente fica completo e reprocessável.

**Retenção:** `evento_processado` cresce para sempre e precisa de expurgo, com janela **maior**
que a retenção do tópico. Se for menor, um replay de mensagem antiga encontra a tabela limpa e
passa pela deduplicação como evento novo, cobrando o cliente duas vezes. O `schema.sql` documenta
o `DELETE`.

### Competência: do evento, não do relógio

```java
YearMonth.from(evento.getOcorridoEm().atZone(ZoneOffset.UTC)).toString()   // "2026-08"
```

Um replay do tópico feito em outubro tem que continuar tarifando contra agosto. Usar `now()` faria
o reprocessamento produzir resultado diferente do original, e o extrato deixaria de ser
reproduzível. O fuso é fixado em UTC de propósito: se dependesse do fuso da máquina, um Pix da
virada do mês cairia em competências diferentes conforme onde o consumidor rodasse.

---

## Rodar

```bash
mvn spring-boot:run     # precisa do docker compose up -d na raiz
mvn test                # 4 testes, sem Docker e sem o servico-pix
```

O teste publica **JSON cru** no tópico, não objeto Java — assim ele exercita o contrato do fio,
como faria um produtor escrito em outra linguagem. Se o `servico-pix` mudar o formato, é este
teste que precisa quebrar.

| Teste | O que prova |
|---|---|
| 1 | Pix dentro da franquia é registrado como isento |
| 2 | **o mesmo evento entregue 3x tarifa uma só vez** — o item obrigatório do B.3 |
| 3 | o Pix seguinte ao fim da franquia é tarifado |
| 4 | campos que o consumidor não declara são ignorados |

## Configuração relevante

| Propriedade | Valor | Por quê |
|---|---|---|
| `spring.kafka.consumer.enable-auto-commit` | `false` | quem confirma o offset é o nosso código |
| `spring.kafka.listener.ack-mode` | `manual_immediate` | o ack é explícito, depois do commit |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | grupo novo lê o histórico do tópico |
| `spring.json.use.type.headers` | `false` | a classe a usar é a **nossa**, não a do produtor |
| `metadata.max.age.ms` | `5000` | sem isso são 5 min de tela parada se o consumidor subir antes do tópico |
