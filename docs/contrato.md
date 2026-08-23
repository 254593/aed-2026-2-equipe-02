# Contrato do Evento: pagamentos.pix.realizado.v1

## Identificação

**Tipo do evento:** `pagamentos.pix.realizado.v1`

**Tópico Kafka:** `pagamentos.pix.realizado.v1`

**Produtor:** `servico-pix` — Serviço responsável por registrar e publicar eventos de Pix liquidados

## Campos da Carga

| Campo | Tipo | Obrigatório | Significado |
|-------|------|-------------|------------|
| `eventoId` | String | Sim | Identificação única e imutável do evento; chave de deduplicação que garante idempotência: envios repetidos do mesmo Pix produzem o mesmo evento e o consumidor descarta as reentregas |
| `liquidadoEm` | Instant (ISO-8601) | Sim | Data e hora em que o Pix foi liquidado pelo Sistema de Pagamentos Instantâneos (SPI) do Banco Central; marca o momento real da transação, não quando o evento foi criado |
| `idTransacaoPix` | String | Sim | Identificador único da transação dentro do SPI; permite rastrear a transação original do Pix no sistema do Banco Central |
| `idEmpresa` | String | Sim | Identificação única da empresa que recebeu o Pix; determina qual empresa será tarifada e é a chave de partição do evento |
| `valor` | BigDecimal | Não | Valor monetário do Pix em reais; pode ser omitido em cenários específicos, mas quando presente é essencial para o cálculo de tarifa |
| `chavePix` | String | Não | A chave Pix utilizada para realizar a transferência (por exemplo: email, CPF, CNPJ, telefone ou chave aleatória); facilita reconciliação e análise de padrões de uso |
| `tipoChave` | String | Não | Classificação da chave utilizada: EMAIL, CPF, CNPJ, TELEFONE ou ALEATORIA; permite segmentação de análises por tipo de chave |
| `bancoDestino` | String | Não | Código ISPB do banco de destino da transação; possibilita análises de fluxo por instituição financeira |
| `endToEndId` | String | Não | Identificador end-to-end único gerado pelo SPI para a transação; referência padrão do Banco Central para rastreabilidade |
| `pagadorNome` | String | Não | Nome do correntista pessoa ou razão social que realizou o pagamento; auxilia em auditoria e rastreamento, mas não é usado em decisões críticas |

## Formato das Datas

Todos os instantes de tempo são representados em formato **ISO-8601** com timezone UTC explícito. Exemplo: `2026-08-23T14:30:45.123Z`

Nunca é utilizado epoch (timestamp Unix em milissegundos ou segundos). ISO-8601 mantém legibilidade e evita ambiguidades de timezone.

## Chave de Partição

**Chave de partição:** `idEmpresa`

**Garantia de ordem:** Todos os Pix de uma mesma empresa seguem na mesma partição, garantindo que eventos são processados em ordem de chegada por empresa. Isso é essencial para a tarifação, onde a sequência de eventos afeta o cálculo de franquia mensal.

## Compatibilidade

**Regra escolhida:** BACKWARD

**Justificativa:** O consumidor (`servico-tarifacao`) processa eventos já publicados e novos simultaneamente. Caso novos campos opcionais sejam adicionados ao evento no futuro, consumidores antigos precisam continuar lendo eventos antigos. A compatibilidade BACKWARD garante que o consumidor antigo consegue desserializar dados novos (ignorando campos desconhecidos) e que o produtor pode ser atualizado primeiro sem coordenar janela de manutenção.

**Nota sobre mudanças perigosas:** Se o campo `valor` mudar de significado para incluir frete, por exemplo, o tipo permanece `BigDecimal` e o schema continua válido — mas o contrato foi violado, sem aviso técnico. Por isso, o significado de cada campo está explicitamente documentado neste contrato. Uma mudança de significado requer mudança de versão (`v2`), não apenas adição de campo.

## Exemplo de Carga

```json
{
  "eventoId": "a1b2c3d4-e5f6-47g8-h9i0-j1k2l3m4n5o6",
  "liquidadoEm": "2026-08-23T14:30:45.123Z",
  "idTransacaoPix": "pix-20260823-001",
  "idEmpresa": "empresa-987654",
  "valor": 1250.50,
  "chavePix": "nfe@empresa.com.br",
  "tipoChave": "EMAIL",
  "bancoDestino": "001",
  "endToEndId": "E00123456789012340002681202608231430123456789",
  "pagadorNome": "Empresa Ficticia LTDA"
}
```

Todos os valores acima são fictícios e seguem o padrão de dados de teste do domínio de Pix.
