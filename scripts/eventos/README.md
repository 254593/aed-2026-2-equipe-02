# scripts/eventos — cargas de exemplo

Dois formatos diferentes, e a diferença entre eles é o assunto da aula.

## `pix-reentrega.json` — o **evento**, com `eventoId` fixo

É um `PixRealizadoEvent` completo, do jeito que trafega no tópico. O `eventoId` é fixo de
propósito: publicando esta carga três vezes com o mesmo `ce_id`, o consumidor produz efeito
**uma** vez.

Não serve para a API HTTP. Ela recebe um `RealizacaoPixVO` — o **pedido** —, e quem cria o fato
(sorteando o `eventoId` e carimbando o `liquidadoEm`) é o `PixService`. Mandar um `eventoId`
pronto pela API seria deixar o cliente inventar a identidade de um fato que ainda não aconteceu.

Use pelo script:

```bash
./scripts/publicar-pix.sh --idempotencia
```

```powershell
.\scripts\publicar-pix.ps1 -Idempotencia
```

## `../../servico-pix/pix-exemplo.json` — o **pedido**, sem `eventoId`

É o corpo que a API HTTP aceita:

```bash
curl -s -X POST http://localhost:8080/pix/realizados \
     -H "Content-Type: application/json" -d "@servico-pix/pix-exemplo.json"
```

Cada chamada gera um evento **novo**, com `eventoId` sorteado — por isso repetir este comando
**não** demonstra idempotência: são fatos diferentes, e cada um vira uma linha.

## Por que a distinção importa

O erro que ela evita é confundir *identidade do fato* com *identidade da entidade*. O mesmo
`idTransacaoPix` pode aparecer em eventos diferentes — um `PixRealizado` hoje, um `PixDevolvido`
amanhã. A deduplicação é pelo `eventoId`, nunca pelo `idTransacaoPix`; é o que o teste 6 do
`IdempotenciaTest` verifica.
