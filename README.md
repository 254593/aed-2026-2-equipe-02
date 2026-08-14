# aed-2026-2-equipe-02

Projeto incremental em equipe — **AED · Arquitetura Reativa e Event-Driven · PUC Minas / IEC**
Turma ASDO 11.1 · Prof. Sândalo Bessa

<!-- TODO EQUIPE: preencher líder, integrantes e o domínio em uma frase (secao 2.3 do enunciado). -->

## Equipe

| Integrante | Matrícula | Papel nesta etapa |
|---|---|---|
| _(a preencher)_ | | líder |
| Allainn Christiam | 1664926 | consumidor de tarifação, infraestrutura, teste de idempotência |
| _(a preencher)_ | | |

## O domínio em uma frase

<!-- TODO EQUIPE: alinhar com docs/adr/ADR-002-dominio-do-projeto.md -->

Tarifação de Pix: cada cliente tem uma franquia de Pix gratuitos por mês, e o Pix que excede a
franquia é tarifado pelo valor do plano contratado.

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

<!-- TODO: ajustar quando o servico-pix estiver no repositório. -->

```bash
mvn -f servico-pix/pom.xml spring-boot:run
```

## Passo 4 — publicar um Pix

```bash
curl.exe -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/pix/realizados \
     -H "Content-Type: application/json" -d "@pix/pix-exemplo.json"
```

A resposta é **202 Accepted**, não 200: no instante da resposta o Pix ainda não foi tarifado.

### Alternativa sem o publicador

Dá para exercitar o consumidor sozinho, publicando direto no tópico:

```bash
printf 'ce_specversion=1.0;ce_id=evt-001;ce_source=/pagamentos/servico-pix;ce_type=pagamentos.pix.realizado.v1;ce_time=2026-08-14T13:00:00.000Z#cli-0001~{"eventoId":"evt-001","ocorridoEm":"2026-08-14T13:00:00.000Z","pixId":"pix-001","clienteId":"cli-0001","valor":150.00,"chavePix":"fulano@exemplo.com","tipoChave":"EMAIL","bancoDestino":"999","endToEndId":"E99900000202608141300000000001","pagadorNome":"Cliente Ficticio"}\n' | docker exec -i e02-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9094 --topic pagamentos.pix.realizado.v1 --property parse.headers=true --property headers.delimiter='#' --property headers.separator=';' --property headers.key.separator='=' --property parse.key=true --property key.separator='~'
```

Repetir a mesma linha três vezes demonstra a idempotência: o `ce_id` é o mesmo e o efeito
acontece uma vez só. Para gastar a franquia, troque `ce_id`, `eventoId` e `pixId` a cada
publicação.

## Passo 5 — conferir o resultado

**No banco** — a franquia de `cli-0001` é de 5 Pix por mês, a R$ 1,90 o excedente:

```bash
docker exec e02-postgres psql -U tarifacao -d tarifacao -c "SELECT pix_id, competencia, valor FROM tarifa ORDER BY ocorrido_em;"
```

```
  pix_id   | competencia | valor
-----------+-------------+-------
 pix-e2e-1 | 2026-08     |  0.00
 ...
 pix-e2e-6 | 2026-08     |  1.90     <- o sexto excede a franquia
```

**O lag do grupo** — deve zerar depois que o consumidor processa:

```bash
docker exec e02-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9094 --describe --group tarifacao
```

**Na Kafka UI** (http://localhost:8081) — abra uma mensagem do tópico e confira os cinco
cabeçalhos `ce_*` e que `ocorridoEm` está em ISO-8601, não em epoch.

> No Git Bash, os `docker exec` acima precisam do prefixo `MSYS_NO_PATHCONV=1`, senão o caminho
> `/opt/kafka/...` é convertido para caminho do Windows e o exec falha.

## O teste automatizado

Roda com Kafka embutido e H2 — **sem Docker e sem o `servico-pix`**:

```bash
mvn -f servico-tarifacao/pom.xml test
```

Quatro cenários: Pix isento dentro da franquia · **o mesmo evento entregue 3x tarifa 1x** · o Pix
seguinte ao fim da franquia é tarifado · campos que o consumidor não declara são ignorados.

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
│   ├── IA.md                    registro do uso de IA, por integrante
│   └── entregas/aula-02.md      folha de rosto desta entrega
├── servico-pix/                 publicador  (projeto Maven independente)
└── servico-tarifacao/           consumidor  (projeto Maven independente)
```

Os dois serviços não compartilham POM pai nem módulo de contrato: o contrato entre eles é o JSON
que trafega no tópico `pagamentos.pix.realizado.v1`.
