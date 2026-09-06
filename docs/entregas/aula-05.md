# Entregas da Aula 05 — Event Sourcing e a projeção da fatura

**Equipe 02 · AED · Turma ASDO 11.1 · PUC Minas / IEC**

| O quê | Onde |
|---|---|
| A decisão | [docs/adr/ADR-005-event-sourcing.md](../adr/ADR-005-event-sourcing.md) |
| O event store | [`schema.sql`](../../servico-tarifacao/src/main/resources/schema.sql) · [`TarifacaoRepository`](../../servico-tarifacao/src/main/java/br/pucminas/aed/tarifacao/service/TarifacaoRepository.java) |
| A projeção | [`FaturaProjetor`](../../servico-tarifacao/src/main/java/br/pucminas/aed/tarifacao/service/FaturaProjetor.java) |
| O teste que prova o replay | [`ProjecaoDaFaturaTest`](../../servico-tarifacao/src/test/java/br/pucminas/aed/tarifacao/ProjecaoDaFaturaTest.java), teste 5 |
| Registro de uso de IA | [docs/IA.md](../IA.md) |

---

## 1. O agregado

> **`CicloDeTarifacao`**, um por `(idEmpresa, competência)`.

A fronteira sai da invariante, não de preferência: `franquia ≤ N` e `tarifado ≤ teto` só existem
sobre a competência — um Pix isolado não viola nem satisfaz nenhuma das duas. O `PixTarifado`, que é
o candidato intuitivo e tem ciclo de vida próprio, não sobrevive ao teste: promovê-lo poria as
invariantes *entre* agregados e exigiria a reserva de franquia que o ADR-003 revogou.

O argumento completo, as alternativas de solução e as dez consequências aceitas estão no ADR-005.
Aqui fica o que foi feito e como se confere.

---

## 2. O event store — e quanto dele já existia

A tabela `tarifa` **já era** o log do agregado antes desta entrega, sem ninguém ter chamado assim.
A decisão de tarifação nunca leu um contador: `contarFranquiaConsumida()` é `COUNT(*)` e
`totalTarifadoNaCompetencia()` é `SUM(valor)`, ambos sobre a própria `tarifa`. O estado sempre foi
o *fold* do histórico — escrito por idempotência, e chegando no mesmo lugar.

Das três regras que definem um event store, uma já valia e duas entraram agora:

| Regra | Onde vive |
|---|---|
| **Append-only** | já valia: só `INSERT`; `CHECK (valor >= 0)` impede o acumulado de retroceder |
| **Ordem por stream** | coluna `versao`, atribuída por `MAX(versao)+1` no stream, dentro da transação da decisão |
| **Versão por stream** | índice único `(id_empresa, competencia, versao)` — dois escritores na mesma versão, o segundo leva violação e a transação inteira volta |

Não há coluna `stream_id`: `(id_empresa, competencia)` já é a identidade do stream. E `situacao`
já era o tipo do fato — `SEM_CONTRATO`, `FRANQUIA`, `FAIXA`, `TETO_PARCIAL`, `TETO_ATINGIDO` são
cinco fatos distintos, não cinco estados de uma linha que muda.

**A versão é um `read-then-write`, e quem a protege é a chave de partição do ADR-003.** Todos os
Pix de uma empresa caem na mesma partição e são processados em série; há um escritor por stream por
construção. O índice único é rede de segurança para o dia em que a compensação puser um segundo
escritor — e é por isso que ele existe agora, enquanto custa uma linha de DDL.

---

## 3. A projeção

> **`fatura_competencia`** — uma linha por `(idEmpresa, competência)`: total tarifado, quantidade de
> Pix, quantos por situação e `versao_projetada`.

É **derivada, descartável e assíncrona**. Nenhuma decisão lê dela — a tarifação continua lendo os
fatos. Um projetor agendado (`FaturaProjetorAgendado`, a cada 2 s) lê os fatos acima da
`versao_projetada` de cada stream e soma sobre a linha existente. Avança por versão, não por tempo.

**Reconstruir usa o mesmo código.** `reconstruir()` apaga a tabela e chama o mesmo `avancar()` — a
partir da versão zero, o avanço incremental *é* o replay completo. Não há caminho separado de
*rebuild* de propósito: um caminho separado poderia divergir do incremental sem que nada acusasse, e
o teste passaria provando a coerência de um código que não é o que roda.

**A consequência prática, e ela é a demonstração:** em produção, o gatilho da reconstrução é o
próprio `DELETE`. Stream sem linha na projeção é tratado como `versao_projetada = 0`, então apagar a
tabela faz o agendamento reconstruir tudo sozinho no ciclo seguinte.

---

## 4. A defasagem tolerada, por tela

Consistência eventual não é defeito a corrigir com engenharia: é escolha a administrar por tela.
Este domínio tem três telas, e a resposta é diferente em cada uma.

| Tela | Quem pergunta | Defasagem tolerada | Por quê |
|---|---|---|---|
| **Fechamento da competência** | financeiro, faturamento | **minutos** | o ciclo é mensal e a fatura fecha uma vez; o projetor a 2 s está três ordens de grandeza abaixo do que a tela precisa |
| **"Quanto já gastei este mês"** — self-service PJ | o cliente | **segundos**, e a interface precisa dizer que o número se move | o ADR-002 já havia assumido: nada *"pode prometer saldo de tarifas exato em tempo real"*. A tela mostra o total e a versão projetada; se o cliente acabou de fazer um Pix, ela diz "processando" em vez de fingir precisão |
| **Auditoria e contestação** | compliance, comercial, o próprio cliente contestando | **não lê a projeção** | o que ela exige não é atualidade, é a mesma resposta hoje e daqui a anos. Lê o log, por `versao`, e reproduz a decisão |

**A defasagem é medida, não estimada.** `versao_projetada` existe para isso: o atraso de uma linha
é a distância entre a versão atual do stream e ela, **em fatos** — não em segundos, que dependeriam
do relógio de quem pergunta.

```sql
SELECT t.id_empresa, t.competencia,
       MAX(t.versao)                          AS versao_do_log,
       COALESCE(MAX(f.versao_projetada), 0)   AS versao_projetada,
       MAX(t.versao) - COALESCE(MAX(f.versao_projetada), 0) AS atraso_em_fatos
  FROM tarifa t
  LEFT JOIN fatura_competencia f USING (id_empresa, competencia)
 GROUP BY t.id_empresa, t.competencia;
```

Em regime normal a coluna `atraso_em_fatos` é zero ou um. Se crescer e não voltar, o projetor parou —
e é um alarme, não uma estimativa.

**O que não resolve, e foi recusado:** acelerar o projetor reduz a janela e não a elimina; ler do
lado da escrita "só nesta tela" desfaz a separação e a projeção deixa de ser descartável.

---

## 5. Como rodar

```bash
docker compose up -d
mvn -f servico-tarifacao/pom.xml spring-boot:run     # aba 1
mvn -f servico-pix/pom.xml spring-boot:run           # aba 2
./scripts/publicar-pix.sh --roteiro                  # publica os cinco cenarios
```

> Banco criado **antes** desta entrega não tem a coluna `versao`. Derrube com
> `docker compose down -v` e suba de novo — está comentado no `schema.sql`, e não há migração com
> backfill de propósito: inventar versão para fatos históricos é escrever ordem que ninguém observou.

**O log, por stream e versão:**

```bash
docker exec e02-postgres psql -U tarifacao -d tarifacao -c \
  "SELECT id_empresa, competencia, versao, situacao, valor FROM tarifa ORDER BY id_empresa, competencia, versao;"
```

**A projeção, depois de ~2 s:**

```bash
docker exec e02-postgres psql -U tarifacao -d tarifacao -c \
  "SELECT id_empresa, competencia, total_tarifado, qtd_pix, qtd_franquia, qtd_faixa, qtd_teto_parcial, qtd_teto_atingido, versao_projetada FROM fatura_competencia ORDER BY 1, 2;"
```

### O critério: apagar a projeção e reconstruir pelo log

```bash
# 1. anote o resultado
docker exec e02-postgres psql -U tarifacao -d tarifacao -c "SELECT * FROM fatura_competencia ORDER BY 1, 2;"

# 2. apague tudo
docker exec e02-postgres psql -U tarifacao -d tarifacao -c "DELETE FROM fatura_competencia;"

# 3. espere o ciclo do projetor (2 s) e compare
sleep 3
docker exec e02-postgres psql -U tarifacao -d tarifacao -c "SELECT * FROM fatura_competencia ORDER BY 1, 2;"
```

O resultado é idêntico, e no log do consumidor aparece `projecao avancada em N stream(s)` sem
ninguém ter chamado nada. É o mesmo teste que o `ProjecaoDaFaturaTest` 5 faz automatizado: processa
os quatro Pix da `emp-0006` (as três saídas do teto) mais um `SEM_CONTRATO`, projeta, apaga, reconstrói
e exige igualdade campo a campo — afirmando os valores concretos, R$ 25,00 e uma linha `TETO_PARCIAL`.

### Os testes

```bash
mvn -f servico-tarifacao/pom.xml test     # 39 cenarios, sem Docker
```

Os onze novos:

| # | O que prova |
|---|---|
| 1 | a versão é sequencial no stream e começa em 1 |
| 2 | o stream é `(empresa, competência)`: a numeração recomeça no mês seguinte |
| 3 | **conflito de versão**: dois fatos na mesma versão são recusados |
| 4 | a projeção reproduz o fold do log |
| 5 | **reconstrução**: apagar e refazer pelo log dá o mesmo resultado |
| 6 | o avanço é incremental |
| 7 | avançar sem fato novo não altera nada |
| 8 | reentrega não infla a projeção — 3 entregas, 1 Pix |
| 9 | falha ao gravar o fato desfaz **também** a deduplicação, e o evento pode ser reprocessado |
| 10 | o `schema.sql` pode rodar de novo sobre o banco já criado |
| — | `ProjetorAgendadoTest`: o projetor agendado projeta sem ninguém chamar |

O 9 vale uma nota: a corrida real de versão não se reproduz de forma determinística numa thread. A
propriedade — "a transação inteira volta, inclusive a deduplicação" — é provocada pela constraint
irmã no mesmo `INSERT`, a chave primária `evento_id`. Sem essa garantia, o evento ficaria marcado
como processado sem ter produzido efeito.

---

## 6. O que decidimos não fazer

**Não criamos uma tabela de eventos ao lado da `tarifa`.** Seriam duas fontes da verdade sobre o
mesmo fato — e a objeção não é *dual write*, que é falta de atomicidade entre sistemas sem transação
comum; duas tabelas no mesmo Postgres comitam juntas. É que alguém teria de decidir qual das duas é
autoritativa, para sempre, sem constraint que impeça a divergência.

**Não criamos `stream_id`.** `(id_empresa, competencia)` já é a identidade do stream. Uma coluna
concatenada seria redundância numa tabela que é fonte da verdade.

**Não fizemos a projeção síncrona.** Eliminaria a janela de defasagem — e acoplaria a leitura à
escrita, desfazendo a fronteira que torna a projeção descartável.

**Não implementamos snapshot.** O stream fecha no fim do mês; não há gargalo a otimizar. Se um dia
houver, apagar todos os snapshots tem de continuar seguro.

**Não entramos na compensação.** Os estornos serão fatos novos no mesmo stream, mas o desenho da
saga fica para a etapa seguinte. As divergências entre o ADR-002 e a especificação sobre
`nao_tarifavel` continuam abertas de propósito.

---

## Resumo

| Decisão | Escolha |
|---|---|
| Agregado | **`CicloDeTarifacao`** — `(idEmpresa, competência)`, fronteira da invariante |
| Event store | a própria `tarifa`: `versao` + índice único; sem `stream_id` |
| Concorrência | `MAX+1` na transação, protegido pela chave de partição; o índice é rede de segurança |
| Projeção | `fatura_competencia`, **assíncrona**, avança por versão |
| Reconstrução | mesmo `avancar()` a partir do zero; em produção, o `DELETE` é o gatilho |
| Defasagem | fechamento: minutos · self-service: segundos, declarado na tela · auditoria: lê o log |
| Medida | `versao_do_log − versao_projetada`, em fatos |
| Fora | compensação, snapshot, tabela de eventos separada |
