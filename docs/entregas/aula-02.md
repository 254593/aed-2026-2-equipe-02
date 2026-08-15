# Folha de rosto — Aula 02

**Equipe 02 · AED · Turma ASDO 11.1 · PUC Minas / IEC**

---

## O que foi feito nesta etapa

| Artefato | Responsável |
|---|---|
| ADR-002: decisão do domínio (tarifação de Pix) | equipe |
| `servico-tarifacao` — consumidor Kafka com idempotência | Allainn Christiam |
| `servico-pix` — publisher com envelope CloudEvents 1.0 | Amanda Bouzan |
| Testes de robustez do listener no consumidor (T5, T6, T12) | Alexsander da Silva |
| As quatro saídas da política de tarifação do ADR (T7–T11) | Allainn Christiam |
| Testes automatizados do publisher — validação e contrato (T2–T8) | Alexsander da Silva |
| Infraestrutura Docker Compose (Kafka, Postgres, Kafka UI) | Allainn Christiam |

---

## Onde está cada coisa

| O quê | Caminho |
|---|---|
| Decisão do domínio | [docs/adr/ADR-002-dominio-do-projeto.md](../adr/ADR-002-dominio-do-projeto.md) |
| Registro de uso de IA | [docs/IA.md](../IA.md) |
| Consumidor (regra de tarifação + idempotência) | [servico-tarifacao/](../../servico-tarifacao/) |
| Publisher (eventos CloudEvents) | [servico-pix/](../../servico-pix/) |
| Infraestrutura | [docker-compose.yml](../../docker-compose.yml) |
| Como rodar (passo a passo) | [README.md](../../README.md) |

---

## Como rodar

### Infraestrutura

```bash
docker compose up -d
```

### Consumidor

```bash
mvn -f servico-tarifacao/pom.xml spring-boot:run
```

### Publisher

```bash
mvn -f servico-pix/pom.xml spring-boot:run
```

### Testes automatizados — consumidor (sem Docker, sem servico-pix)

```bash
mvn -f servico-tarifacao/pom.xml test
```

### Testes automatizados — publisher

```bash
mvn -f servico-pix/pom.xml test
```

---

## Testes automatizados — o que cada um cobre

O arquivo de testes é
[`servico-tarifacao/src/test/java/br/pucminas/aed/tarifacao/IdempotenciaTest.java`](../../servico-tarifacao/src/test/java/br/pucminas/aed/tarifacao/IdempotenciaTest.java).
Roda com Kafka embutido e H2 — sem Docker e sem o `servico-pix`.
As mensagens são publicadas como JSON cru para exercitar o contrato do fio, não a classe Java.

### Idempotência e contrato (Allainn Christiam)

| # | Método | O que valida |
|---|---|---|
| 1 | `pixDentroDaFranquiaSaiIsento` | Pix dentro da franquia é registrado com valor 0,00 e situação `ISENTO_FRANQUIA` |
| 2 | `reentregaNaoDuplicaOEfeito` | O mesmo evento entregue 3× gera efeito 1× — o item obrigatório do B.3 |
| 3 | `pixAcimaDaFranquiaETarifado` | O Pix seguinte ao fim da franquia é cobrado |
| 4 | `consumidorTolerante` | Campos que o consumidor não declara são ignorados sem erro |

### Robustez do listener (Alexsander da Silva)

| # | Método | O que valida |
|---|---|---|
| 5 | `ceIdAusenteUsaFallbackDoCorpo` | Sem o header `ce_id` o listener cai no `eventoId` do corpo, registra `WARN` e ainda deduplica na reentrega |
| 6 | `deduplicacaoUsaEventoIdNaoPixId` | Dois eventos com `eventoId` distintos e mesmo `pixId` geram dois efeitos — a chave de deduplicação é a identidade do fato, não a da entidade |
| 12 | `competenciaEIsoladaPorMes` | A franquia de agosto não contamina setembro: a competência vem do `ocorridoEm` do evento, nunca do relógio |

### As quatro saídas da política do ADR-002 (Allainn Christiam)

Acrescentados em 15/08, ao confrontar o consumidor com o ADR. O ponto de decisão que o ADR declara
— *isentar, tarifar por faixa de valor, não cobrar por teto atingido*, mais a regra de que empresa
sem contrato vigente não é cobrada — só estava implementado pela metade.

| # | Método | O que valida |
|---|---|---|
| 7 | `clienteSemContratoNaoECobrado` | Cliente sem contrato vira linha `SEM_CONTRATO` com valor 0,00 e **não consome franquia**. Substitui um teste anterior que exigia o oposto (plano padrão de R$ 1,90) e contradizia o ADR — ver [IA.md](../IA.md), interação 6 |
| 8 | `contratoEncerradoDeixaDeSerCobrado` | O mesmo cliente é cobrado em julho, quando havia contrato, e sai `SEM_CONTRATO` em agosto, quando não há |
| 9 | `ofertaEBuscadaPelaCompetenciaDoEvento` | Troca de plano: um evento de julho reprocessado hoje reencontra o plano **de julho**. É o que torna o fechamento mensal reproduzível |
| 10 | `tarifaVemDaFaixaDoValor` | Acima da franquia, o valor do Pix escolhe a faixa: R$ 1,90 / R$ 3,50 / R$ 7,00 |
| 11 | `tetoMensalInterrompeACobranca` | Atingido o teto de gasto do mês, os Pix seguintes saem `TETO_ATINGIDO` com valor 0,00 |

---

## Testes do publisher (`servico-pix`)

Arquivos:
- [`servico-pix/src/test/.../KafkaConfigTest.java`](../../servico-pix/src/test/java/br/pucminas/aed/pix/KafkaConfigTest.java)
- [`servico-pix/src/test/.../service/PixServiceTest.java`](../../servico-pix/src/test/java/br/pucminas/aed/pix/service/PixServiceTest.java)
- [`servico-pix/src/test/.../controller/PixControllerTest.java`](../../servico-pix/src/test/java/br/pucminas/aed/pix/controller/PixControllerTest.java)

### Testes originais (Amanda Bouzan)

| Arquivo | Método | O que valida |
|---|---|---|
| `KafkaConfigTest` | `serializaDataEmIso8601ENaoEmEpoch` | `ObjectMapper` serializa `ocorridoEm` como ISO-8601, nunca como epoch |
| `KafkaConfigTest` | `declaraTopicoComTresParticoes` | Tópico criado com 3 partições |
| `PixServiceTest` | `publicaContratoEsperadoPeloConsumidor` | Tópico correto, chave de partição = `clienteId`, os 5 headers `ce_*` obrigatórios, `ce_id` = `eventoId` do evento |
| `PixControllerTest` | `responde202QuandoPublicacaoEConfiadaAoKafka` | Controller retorna 202 quando a publicação é delegada ao Kafka |

### Testes adicionais (Alexsander da Silva)

| Arquivo | Método | O que valida |
|---|---|---|
| `PixServiceTest` | `lancaExcecaoQuandoRealizacaoENula` | `realizar(null)` lança `IllegalArgumentException` antes de qualquer publicação |
| `PixServiceTest` | `lancaExcecaoQuandoPixIdEmBranco` | `pixId` vazio lança exceção |
| `PixServiceTest` | `lancaExcecaoQuandoClienteIdEmBranco` | `clienteId` vazio lança exceção |
| `PixServiceTest` | `lancaExcecaoQuandoValorEZero` | `valor = 0` é rejeitado (só valor > 0 é aceito) |
| `PixServiceTest` | `lancaExcecaoQuandoValorENegativo` | `valor < 0` é rejeitado |
| `PixServiceTest` | `eventoIdUnicoACadaChamada` | Dois `realizar()` com mesmo payload geram `eventoId` distintos — cada fato tem identidade própria |
| `PixControllerTest` | `responde400QuandoEntradaEhInvalida` | `@ExceptionHandler` retorna `400 Bad Request` com corpo `{"erro": "..."}` quando a validação falha |
