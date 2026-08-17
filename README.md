# aed-2026-2-equipe-02

Projeto incremental em equipe — **AED · Arquitetura Reativa e Event-Driven · PUC Minas / IEC**
Turma ASDO 11.1 · Prof. Sândalo Bessa

## Equipe

Equipe 02 · líder: **Evandro V. Junior**

| Integrante | Matrícula | Papel nesta etapa |
|---|---|---|
| Evandro V. Junior | 254593 | líder · ADR-002 e decisão do domínio |
| Allainn Christiam | 254337 | consumidor de tarifação, infraestrutura, teste de idempotência |
| Amanda Bouzan | 255369 | publicador do evento de Pix realizado |
| Alexsander da Silva | 254779 | testes automatizados adicionais do consumidor |
| _(a preencher)_ | | |
| _(a preencher)_ | | |
| _(a preencher)_ | | |

<!--
PENDENTE — para a equipe conferir antes da entrega:
  1. faltam os tres integrantes restantes (a turma organiza equipes de sete),
     e nenhum deles tem commit proprio ainda — item 17 do checklist;
  2. lider inferido de quem criou o repositorio; confirmar;
  3. contas do GitHub x matricula (secao 2.2 exige NOME DE USUARIO = MATRICULA):
       254337  Allainn      OK  (conta renomeada em 16/08)
       254593  Evandro      OK
       1665626 Amanda       conferir se e a matricula
       1125713 Alexsander   DIVERGE: o README diz 254779, e as duas contas
                            existem. Os commits dele saem como 1125713. A saida
                            e adicionar 1125713@pucminas.edu.br em Settings ->
                            Emails da conta 254779: os commits migram sozinhos,
                            porque a atribuicao do GitHub e pelo e-mail;
  4. docs/IA.md ainda tem uma secao so. Sao 10% da nota, e o criterio pede tres
     interacoes com ao menos uma recusa justificada POR INTEGRANTE.
-->

## O domínio em uma frase

Tarifação de Pix de contas PJ: cada Pix liquidado é confrontado com a oferta comercial vigente da
empresa e sai isento, enquanto couber na franquia mensal do plano, ou tarifado a partir do primeiro
excedente — e empresa sem contrato vigente não é cobrada.

O processo completo, os quatro critérios e as consequências aceitas estão em
[docs/adr/ADR-002-dominio-do-projeto.md](docs/adr/ADR-002-dominio-do-projeto.md).

---

# Como subir o projeto numa máquina limpa

## Pré-requisitos

| O que | Como conferir | Esperado |
|---|---|---|
| JDK 21 | `java -version` | `openjdk version "21..."` |
| Maven 3.9+ | `mvn -v` | 3.9 ou maior, com `Java version: 21` |
| Docker | `docker compose version` | qualquer v2.x |
| Docker rodando | `docker ps` | uma tabela, mesmo vazia |

> **Se você rodou o `demo-kafka-idempotencia` da aula 01**, derrube-o antes: as portas são as
> mesmas (19092, 15432, 8081). `docker compose down` na pasta do demo resolve.

> **No PowerShell, `curl` é apelido de `Invoke-WebRequest`** e não entende `-X`, `-d` nem
> `@arquivo`. Use **`curl.exe`**, com o `.exe`. No Git Bash, WSL, Linux e macOS, use `curl`.

## Passo 1 — a infraestrutura

```bash
docker compose up -d
```

Sobe três containers e só devolve o prompt quando o Kafka está *healthy* (~8 s):

| Serviço | Container | Endereço no host |
|---|---|---|
| Kafka (KRaft) | `e02-kafka` | `localhost:19092` |
| Postgres | `e02-postgres` | `localhost:15432` — db/user/senha `tarifacao` |
| Kafka UI | `e02-kafka-ui` | http://localhost:8081 |

## Passo 2 — o consumidor (servico-tarifacao)

Cria as próprias tabelas na subida e fica esperando evento. Não expõe porta HTTP.

```bash
mvn -f servico-tarifacao/pom.xml spring-boot:run
```

Se ele subir antes de o tópico existir, aparece `UNKNOWN_TOPIC_OR_PARTITION` — é esperado, e se
resolve sozinho em 5 segundos.

> Detalhes do consumidor, incluindo o **contrato que o publicador precisa respeitar**, estão em
> [servico-tarifacao/README.md](servico-tarifacao/README.md).

## Passo 3 — o publicador (servico-pix)

Expõe HTTP na porta 8080 e cria o tópico com 3 partições na subida. **Numa outra aba**, com o
consumidor já rodando:

```bash
mvn -f servico-pix/pom.xml spring-boot:run
```

## Passo 4 — publicar um Pix

```bash
curl.exe -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/pix/realizados \
     -H "Content-Type: application/json" -d "@servico-pix/pix-exemplo.json"
```

A requisição leva um `RealizacaoPixVO` (o pedido); a resposta devolve o `PixRealizadoEvent` (o
fato), já com o `eventoId` sorteado e o `liquidadoEm` em ISO-8601. Campo obrigatório faltando ou
valor menor ou igual a zero responde **400** com o motivo no corpo.

Para gastar a franquia, repita o comando trocando o `idTransacaoPix` — o `eventoId` é sorteado a cada
chamada, então cada requisição é um fato novo.

A resposta é **202 Accepted**, não 200: no instante da resposta o Pix ainda não foi tarifado.

### Ou pelo script, que já traz o roteiro pronto

```bash
./scripts/publicar-pix.sh --roteiro
```

```powershell
.\scripts\publicar-pix.ps1 -Roteiro
```

Os dois são equivalentes — mesmos parâmetros, mesma saída. O `--roteiro` publica os cinco
cenários da política em sequência e termina pela **idempotência**: franquia, faixa por valor,
a fronteira exclusiva em R$ 500,00, o teto parando exatamente em R$ 25,00, a empresa sem
contrato saindo com valor zero, e o mesmo evento entregue 3× produzindo efeito 1×.

| Comando | O que faz |
|---|---|
| `--roteiro` / `-Roteiro` | os cinco cenários da política **+ a idempotência** |
| `--idempotencia` / `-Idempotencia` | **o mesmo evento 3× → efeito 1×**, e falha se não for |
| `--evento-id X` / `-EventoId X` | repete um `eventoId` de propósito, para ver o descarte |
| `--entregas 5` / `-Entregas 5` | quantas vezes reentregar o mesmo evento |
| `--conferir` / `-Conferir` | só mostra a tabela `tarifa`, sem publicar nada |
| `-e emp-0002 -v 750.00` | um Pix parametrizado (`-Empresa` / `-Valor` no PowerShell) |
| `-n 11` | onze Pix seguidos (`-Quantidade` no PowerShell) |
| `-h` | ajuda |

> **Por que a idempotência não passa pela API HTTP.** O corpo do `POST` é um `RealizacaoPixVO` —
> o *pedido* —, e quem cria o *fato* (sorteando o `eventoId`) é o `PixService`. Cada chamada é um
> fato novo, então repetir o `curl` **não** demonstra reentrega. O `--idempotencia` publica direto
> no tópico com `ce_id` fixo, que é o que o consumidor usa para deduplicar. As duas cargas estão
> em [scripts/eventos/](scripts/eventos/), com a explicação da diferença.

### Alternativa sem o publicador

Dá para exercitar o consumidor sozinho, publicando direto no tópico:

```bash
printf 'ce_specversion=1.0;ce_id=evt-001;ce_source=/pagamentos/servico-pix;ce_type=pagamentos.pix.realizado.v1;ce_time=2026-08-14T13:00:00.000Z#emp-0001~{"eventoId":"evt-001","liquidadoEm":"2026-08-14T13:00:00.000Z","idTransacaoPix":"pix-001","idEmpresa":"emp-0001","valor":150.00,"chavePix":"fulano@exemplo.com","tipoChave":"EMAIL","bancoDestino":"999","endToEndId":"E99900000202608141300000000001","pagadorNome":"Empresa Ficticia"}\n' | docker exec -i e02-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9094 --topic pagamentos.pix.realizado.v1 --property parse.headers=true --property headers.delimiter='#' --property headers.separator=';' --property headers.key.separator='=' --property parse.key=true --property key.separator='~'
```

Repetir a mesma linha três vezes demonstra a idempotência: o `ce_id` é o mesmo e o efeito
acontece uma vez só. Para gastar a franquia, troque `ce_id`, `eventoId` e `idTransacaoPix` a cada
publicação.

## Passo 5 — conferir o resultado

**No banco** — `emp-0001` é o Plano PJ: 10 Pix isentos por mês, acima disso a tarifa vem da faixa
do valor (abaixo de R$ 500 → R$ 0,50):

```bash
docker exec e02-postgres psql -U tarifacao -d tarifacao -c "SELECT id_transacao_pix, competencia, situacao, valor FROM tarifa ORDER BY liquidado_em;"
```

```
 id_transacao_pix | competencia |  situacao  | valor
------------------+-------------+------------+-------
 pix-e2e-1        | 2026-08     | FRANQUIA   |  0.00
 ...
 pix-e2e-11       | 2026-08     | FAIXA      |  0.50   <- o 11º excede a franquia
```

A coluna `situacao` é o motivo da decisão, e é dado de domínio: três das cinco saídas valem zero e
significam coisas diferentes. Trocando o `idEmpresa` para `emp-9999` — que não tem contrato — a
linha sai como `SEM_CONTRATO`, e **não** cobrada:

```bash
docker exec e02-postgres psql -U tarifacao -d tarifacao -c "SELECT situacao, count(*), sum(valor) FROM tarifa GROUP BY situacao;"
```

**O lag do grupo** — deve zerar depois que o consumidor processa:

```bash
docker exec e02-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9094 --describe --group tarifacao
```

**Na Kafka UI** (http://localhost:8081) — abra uma mensagem do tópico e confira os cinco
cabeçalhos `ce_*` e que `liquidadoEm` está em ISO-8601, não em epoch.

> No Git Bash, os `docker exec` acima precisam do prefixo `MSYS_NO_PATHCONV=1`, senão o caminho
> `/opt/kafka/...` é convertido para caminho do Windows e o exec falha.

## O teste automatizado

Roda com Kafka embutido e H2 — **sem Docker e sem o `servico-pix`**:

```bash
mvn -f servico-tarifacao/pom.xml test
```

Dezesseis cenários, cobrindo a idempotência e as cinco saídas da política: **o mesmo evento entregue
3x produz efeito 1x** · consumidor tolerante a campos desconhecidos · `ce_id` ausente · mesmo
`idTransacaoPix` com `eventoId` distintos · isenção por franquia · a faixa de valor, com a fronteira
exclusiva · empresa sem contrato não é cobrada · contrato encerrado · troca de plano respeitando a
competência do evento · o estouro do teto cobrado parcialmente · só o isento consome franquia ·
isolamento por competência. A tabela completa está em
[servico-tarifacao/README.md](servico-tarifacao/README.md#rodar).

O `servico-pix` tem a própria bateria (`mvn -f servico-pix/pom.xml test`): ISO-8601 no fio, tópico
com 3 partições, 202 e 400 no controlador, e o contrato que o consumidor espera.

## Derrubar tudo

```bash
docker compose down -v
```

---

## Estrutura do repositório

```
aed-2026-2-equipe-02/
├── README.md
├── docker-compose.yml           Kafka + Postgres + Kafka UI
├── docs/
│   ├── adr/ADR-002-dominio-do-projeto.md
│   ├── regra-de-tarifacao.md    a regra em detalhe: faixas, teto, compensação
│   ├── IA.md                    registro do uso de IA, por integrante
│   └── entregas/aula-02.md      folha de rosto desta entrega
├── scripts/                     publicar-pix.sh e .ps1 — exercitam a API
├── servico-pix/                 publicador  (projeto Maven independente)
└── servico-tarifacao/           consumidor  (projeto Maven independente)
```

Os dois serviços não compartilham POM pai nem módulo de contrato: o contrato entre eles é o JSON
que trafega no tópico `pagamentos.pix.realizado.v1`.
