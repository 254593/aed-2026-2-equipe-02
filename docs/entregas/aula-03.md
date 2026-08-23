# Entregas da Aula 03 — Contrato e Agregador

**Equipe 02 · AED · Turma ASDO 11.1 · PUC Minas / IEC**

---

## 1. Pergunta de negócio que a agregação responde

**"Quanto foi liquidado em Pix por hora?"**

Esta métrica é essencial para o time de negócio porque:
- Monitora o fluxo de receita em tempo real (ou quase tempo real)
- Alimenta o sistema de faturamento com volume de transações por competência
- Permite análise de sazonalidade e padrões de uso ao longo do dia
- Detecta anomalias: uma hora com volume anormalmente baixo pode indicar problema operacional

É uma pergunta de negócio, não de infraestrutura: não perguntamos "quantos eventos chegam por minuto", mas sim "quanto em reais foi liquidado" — informação que o time financeiro precisa consultar.

---

## 2. Relógio escolhido: OCORRÊNCIA (event time)

**Justificativa:**

Escolhemos o campo `liquidadoEm` (instante em que o Pix foi liquidado no SPI) como relógio, não a hora em que o evento chegou ao broker Kafka ou em que foi processado.

**Por quê:**
- Garante que a agregação representa a realidade do domínio, não a latência do sistema
- Uma transação liquidada às 14:00:00 UTC deve aparecer na agregação das 14:00–15:00, mesmo que o evento chegue ao Kafka uma hora depois (rede lenta, reprocessamento, etc.)
- Permite reprocessamento reproduzível: se rodamos o agregador novamente com todos os eventos históricos, o resultado é idêntico

**Trade-off aceito:** A agregação reflete o momento real da transação, não o momento em que o sistema tomou conhecimento dela. Para uma visão "quando o banco soube", seria necessário um segundo agregador com processing time.

---

## 3. Comportamento de eventos atrasados

Um Pix com `liquidadoEm = 2026-08-23T14:35:00Z` que chega ao agregador às 15:50:00Z é **agregado na janela das 14:00–15:00**, não na janela das 15:50–16:50.

**O desenho:**
- Cada evento calcula sua janela com base em `liquidadoEm`
- Se a janela é nova (primeira vez nesse horário), inicia um acumulador
- Se a janela já foi processada (eventos anteriores já passaram), o evento ainda é agregado **corretamente no seu lugar temporal**

Isso significa que a janela encerrada pode "voltar a abrir" se um retardatário chegar. Em produção, isso seria controlado com um **watermark** (tempo máximo de espera), mas neste protótipo, o evento é sempre agregado no seu lugar correto.

---

## 4. Reprocessamento do zero: resultado seria o MESMO

Se reprocessássemos todos os eventos do tópico `pagamentos.pix.realizado.v1` do início (com `--from-beginning`), o resultado seria **idêntico**.

**Por quê?**
Porque a agregação depende **apenas** do `liquidadoEm`, que está dentro do evento e é imutável. Não há dependência de:
- Hora em que o agregador foi iniciado ❌
- Processing time do Kafka ❌
- Estado externo (relógio do servidor) ❌

Rodando o agregador 100 vezes, 100 vezes chegará ao mesmo resultado: 14:00–15:00 terá exatamente os mesmos Pix agregados. Isso é a definição de **event time semantics**.

**Consequência aceita:** Ganhos em reproduzibilidade (essencial para auditoria financeira) ao custo de latência potencial: uma transação liquidada agora pode demorar minutos ou horas para aparecer na agregação se o Kafka estiver congestionado. Para receita financeira, a precisão (reproduzibilidade) é mais importante que a latência.

---

## Resumo da escolha de arquitetura

- **Agregação:** valor total liquidado por hora
- **Relógio:** event time (`liquidadoEm` do evento)
- **Janela:** 1 hora, alinhada por tempo UTC
- **Reproduzibilidade:** garantida ✅
- **Tolerância a atraso:** eventos atrasados são agregados no seu lugar correto
