# servico-pix - o publicador

Recebe pela API o registro de um Pix realizado e publica `PixRealizadoEvent` para que o
`servico-tarifacao` aplique a franquia mensal do cliente.

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

## Contrato publicado

- tópico e `ce_type`: `pagamentos.pix.realizado.v1`;
- chave de partição: `clienteId`;
- três partições, declaradas pelo bean `NewTopic`;
- `ce_source`: `/pagamentos/servico-pix`;
- `ce_id`: igual ao `eventoId` do corpo;
- `ce_time` e `ocorridoEm`: ISO-8601.

O retorno de `KafkaTemplate.send()` pertence a `ResultadoPublicacaoListener`, que registra
sucesso ou falha de publicação. O serviço não possui banco nesta etapa.

## Testar

```bash
mvn -f servico-pix/pom.xml test
```

Os testes conferem o contrato do producer, a chave de partição, os cabeçalhos CloudEvents,
a serialização ISO-8601, a criação do tópico com três partições e a resposta HTTP 202.

