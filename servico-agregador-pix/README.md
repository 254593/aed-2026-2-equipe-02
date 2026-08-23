# Agregador de Pix por Hora

Consumidor que agrega Pix liquidados por janela de tempo (hora).

## Quick start

```bash
mvn spring-boot:run
```

Pré-requisito: `servico-pix` e infraestrutura Kafka já rodando.

## Funcionalidade

- **Entrada:** eventos do tópico `pagamentos.pix.realizado.v1`
- **Processamento:** agrupa por hora usando event time (`liquidadoEm`)
- **Saída:** logs com agregação por hora
- **Pergunta respondida:** "Quanto foi liquidado em Pix por hora?"

## Grupo de consumidores

- **servico-tarifacao:** `tarifacao-pix` (etapa anterior)
- **servico-agregador-pix:** `agregador-pix-por-hora` (etapa 3)

Os dois podem rodar simultaneamente sem interferência. Cada um consome do mesmo tópico com seu próprio offset.

## Janela alinhada por tempo

A janela de 1 hora é alinhada por tempo UTC:
- 00:00 UTC a 01:00 UTC
- 01:00 UTC a 02:00 UTC
- ...

Não é alinhada pela hora em que o processo subiu. Isso garante que o resultado seja reproduzível se o fluxo for reprocessado do início.

## Exemplo de log

```
14:35:20 INFO  AgregadorListener           : Agregando Pix pix-20260823-001 | Janela: [2026-08-23T14:00:00Z, 2026-08-23T15:00:00Z) | Valor acumulado: R$ 1250.50 | Quantidade: 1
14:40:15 INFO  AgregadorListener           : Agregando Pix pix-20260823-002 | Janela: [2026-08-23T14:00:00Z, 2026-08-23T15:00:00Z) | Valor acumulado: R$ 2750.75 | Quantidade: 2
15:05:30 INFO  AgregadorListener           : Janela encerrada: 2026-08-23T14:00:00Z | Total: R$ 2750.75 | Quantidade: 2 Pix
15:05:30 INFO  AgregadorListener           : Agregando Pix pix-20260823-003 | Janela: [2026-08-23T15:00:00Z, 2026-08-23T16:00:00Z) | Valor acumulado: R$ 500.00 | Quantidade: 1
```
