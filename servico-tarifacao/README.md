# servico-tarifacao — o consumidor

Consome o fato **`PixRealizado`** e decide, para cada Pix, se ele cabe na franquia mensal do
cliente ou se deve ser tarifado.

Responsável: **Allainn Christiam (1664926)**.

> **Se você está escrevendo o `servico-pix`, leia a seção
> [O contrato que o publicador precisa respeitar](#o-contrato-que-o-publicador-precisa-respeitar).**
> É a única parte deste documento que te obriga a alguma coisa. O resto é contexto.

---

## A regra de negócio

É a **`PoliticaDeTarifacao`** do [ADR-002](../docs/adr/ADR-002-dominio-do-projeto.md). Para cada
Pix ela decide entre **quatro** saídas — aprova, recusa e limita:

| Situação | Valor | Consome franquia? | Quando |
|---|---|---|---|
| `SEM_CONTRATO` | `0.00` | não | não há oferta vigente na competência |
| `ISENTO_FRANQUIA` | `0.00` | **sim** | consumo do mês < franquia do plano |
| `TARIFADO` | faixa | **sim** | acima da franquia; o preço vem da faixa do valor |
| `TETO_ATINGIDO` | `0.00` | não | o gasto do mês já atingiu o teto do plano |

```
Pix chega
   ↓
já vi este eventoId?  ──sim──▶  descarta em silêncio, não faz nada
   ↓ não
qual a competência?  →  mês do ocorridoEm DO EVENTO (não de now())
   ↓
há oferta vigente NESSA competência?  ──não──▶  SEM_CONTRATO, 0.00
   ↓ sim
franquia consumida < pix_gratuitos_mes ?  ──sim──▶  ISENTO_FRANQUIA, 0.00
   ↓ não
plano tem teto e já foi atingido ?  ──sim──▶  TETO_ATINGIDO, 0.00
   ↓ não
TARIFADO, valor = a faixa que cobre o valor do Pix
   ↓
grava a linha em `tarifa`  (mesma transação do registro da deduplicação)
   ↓
commit
   ↓
ack do offset
```

**A ordem das perguntas não é arbitrária.** Sem contrato não se pergunta pela franquia, e o teto
só faz sentido depois de saber que haveria cobrança — inverter as duas últimas faria um Pix isento
"atingir o teto" e sair com a situação errada no extrato.

### Por que a situação é uma coluna, e não só o valor

Três das quatro saídas valem `0.00` e significam coisas diferentes. Sem a coluna `situacao`, um
Pix isento por franquia, um de empresa sem contrato e um acima do teto seriam indistinguíveis no
extrato — justamente onde a auditoria e a contestação comercial precisam de clareza. E a contagem
de franquia depende disso: `SEM_CONTRATO` e `TETO_ATINGIDO` não gastam cota.

### Não existe plano padrão

Cliente sem oferta vigente na competência **não é cobrado**. O Pix vira linha — o fato aconteceu e
precisa aparecer na auditoria — com situação `SEM_CONTRATO` e valor zero.

Não há tabela de fallback, e a razão está no ADR-002: cobrar sem contrato vigente é cobrança
indevida, com exposição a devolução em dobro (CDC, art. 42, parágrafo único). Um plano padrão
"para não travar a partição" transformaria um erro de cadastro numa cobrança ao cliente.

### A oferta tem vigência

A busca é pela oferta vigente **na competência do evento**, nunca "a atual". Um replay feito em
outubro precisa reencontrar o contrato que vigia em agosto e chegar ao mesmo valor de antes — é o
que torna o fechamento mensal reproduzível, o quarto critério do ADR.

### Clientes de exemplo (dados fictícios, em `schema.sql`)

| cliente_id | vigência | grátis/mês | faixas | teto |
|---|---|---|---|---|
| `cli-0001` | 2026-01 → aberta | 5 | ≤500: 1,90 · ≤5000: 3,50 · acima: 7,00 | — |
| `cli-0002` | 2026-01 → aberta | 2 | única: 3,50 | — |
| `cli-0003` | 2026-01 → aberta | 0 | única: 0,99 | — |
| `cli-0004` | 2026-01 → 2026-07 | 2 | única: 4,90 | — |
| `cli-0004` | 2026-08 → aberta | 10 | única: 2,50 | — |
| `cli-0005` | 2026-01 → **2026-07** | 5 | única: 1,90 | — |
| `cli-0006` | 2026-01 → aberta | 0 | única: 0,99 | **1,98** |

`cli-0004` demonstra troca de plano; `cli-0005`, contrato encerrado sem sucessora; `cli-0006`, o
teto mensal. Qualquer outro `clienteId` cai em `SEM_CONTRATO`.

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
| `clienteId` | **sim** | de quem é a franquia, e a chave de partição |
| `valor` | **sim** | escolhe a faixa de tarifa do plano |
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
├── TarifacaoApplication.java             raiz
├── controller/TarifacaoListener.java     adaptador de entrada
├── domain/
│   ├── PixRealizadoEvent.java            o fato, como ESTE serviço o enxerga
│   ├── OfertaVO.java                     o contrato vigente do cliente
│   ├── FaixaDeTarifaVO.java              até tanto de valor, custa tanto
│   ├── SituacaoDaTarifaVO.java           as quatro saídas da política
│   └── DecisaoDeTarifacaoVO.java         o que a política decidiu: situação + valor
└── service/
    ├── TarifacaoService.java             a política + a transação
    └── TarifacaoRepository.java          as quatro tabelas
```

Quatro pacotes, nenhum a mais. `domain` não importa nada de Kafka nem de Spring — só a biblioteca
padrão e as anotações de serialização.

Todo sufixo está na lista fechada da Seção 12. `SituacaoDaTarifaVO` é um enum, e leva `VO` porque
é exatamente isso: valor sem identidade própria, imutável, que só faz sentido junto da tarifa que
o carrega.

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

### As quatro tabelas

| Tabela | Papel |
|---|---|
| `evento_processado` | memória do que já foi visto. PK = `evento_id`. Sustenta a idempotência |
| `oferta` | o contrato vigente do cliente, com vigência por competência e teto mensal |
| `oferta_faixa` | o preço por faixa de valor daquele contrato |
| `tarifa` | o efeito de negócio. Uma linha por Pix, com `situacao` e `valor` |

A deduplicação e o efeito acontecem na **mesma transação**. Em transações separadas existe uma
janela em que o processo morre entre as duas e o evento é reprocessado — exatamente o que se
queria evitar.

**Por que a chave de deduplicação é o `eventoId`, e não o `pixId`:** dois eventos *diferentes*
podem falar do mesmo Pix — um `PixRealizado` hoje e um `PixDevolvido` amanhã. Deduplicar pela
entidade descartaria o segundo como se fosse repetição do primeiro.

**Por que todo Pix vira linha, cobrado ou não:** o isento consome franquia, e a contagem sai da
própria `tarifa` — sem contador em coluna separada, que seria um `UPDATE` em delta, o tipo de
operação que a reentrega quebra. Os não cobrados (`SEM_CONTRATO`, `TETO_ATINGIDO`) entram porque
são fatos que a auditoria precisa ver; a coluna `situacao` é o que os mantém fora da contagem de
franquia. De brinde, o extrato mensal do cliente fica completo e reprocessável.

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
mvn test                # 12 testes, sem Docker e sem o servico-pix
```

O teste publica **JSON cru** no tópico, não objeto Java — assim ele exercita o contrato do fio,
como faria um produtor escrito em outra linguagem. Se o `servico-pix` mudar o formato, é este
teste que precisa quebrar.

Nenhum `Instant.now()` em lugar nenhum: cada evento carrega um `ocorridoEm` fixo, e é dele que sai
a competência. Um teste que dependesse do relógio falharia na virada do mês e, pior, esconderia
justamente o bug que os testes 8 e 9 existem para pegar.

| Teste | O que prova |
|---|---|
| 1 | Pix dentro da franquia é registrado como isento |
| 2 | **o mesmo evento entregue 3x produz efeito uma só vez** — o item obrigatório do B.3 |
| 3 | o Pix seguinte ao fim da franquia é tarifado |
| 4 | campos que o consumidor não declara são ignorados |
| 5 | mensagem sem `ce_id` cai no `eventoId` do corpo e continua idempotente |
| 6 | mesmo `pixId` com `eventoId` distintos são dois fatos, não reentrega |
| 7 | cliente que nunca teve contrato não é cobrado, e não consome franquia |
| 8 | contrato encerrado: cobre em julho, `SEM_CONTRATO` em agosto |
| 9 | troca de plano: vale a oferta vigente na competência **do evento** |
| 10 | acima da franquia, o **valor** do Pix escolhe a faixa da tarifa |
| 11 | atingido o teto mensal, os Pix seguintes deixam de ser cobrados |
| 12 | a competência é isolada por mês: a franquia reinicia em setembro |

## Configuração relevante

| Propriedade | Valor | Por quê |
|---|---|---|
| `spring.kafka.consumer.enable-auto-commit` | `false` | quem confirma o offset é o nosso código |
| `spring.kafka.listener.ack-mode` | `manual_immediate` | o ack é explícito, depois do commit |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | grupo novo lê o histórico do tópico |
| `spring.json.use.type.headers` | `false` | a classe a usar é a **nossa**, não a do produtor |
| `metadata.max.age.ms` | `5000` | sem isso são 5 min de tela parada se o consumidor subir antes do tópico |
