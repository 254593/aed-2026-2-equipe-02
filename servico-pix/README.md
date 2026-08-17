# servico-pix - o publicador

Recebe pela API o registro de um Pix realizado e publica `PixRealizadoEvent` para que o
`servico-tarifacao` aplique a franquia mensal da empresa.

## Rodar

Na raiz do repositório, suba a infraestrutura:

```bash
docker compose up -d
```

Depois, inicie o publicador:

```bash
mvn -f servico-pix/pom.xml spring-boot:run
```

No Windows PowerShell, publique o exemplo com:

```powershell
curl.exe -i -X POST http://localhost:8080/pix/realizados `
  -H "Content-Type: application/json" `
  --data-binary "@servico-pix/pix-exemplo.json"
```

No Git Bash, WSL, Linux ou macOS:

```bash
curl -i -X POST http://localhost:8080/pix/realizados \
  -H "Content-Type: application/json" \
  --data-binary @servico-pix/pix-exemplo.json
```

A API responde `202 Accepted`. Consulte a mensagem na Kafka UI em
`http://localhost:8081`, no tópico `pagamentos.pix.realizado.v1`.

## `eventoId`: chave de idempotência opcional

O corpo aceita um campo `eventoId`. Informado, ele vira a identidade do fato publicado — o `ce_id`
do envelope —, e repetir o mesmo POST produz o **mesmo** evento, que o consumidor descarta como
reentrega. Ausente, o serviço gera um UUID por chamada.

Existe porque o POST é repetível por fora: um timeout ou uma falha de rede fazem o cliente tentar de
novo, e sem a chave o serviço publicaria **dois eventos distintos para o mesmo Pix** — a empresa
seria cobrada duas vezes. É o mesmo papel do `Idempotency-Key` das APIs de pagamento.

O `pix-exemplo.json` **não** traz o campo, de propósito: se trouxesse uma chave fixa, quem repetisse
o comando acima veria Pix reais sendo descartados como reentrega.

## Gerar carga (opcional)

```bash
./scripts/publicar-pix.sh -n 11              # onze Pix: a franquia acaba no 11º
./scripts/publicar-pix.sh --idempotencia     # o mesmo evento 3x -> efeito 1x
```

No Windows, `scripts\publicar-pix.ps1` tem os mesmos parâmetros. Os comandos `curl` de
[docs/entregas/aula-02.md](../docs/entregas/aula-02.md) cobrem os mesmos casos sem script nenhum.

## Contrato publicado

- tópico e `ce_type`: `pagamentos.pix.realizado.v1`;
- chave de partição: `idEmpresa`;
- três partições, declaradas pelo bean `NewTopic`;
- `ce_source`: `/pagamentos/servico-pix`;
- `ce_id`: igual ao `eventoId` do evento — o informado no request, ou o gerado pelo serviço;
- `ce_time` e `liquidadoEm`: ISO-8601.

O retorno de `KafkaTemplate.send()` pertence a `ResultadoPublicacaoListener`, que registra
sucesso ou falha de publicação. O serviço não possui banco nesta etapa.

## Testar

```bash
mvn -f servico-pix/pom.xml test
```

Os testes conferem o contrato do producer, a chave de partição, os cabeçalhos CloudEvents,
a serialização ISO-8601, a criação do tópico com três partições e a resposta HTTP 202.

