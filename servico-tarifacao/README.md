# servico-tarifacao — o consumidor

Consome o fato **`PixRealizado`** e decide, para cada Pix, se ele cabe na franquia mensal da
empresa ou se deve ser tarifado.

Responsável: **Allainn Christiam (254337)**.

> **Se você está escrevendo o `servico-pix`, leia a seção
> [O contrato que o publicador precisa respeitar](#o-contrato-que-o-publicador-precisa-respeitar).**
> É a única parte deste documento que te obriga a alguma coisa. O resto é contexto.

---

## A regra de negócio

A especificação completa é [`docs/regra-de-tarifacao.md`](../docs/regra-de-tarifacao.md); a decisão
de domínio que a motiva está no [ADR-002](../docs/adr/ADR-002-dominio-do-projeto.md). Para cada Pix
a política decide entre **cinco** saídas — aprova, recusa e limita:

| Motivo | Valor | Consome franquia? | Quando |
|---|---|---|---|
| `SEM_CONTRATO` | `0.00` | não | não há oferta vigente na competência |
| `FRANQUIA` | `0.00` | **sim** | consumo do mês < franquia do plano |
| `FAIXA` | faixa | não | acima da franquia; o preço vem da faixa do valor |
| `TETO_PARCIAL` | o que cabe | não | a faixa não cabe no espaço restante do teto |
| `TETO_ATINGIDO` | `0.00` | não | o teto do mês já estava completo |

Quatro deles vêm da especificação. O `SEM_CONTRATO` vem do ADR-002 e cobre o caso que a
especificação não trata: não haver oferta vigente.

```
Pix chega
   ↓
já vi este eventoId?  ──sim──▶  descarta em silêncio, não faz nada
   ↓ não
qual a competência?  →  mês do liquidadoEm DO EVENTO (não de now())
   ↓
há oferta vigente NESSA competência?  ──não──▶  SEM_CONTRATO, 0.00
   ↓ sim
franquia consumida < pix_gratuitos_mes ?  ──sim──▶  FRANQUIA, 0.00
   ↓ não
tarifa = a faixa que cobre o valor do Pix
   ↓
já tarifado >= teto ?  ──sim──▶  TETO_ATINGIDO, 0.00
   ↓ não
já tarifado + tarifa > teto ?  ──sim──▶  TETO_PARCIAL, (teto − já tarifado)
   ↓ não
FAIXA, tarifa integral
   ↓
grava a linha em `tarifa`  (mesma transação do registro da deduplicação)
   ↓
commit
   ↓
ack do offset
```

**A ordem das perguntas não é arbitrária.** A franquia é sempre consumida primeiro, antes de
qualquer verificação de teto: uma empresa de alto volume termina o mês com as 10 de 10 isenções
usadas independentemente do teto, e é isso que mantém o relatório de fechamento fiel. E o teto só é
consultado depois de calcular a tarifa, porque o último passo precisa saber quanto caberia cobrar.

### O estouro do teto é cobrado parcialmente

Esta é a parte que mais engana. Quando a tarifa não cabe no espaço restante, cobra-se **o que
cabe** — não a tarifa inteira, e não zero:

```
já tarifado = R$ 1.995,00   (teto R$ 2.000,00)
Pix de R$ 6.000,00 → faixa = R$ 10,00
   1.995 + 10 = 2.005 > 2.000  →  cobra R$ 5,00, motivo TETO_PARCIAL
```

Sem esse passo o mês fecharia em R$ 2.005,00, acima do teto contratado. É o que sustenta a
invariante `valorTarifadoNaCompetencia ≤ teto`.

A alternativa — não cobrar nada quando não cabe — deixaria o acumulado abaixo do teto para sempre,
e um Pix barato posterior voltaria a ser cobrado; honrar "depois do teto, nada é cobrado" exigiria
um sinalizador separado do acumulador. Com a cobrança parcial, "teto atingido" é derivável do
próprio acumulado, sem nenhum estado à parte.

**O acumulado é monotônico: o teto não reabre.** Estorno é ajuste de fatura, em acumulador
próprio, e nunca decrementa o que já foi cobrado. É por isso que a coluna `valor` tem
`CHECK (valor >= 0)` no `schema.sql`: uma única linha negativa faria o `SUM` decrementar e o teto
reabrir em silêncio, recobrando Pix que já haviam saído como `TETO_ATINGIDO`. O teste 16 protege
essa constraint.

### Por que o motivo é uma coluna, e não só o valor

O motivo não é log: é dado do domínio. Três das cinco saídas valem `0.00` e significam coisas
diferentes — isento por benefício contratual e gratuito por limite atingido são duas gratuidades
com significados comerciais opostos. É o motivo que identifica **qual cobrança bateu o teto** (a
única linha `TETO_PARCIAL` da competência) e permite responder "por que esta empresa pagou este
valor" sem recalcular nada.

### Só o Pix isento consome franquia

Invariante da especificação: `unidadesFranquiaConsumidas ≤ franquia do plano`. Um Pix tarifado não
gasta unidade de franquia — ele existe justamente porque não havia mais nenhuma. A decisão não muda
por isso (uma vez atingido o limite, o contador para nele, e o limite nunca é menor que ele mesmo),
mas o acumulado fica correto para o fechamento, que o lê para dizer quantas isenções o contrato de
fato concedeu.

### Vocabulário: um só, do documento ao banco

A especificação, o ADR, o contrato do fio, as classes Java e as colunas do banco usam os **mesmos
nomes**. Não há tradução em lugar nenhum:

| Conceito | No JSON e no Java | No banco |
|---|---|---|
| a empresa tarifada | `idEmpresa` — também a chave de partição | `id_empresa` |
| a transação Pix | `idTransacaoPix` | `id_transacao_pix` |
| quando o Pix liquidou | `liquidadoEm` — de onde sai a competência | `liquidado_em` |

Uma versão anterior deste serviço usava `clienteId`, `pixId` e `ocorridoEm`, e a divergência com a
especificação foi mantida por um dia sob o argumento de que renomear quebraria os dois lados sem
mudar regra nenhuma. O argumento estava certo sobre o custo e errado sobre o benefício: duas
grafias para a mesma coisa é o erro que o enunciado lista como fácil de evitar, e quem lê a spec ao
lado do código paga esse imposto em toda leitura. Ver [IA.md](../docs/IA.md), interação 8.

**O nome da classe e o do tópico não acompanharam**: seguem `PixRealizadoEvent` e
`pagamentos.pix.realizado.v1`. A especificação os chama de `PixLiquidado` e `pix.liquidado`, e
alinhá-los também invalidaria os offsets do grupo e todos os comandos colados neste README — custo
que não se paga na véspera da entrega. Fica anotado como dívida.

### Não existe plano padrão

Empresa sem oferta vigente na competência **não é cobrada**. O Pix vira linha — o fato aconteceu e
precisa aparecer na auditoria — com situação `SEM_CONTRATO` e valor zero.

Não há tabela de fallback, e a razão está no ADR-002: cobrar sem contrato vigente é cobrança
indevida, com exposição a devolução em dobro (CDC, art. 42, parágrafo único). Um plano padrão
"para não travar a partição" transformaria um erro de cadastro numa cobrança à empresa.

### A oferta tem vigência

A busca é pela oferta vigente **na competência do evento**, nunca "a atual". Um replay feito em
outubro precisa reencontrar o contrato que vigia em agosto e chegar ao mesmo valor de antes — é o
que torna o fechamento mensal reproduzível, o quarto critério do ADR.

### A tabela de faixas do Plano PJ

Limite inferior inclusivo, superior **exclusivo**. Não há intervalo sem faixa, e nenhum valor
pertence a duas:

| Faixa | Tarifa |
|---|---|
| valor < R$ 500,00 | R$ 0,50 |
| R$ 500,00 ≤ valor < R$ 1.000,00 | R$ 1,00 |
| R$ 1.000,00 ≤ valor < R$ 5.000,00 | R$ 5,00 |
| valor ≥ R$ 5.000,00 | R$ 10,00 |

A fronteira exclusiva **muda dinheiro**, e nos valores redondos, que são os mais comuns numa
transferência: um Pix de exatamente R$ 500,00 paga R$ 1,00, não R$ 0,50. Por isso a coluna se chama
`valor_abaixo_de` e não `valor_ate` — "até" se lê como inclusivo, e foi assim que a primeira versão
desta tabela errou.

### Empresas de exemplo (dados fictícios, em `schema.sql`)

| id_empresa | vigência | grátis/mês | faixas | teto |
|---|---|---|---|---|
| `emp-0001` | 2026-01 → aberta | 10 | **Plano PJ** (as quatro acima) | 2.000,00 |
| `emp-0002` | 2026-01 → aberta | 2 | Plano PJ | — |
| `emp-0003` | 2026-01 → aberta | 0 | única: 0,99 | — |
| `emp-0004` | 2026-01 → 2026-07 | 2 | única: 4,90 | — |
| `emp-0004` | 2026-08 → aberta | 10 | única: 2,50 | — |
| `emp-0005` | 2026-01 → **2026-07** | 5 | única: 1,90 | — |
| `emp-0006` | 2026-01 → aberta | 0 | única: 10,00 | **25,00** |

`emp-0001` é o Plano PJ tal como a especificação o define. `emp-0002` tem a mesma tabela de faixas
com franquia curta, para exercitar o fim da franquia sem publicar onze eventos. `emp-0004`
demonstra troca de plano; `emp-0005`, contrato encerrado sem sucessora; `emp-0006` tem teto baixo
de propósito, para exercitar `TETO_PARCIAL` e `TETO_ATINGIDO` em quatro Pix em vez de duzentos.
Qualquer outro `idEmpresa` cai em `SEM_CONTRATO`.

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

### 2. A chave de partição: `idEmpresa`

**Esta é a exigência que não pode ser negociada por conveniência.**

```java
new ProducerRecord<>(topico, evento.getClienteId(), evento);
//                           ^^^^^^^^^^^^^^^^^^^^^ nem idTransacaoPix, nem eventoId
```

Por quê: decidir se um Pix é isento é um *read-then-write* — o consumidor lê quantos Pix o
cliente já fez no mês, decide, e só então grava. Se dois Pix da mesma empresa caírem em
**partições diferentes**, eles são processados em paralelo por consumidores distintos do grupo;
os dois leem "4 usados" contra uma franquia de 5 e os dois saem isentos. A franquia estoura, e o
bug só aparece sob concorrência.

Com `idEmpresa` como chave, todos os Pix de uma empresa caem na mesma partição, onde a ordem é
total e o processamento é serial. Não há lock no banco justamente porque a chave já garante isso.

### 3. Os cabeçalhos — CloudEvents 1.0 em modo binário

Cinco cabeçalhos, todos como `byte[]` em UTF-8:

| Cabeçalho | Valor | Obrigatório |
|---|---|---|
| `ce_specversion` | `1.0` | sim (spec) |
| `ce_id` | o `eventoId` — **é a chave de deduplicação** | sim (spec) |
| `ce_source` | `/pagamentos/servico-pix` | sim (spec) |
| `ce_type` | `pagamentos.pix.realizado.v1` | sim (spec) |
| `ce_time` | o `liquidadoEm` em ISO-8601 | sim, para nós |

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
  "liquidadoEm":   "2026-08-14T13:00:00.000Z",
  "idTransacaoPix":        "pix-001",
  "idEmpresa":    "emp-0001",
  "valor":        150.00,
  "chavePix":     "fulano@exemplo.com",
  "tipoChave":    "EMAIL",
  "bancoDestino": "999",
  "endToEndId":   "E99900000202608141300000000001",
  "pagadorNome":  "Empresa Ficticia"
}
```

| Campo | O consumidor usa? | Observação |
|---|---|---|
| `eventoId` | sim (rede de segurança) | identidade do **fato**; distinto do `idTransacaoPix` |
| `liquidadoEm` | **sim** | define a competência. ISO-8601, **nunca epoch** |
| `idTransacaoPix` | sim | identidade da **transação**; vai para o registro da tarifa |
| `idEmpresa` | **sim** | de quem é a franquia, e a chave de partição |
| `valor` | **sim** | escolhe a faixa de tarifa do plano |
| `chavePix`, `tipoChave`, `bancoDestino`, `endToEndId`, `pagadorNome` | **não** | ignorados de propósito |

Os cinco últimos existem **para serem ignorados**: é a demonstração do consumidor tolerante que o
enunciado pede no B.3. `PixRealizadoEvent` declara menos campos do que o `servico-pix` publica, e
`@JsonIgnoreProperties(ignoreUnknown = true)` faz o resto. Pode acrescentar campos novos à
vontade — o consumidor não quebra.

**Os quatro primeiros campos são obrigatórios.** `eventoId`, `liquidadoEm`, `idTransacaoPix` e `idEmpresa`
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
- [ ] chave de partição = `idEmpresa`
- [ ] os cinco cabeçalhos `ce_*`
- [ ] `ce_id` igual ao `eventoId` do corpo
- [ ] `liquidadoEm` em ISO-8601 no corpo (ObjectMapper configurado)
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

**Por que a chave de deduplicação é o `eventoId`, e não o `idTransacaoPix`:** dois eventos *diferentes*
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
mvn test                # 16 testes, sem Docker e sem o servico-pix
```

O teste publica **JSON cru** no tópico, não objeto Java — assim ele exercita o contrato do fio,
como faria um produtor escrito em outra linguagem. Se o `servico-pix` mudar o formato, é este
teste que precisa quebrar.

Nenhum `Instant.now()` em lugar nenhum: cada evento carrega um `liquidadoEm` fixo, e é dele que sai
a competência. Um teste que dependesse do relógio falharia na virada do mês e, pior, esconderia
justamente o bug que os testes 8 e 9 existem para pegar.

| Teste | O que prova |
|---|---|
| 1 | Pix dentro da franquia é registrado como isento |
| 2 | **o mesmo evento entregue 3x produz efeito uma só vez** — o item obrigatório do B.3 |
| 3 | o Pix seguinte ao fim da franquia é tarifado |
| 4 | campos que o consumidor não declara são ignorados |
| 5 | mensagem sem `ce_id` cai no `eventoId` do corpo e continua idempotente |
| 6 | mesmo `idTransacaoPix` com `eventoId` distintos são dois fatos, não reentrega |
| 7 | `ce_id` divergente do corpo não colide na PK da tarifa nem trava a partição |
| 8 | empresa que nunca teve contrato não é cobrada, e não consome franquia |
| 9 | contrato encerrado: cobre em julho, `SEM_CONTRATO` em agosto |
| 10 | troca de plano: vale a oferta vigente na competência **do evento** |
| 11 | acima da franquia, o **valor** do Pix escolhe a faixa: 0,50 / 1,00 / 5,00 / 10,00 |
| 12 | **a fronteira da faixa é exclusiva**: R$ 500,00 paga R$ 1,00, não R$ 0,50 |
| 13 | **o estouro do teto é cobrado parcialmente** — o acumulado para exatamente no teto |
| 14 | só o Pix isento consome franquia; o tarifado não |
| 15 | a competência é isolada por mês: a franquia reinicia em setembro |
| 16 | **o banco recusa valor negativo em `tarifa`** — o teto não reabre em silêncio |

## Configuração relevante

| Propriedade | Valor | Por quê |
|---|---|---|
| `spring.kafka.consumer.enable-auto-commit` | `false` | quem confirma o offset é o nosso código |
| `spring.kafka.listener.ack-mode` | `manual_immediate` | o ack é explícito, depois do commit |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | grupo novo lê o histórico do tópico |
| `spring.json.use.type.headers` | `false` | a classe a usar é a **nossa**, não a do produtor |
| `metadata.max.age.ms` | `5000` | sem isso são 5 min de tela parada se o consumidor subir antes do tópico |
