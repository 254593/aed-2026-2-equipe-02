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
| `valor` | BigDecimal | Sim | Valor monetário do Pix liquidado, em reais, com duas casas decimais; é o montante transferido, **não** inclui a tarifa nem qualquer encargo. O `servico-pix` recusa a publicação com HTTP 400 se ausente ou menor ou igual a zero |
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

**Garantia de ordem:** todos os Pix de uma mesma empresa caem na mesma partição e são consumidos em série por um único consumidor do grupo, na ordem em que foram publicados. A garantia é **por empresa**, não global: entre empresas diferentes não há ordem alguma, e o desenho não depende disso.

Isso é essencial para a tarifação, onde a decisão de isentar é um *read-then-write* sobre o consumo do mês: dois Pix da mesma empresa em partições diferentes seriam processados em paralelo, ambos leriam o mesmo consumo e ambos sairiam isentos, estourando a franquia.

Para o agregador a ordem é indiferente — soma é comutativa —, mas ele herda a mesma chave por consumir o mesmo tópico.

## Compatibilidade

**Regra escolhida:** FORWARD

**Justificativa em uma frase:** há **um produtor e dois consumidores** (`servico-tarifacao` e `servico-agregador-pix`), então a regra que serve é a que deixa o **produtor subir primeiro** e cada consumidor acompanhar no próprio ritmo — que é exatamente FORWARD: consumidor VELHO lê dado escrito pelo NOVO.

**Por que não BACKWARD.** BACKWARD exigiria atualizar **todos** os consumidores antes do produtor. Com dois consumidores hoje, e o Faturamento previsto como terceiro, isso significa coordenar uma janela de implantação a cada campo novo. FORWARD elimina essa coordenação: o produtor acrescenta o campo, e quem ainda não sabe dele simplesmente o ignora.

**E é o que o código já sustenta.** Os dois consumidores declaram **menos campos** do que o produtor publica e usam `@JsonIgnoreProperties(ignoreUnknown = true)`. O `servico-tarifacao` declara 5 dos 10 campos; o `servico-agregador-pix`, 4. Acrescentar um campo ao evento não quebra nenhum dos dois, e há teste automatizado provando isso em cada um.

**O que FORWARD nos proíbe:** remover um campo obrigatório ou renomeá-lo. Isso quebraria consumidores velhos, e exige `v2` do tipo — nunca alteração no `v1`.

**Nota sobre mudanças perigosas:** Se o campo `valor` mudar de significado para incluir frete, por exemplo, o tipo permanece `BigDecimal` e o schema continua válido — mas o contrato foi violado, sem aviso técnico. Por isso, o significado de cada campo está explicitamente documentado neste contrato. Uma mudança de significado requer mudança de versão (`v2`), não apenas adição de campo.

## Exemplo de Carga

```json
{
  "eventoId": "a1b2c3d4-e5f6-4a78-b9c0-d1e2f3a4b5c6",
  "liquidadoEm": "2026-08-23T14:30:45.123Z",
  "idTransacaoPix": "pix-20260823-001",
  "idEmpresa": "emp-0001",
  "valor": 1250.50,
  "chavePix": "nfe@exemplo.com.br",
  "tipoChave": "EMAIL",
  "bancoDestino": "001",
  "endToEndId": "E00123456789012340002681202608231430123456789",
  "pagadorNome": "Empresa Ficticia LTDA"
}
```

Todos os valores acima são fictícios. O `idEmpresa` segue o padrão `emp-NNNN` da carga de exemplo
do `servico-tarifacao`, e o `eventoId` é um UUID v4 válido — o exemplo é copiável e funciona.

## Quem consome este evento

| Consumidor | Grupo | Campos que declara | Para quê |
|---|---|---|---|
| `servico-tarifacao` | `tarifacao` | 5 de 10 | decide isenção, faixa e teto por competência |
| `servico-agregador-pix` | `agregador-pix-por-hora` | 4 de 10 | soma o liquidado por hora, por event time |

Os dois leem o **mesmo tópico** em **grupos distintos**: cada um tem o próprio ponteiro de leitura e
nenhum consome a mensagem do outro. Nenhum dos dois declara os dez campos, e é isso que sustenta a
regra FORWARD acima.
