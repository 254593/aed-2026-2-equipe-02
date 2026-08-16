# Folha de rosto — Aula 02

**Equipe 02 · AED · Turma ASDO 11.1 · PUC Minas / IEC**

---

## O que foi feito nesta etapa

| Artefato | Responsável |
|---|---|
| ADR-002: decisão do domínio (tarifação de Pix) | equipe |
| `servico-tarifacao` — consumidor Kafka com idempotência | Allainn Christiam |
| `servico-pix` — publisher com envelope CloudEvents 1.0 | Amanda Bouzan |
| Testes de robustez do listener no consumidor (T5, T6, T15) | Alexsander da Silva |
| As cinco saídas da política de tarifação (T8–T14) | Allainn Christiam |
| Regressão de identidade do evento encontrada em code review (T7) | Allainn Christiam |
| Testes automatizados do publisher — validação e contrato | Alexsander da Silva |
| Infraestrutura Docker Compose (Kafka, Postgres, Kafka UI) | Allainn Christiam |

---

## Onde está cada coisa

| O quê | Caminho |
|---|---|
| Decisão do domínio | [docs/adr/ADR-002-dominio-do-projeto.md](../adr/ADR-002-dominio-do-projeto.md) |
| Especificação da regra de tarifação | [docs/regra-de-tarifacao.md](../regra-de-tarifacao.md) |
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
| 1 | `pixDentroDaFranquiaSaiIsento` | Pix dentro da franquia é registrado com valor 0,00 e motivo `FRANQUIA` |
| 2 | `reentregaNaoDuplicaOEfeito` | O mesmo evento entregue 3× gera efeito 1× — o item obrigatório do B.3 |
| 3 | `pixAcimaDaFranquiaETarifado` | O Pix seguinte ao fim da franquia é cobrado |
| 4 | `consumidorTolerante` | Campos que o consumidor não declara são ignorados sem erro |

### Robustez do listener (Alexsander da Silva)

| # | Método | O que valida |
|---|---|---|
| 5 | `ceIdAusenteUsaFallbackDoCorpo` | Sem o header `ce_id` o listener cai no `eventoId` do corpo, registra `WARN` e ainda deduplica na reentrega |
| 6 | `deduplicacaoUsaEventoIdNaoIdTransacaoPix` | Dois eventos com `eventoId` distintos e mesmo `idTransacaoPix` geram dois efeitos — a chave de deduplicação é a identidade do fato, não a da entidade |
| 15 | `competenciaEIsoladaPorMes` | A franquia de agosto não contamina setembro: a competência vem do `liquidadoEm` do evento, nunca do relógio |

### Regressão encontrada em code review (Allainn Christiam)

| # | Método | O que valida |
|---|---|---|
| 7 | `ceIdDivergenteDoCorpoNaoTravaAParticao` | A identidade gravada nas duas tabelas é a mesma — a do envelope. Antes, `evento_processado` usava o `ce_id` e `tarifa` usava o `eventoId` do corpo: duas mensagens com `ce_id` distintos e mesmo `eventoId` no corpo passavam pela deduplicação e colidiam na PK da tarifa, travando a partição em retentativa. O teste falha com o código anterior e passa com a correção |

### As cinco saídas da política (Allainn Christiam)

Acrescentados em 15/08, em duas rodadas: primeiro ao confrontar o consumidor com o ADR-002, depois
ao receber de Evandro a especificação detalhada da regra
([docs/regra-de-tarifacao.md](../regra-de-tarifacao.md)). A segunda rodada corrigiu dois defeitos
que os testes anteriores não pegavam — a fronteira das faixas e o estouro do teto. Ver
[IA.md](../IA.md), interações 6 e 7.

| # | Método | O que valida |
|---|---|---|
| 8 | `empresaSemContratoNaoECobrada` | Empresa sem contrato vira linha `SEM_CONTRATO`, valor 0,00, e **não consome franquia**. Substitui um teste anterior que exigia o oposto (plano padrão de R$ 1,90) e contradizia o ADR |
| 9 | `contratoEncerradoDeixaDeSerCobrado` | A mesma empresa é cobrada em julho, quando havia contrato, e sai `SEM_CONTRATO` em agosto, quando não há |
| 10 | `ofertaEBuscadaPelaCompetenciaDoEvento` | Troca de plano: um evento de julho reprocessado hoje reencontra o plano **de julho**. É o que torna o fechamento mensal reproduzível |
| 11 | `tarifaVemDaFaixaDoValor` | Acima da franquia, o valor do Pix escolhe a faixa: R$ 0,50 / 1,00 / 5,00 / 10,00 |
| 12 | `fronteiraDaFaixaEExclusiva` | Limite superior **exclusivo**: um Pix de exatamente R$ 500,00 paga R$ 1,00, não R$ 0,50 |
| 13 | `tetoEhCobradoParcialmente` | O estouro do teto é cobrado até completá-lo (`TETO_PARCIAL`), e o acumulado para **exatamente** no teto — a invariante `valorTarifado ≤ teto` |
| 14 | `apenasOIsentoConsomeFranquia` | Invariante `unidadesFranquiaConsumidas ≤ franquia`: o Pix tarifado não gasta cota |

---

## Testes do publisher (`servico-pix`)

Arquivos:
- [`servico-pix/src/test/.../KafkaConfigTest.java`](../../servico-pix/src/test/java/br/pucminas/aed/pix/KafkaConfigTest.java)
- [`servico-pix/src/test/.../service/PixServiceTest.java`](../../servico-pix/src/test/java/br/pucminas/aed/pix/service/PixServiceTest.java)
- [`servico-pix/src/test/.../controller/PixControllerTest.java`](../../servico-pix/src/test/java/br/pucminas/aed/pix/controller/PixControllerTest.java)

### Testes originais (Amanda Bouzan)

| Arquivo | Método | O que valida |
|---|---|---|
| `KafkaConfigTest` | `serializaDataEmIso8601ENaoEmEpoch` | `ObjectMapper` serializa `liquidadoEm` como ISO-8601, nunca como epoch |
| `KafkaConfigTest` | `declaraTopicoComTresParticoes` | Tópico criado com 3 partições |
| `PixServiceTest` | `publicaContratoEsperadoPeloConsumidor` | Tópico correto, chave de partição = `idEmpresa`, os 5 headers `ce_*` obrigatórios, `ce_id` = `eventoId` do evento |
| `PixControllerTest` | `responde202QuandoPublicacaoEConfiadaAoKafka` | Controller retorna 202 quando a publicação é delegada ao Kafka |

### Testes adicionais (Alexsander da Silva)

| Arquivo | Método | O que valida |
|---|---|---|
| `PixServiceTest` | `lancaExcecaoQuandoRealizacaoENula` | `realizar(null)` lança `IllegalArgumentException` antes de qualquer publicação |
| `PixServiceTest` | `lancaExcecaoQuandoIdTransacaoPixEmBranco` | `idTransacaoPix` vazio lança exceção |
| `PixServiceTest` | `lancaExcecaoQuandoIdEmpresaEmBranco` | `idEmpresa` vazio lança exceção |
| `PixServiceTest` | `lancaExcecaoQuandoValorEZero` | `valor = 0` é rejeitado (só valor > 0 é aceito) |
| `PixServiceTest` | `lancaExcecaoQuandoValorENegativo` | `valor < 0` é rejeitado |
| `PixServiceTest` | `eventoIdUnicoACadaChamada` | Dois `realizar()` com mesmo payload geram `eventoId` distintos — cada fato tem identidade própria |
| `PixControllerTest` | `responde400QuandoEntradaEhInvalida` | `@ExceptionHandler` retorna `400 Bad Request` com corpo `{"erro": "..."}` quando a validação falha |
