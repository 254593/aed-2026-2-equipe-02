# Folha de rosto — Aula 02

**Equipe 02 · AED · Turma ASDO 11.1 · PUC Minas / IEC**

---

## O que foi feito nesta etapa

| Artefato | Responsável |
|---|---|
| ADR-002: decisão do domínio (tarifação de Pix) | equipe |
| `servico-tarifacao` — consumidor Kafka com idempotência | Allainn Christiam |
| `servico-pix` — publisher com envelope CloudEvents 1.0 | Amanda Bouzan |
| Testes automatizados adicionais do consumidor (T5–T8) | Alexsander da Silva |
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

### Testes automatizados (sem Docker, sem servico-pix)

```bash
mvn -f servico-tarifacao/pom.xml test
```

---

## Testes automatizados — o que cada um cobre

O arquivo de testes é
[`servico-tarifacao/src/test/java/br/pucminas/aed/tarifacao/IdempotenciaTest.java`](../../servico-tarifacao/src/test/java/br/pucminas/aed/tarifacao/IdempotenciaTest.java).
Roda com Kafka embutido e H2 — sem Docker e sem o `servico-pix`.
As mensagens são publicadas como JSON cru para exercitar o contrato do fio, não a classe Java.

### Testes originais (Allainn Christiam)

| # | Método | O que valida |
|---|---|---|
| 1 | `pixDentroDaFranquiaSaiIsento` | Pix dentro da franquia é registrado com valor 0,00 |
| 2 | `reentregaNaoDuplicaOEfeito` | O mesmo evento entregue 3× gera efeito 1× (idempotência core) |
| 3 | `pixAcimaDaFranquiaEhTarifado` | O Pix seguinte ao fim da franquia é cobrado pelo valor do plano |
| 4 | `consumidorTolerante` | Campos que o consumidor não declara são ignorados sem erro |

### Testes adicionais (Alexsander da Silva)

| # | Método | O que valida |
|---|---|---|
| 5 | `ceIdAusenteUsaFallbackDoCorpo` | Quando o header `ce_id` está ausente o listener cai no fallback para o `eventoId` do corpo, processa normalmente e ainda deduplica na reentrega |
| 6 | `competenciaEIsoladaPorMes` | A franquia de agosto não contamina setembro: a competência vem do campo `ocorridoEm` do evento, nunca do relógio da máquina |
| 7 | `clienteSemOfertaUsaPlanoPadrao` | Cliente sem linha na tabela `oferta` recebe o plano padrão (5 grátis, R$ 1,90) — cliente desconhecido não derruba o consumidor |
| 8 | `deduplicacaoUsaEventoIdNaoPixId` | Dois eventos com `eventoId` distintos e mesmo `pixId` geram dois efeitos — a chave de deduplicação é a identidade do fato, não a da entidade de negócio |
