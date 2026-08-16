# Registro do uso de IA — Equipe 02

Cada integrante registra aqui as próprias interações, sob a seção da aula correspondente.
Formato de cada entrada: **o que foi pedido · o que a ferramenta sugeriu · o que foi aceito ·
o que foi RECUSADO e por quê**.

A recusa não é enfeite: ela é a evidência de que houve um critério do lado de cá. Registro sem
recusa é indício de que a ferramenta decidiu no lugar da equipe.

---

## Aula 02

### Allainn Christiam (1664926) — consumidor de tarifação, infraestrutura e teste

Ferramenta: Claude Code (Claude Opus). Interações de 14 e 15/08/2026.

---

#### 1. Chave de partição do `PixRealizadoEvent`

**Pedido.** Definir a chave de partição do evento, sabendo que o consumidor precisa contar
quantos Pix o cliente já fez no mês para decidir se o próximo é isento ou tarifado.

**Sugerido.** A ferramenta apresentou três caminhos: `clienteId`, `pixId` (paralelismo máximo e
distribuição perfeita entre partições) e deixar a decisão para depois da reunião de equipe.

**Aceito.** `clienteId`.

**RECUSADO — `pixId`.** Razão técnica: a decisão de isentar é um *read-then-write* sobre o
contador do cliente — lê quantos Pix já existem na competência, decide, e só então grava. Com
`pixId` como chave, dois Pix do mesmo cliente caem em partições diferentes e são processados em
paralelo por consumidores distintos do grupo; os dois leem "4 usados" contra uma franquia de 5, e
os dois saem isentos. A franquia estoura, e o bug só aparece sob concorrência — o pior tipo de
bug para se descobrir em produção. Só `clienteId` garante que todos os eventos de um cliente
caiam na mesma partição, onde a ordem é total e o processamento é serial.

**Consequência aceita, e registrada.** Cliente de volume muito alto concentra carga numa partição
(*hot partition*). Se isso aparecer, a saída é rever a granularidade da chave — não aumentar o
número de partições, porque isso rebate o hash e quebra a ordem das chaves já existentes.

*Verificação prática:* no teste de ponta a ponta, as 9 mensagens de `cli-0001` foram todas para a
partição 1 do tópico, confirmando o comportamento pretendido.

---

#### 2. Blindar a contagem da franquia com lock no banco

**Pedido.** Avaliar se o *read-then-write* da contagem precisava de proteção transacional
adicional.

**Sugerido.** `SELECT ... FOR UPDATE` na linha da tabela `oferta`, ou elevar o nível de isolamento
da transação para `SERIALIZABLE`.

**Aceito.** Nada. O código não tem lock explícito nem isolamento customizado.

**RECUSADO — o lock.** Razão técnica: a serialização já existe, e vem da chave de partição. Dentro
de um consumer group, cada partição é atribuída a no máximo um consumidor; como a chave é o
`clienteId`, os Pix de um mesmo cliente já são processados em série por definição. O lock não
acrescentaria garantia nenhuma, custaria contenção, e — o problema real — **esconderia a
dependência da chave de partição**. Alguém leria o código com lock, concluiria que a correção
está no banco, e trocaria a chave sem perceber que estava quebrando o invariante. A escolha foi
manter o mecanismo à vista e documentá-lo em comentário no `TarifacaoService`.

---

#### 3. Classes auxiliares do teste, copiadas da demonstração

**Pedido.** Escrever o teste automatizado que entrega o mesmo evento três vezes e verifica efeito
único.

**Sugerido.** Reaproveitar a estrutura do `demo-kafka-idempotencia`, com as classes de apoio
`TestPublisher` (publica JSON cru no tópico) e `EstoqueVerifier` (asserção para o Awaitility).
Essa era, inclusive, a estrutura escrita no plano inicial desta tarefa.

**Aceito.** A técnica: publicar **JSON cru** em vez de objeto Java, para que o teste exercite o
contrato do fio e não uma classe compartilhada; e o uso do Awaitility com janela de observação
para provar que a reentrega *não* muda o estado.

**RECUSADO — as duas classes.** Razão técnica: `Publisher` e `Verifier` não estão na lista fechada
de sufixos (`Application, Config, Controller, Listener, Service, Repository, Event, VO`), e a
rubrica cobra consistência de estereótipos **inclusive nos testes**. Criar duas classes fora do
padrão para economizar trinta linhas seria pagar em conformidade o que se ganharia em organização.
O publicador virou método privado e o verificador virou lambda, dentro do próprio
`IdempotenciaTest` — que ficou a única classe do diretório de testes.

---

#### 4. Tratamento do cabeçalho `ce_id` ausente

**Pedido.** Extrair a identidade do evento do envelope CloudEvents no listener.

**Sugerido.** Reproduzir o `lerCabecalho` da demonstração, que devolve `null` quando o cabeçalho
não existe e repassa esse `null` adiante.

**Aceito.** Ler a identidade do cabeçalho `ce_id`, e não do corpo — é para isso que existe o modo
binário do CloudEvents: deduplicar e rotear sem desserializar o payload.

**RECUSADO — repassar o `null`.** Razão técnica: `eventoId` nulo vira um `INSERT` de `NULL` na
chave primária da tabela de deduplicação. A transação estoura, a exceção sobe até o listener, o
offset nunca é confirmado, e a mesma mensagem é reentregue indefinidamente — travando a partição
inteira atrás de uma mensagem envenenada. Entre parar a fila e avisar, avisar é a escolha
operacionalmente correta: o `TarifacaoListener` registra um `WARN` nomeando a partição e o offset
da mensagem fora do contrato, e usa o `eventoId` do corpo como rede de segurança.

---

#### 5. Material de outra disciplina oferecido como apoio

**Pedido.** Aproveitar uma transcrição de aula de 175 KB, indicada como contendo orientações do
professor sobre este trabalho.

**Sugerido.** Nada — a verificação veio antes.

**Aceito.** Nada do arquivo.

**RECUSADO — o material inteiro.** Razão técnica: a transcrição é da disciplina *Arquitetura
Cloud-Native e Soluções Serverless* (Prof. Douglas Jardim, 05/08), sobre AWS, EC2 e disaster
recovery — não da AED. A busca textual por `ADR`, `IA.md`, `domínio`, `Pix` e `tarifação` no
arquivo não retornou nenhuma ocorrência ligada a esta entrega. Usar conteúdo de outra ementa como
fundamento arquitetural teria introduzido vocabulário e critérios que o enunciado não pede. O
material que de fato faltava foi identificado e pedido: a **Seção 12 das Notas de Aula da Aula
02**, que o enunciado cita como fonte do padrão completo de pacotes.

---

#### 6. O plano padrão para cliente sem contrato — a recusa que mudou o código

**Pedido** (15/08, depois que o ADR-002 subiu). Conferir se o consumidor atende o que o ADR
decidiu sobre o fluxo *tarifar*, e alinhar o que estivesse divergente.

**Sugerido, na véspera.** Ao escrever o `TarifacaoRepository` em 14/08, a ferramenta propôs — e a
equipe aceitou sem confrontar com o domínio — um plano padrão para cliente sem linha na tabela
`oferta`: 5 Pix grátis e R$ 1,90 de tarifa, com a justificativa de que "um Pix de cliente
desconhecido não pode derrubar o consumidor e travar a partição". O argumento é bom de
disponibilidade, e passou despercebido por isso.

**RECUSADO — o plano padrão inteiro.** Razão técnica: o ADR-002 decide o oposto, e por um motivo
que não é de arquitetura. *"Empresa sem contrato de tarifação vigente na data de competência não é
cobrada. Não há tabela padrão de fallback; cobrar sem contrato é cobrança indevida, com exposição
a devolução em dobro (CDC, art. 42, parágrafo único)."* O fallback transformava um erro de
cadastro numa **cobrança indevida ao cliente** — e o pior é que funcionava: não derrubava nada, os
testes passavam, e o defeito só apareceria na fatura de alguém. A preocupação com a partição
travada é legítima, mas a resposta certa a ela é registrar o Pix com situação `SEM_CONTRATO` e
valor zero, não inventar um contrato que ninguém assinou.

**Também recusado — corrigir o ADR em vez do código.** Era a saída barata: apagar do ADR a frase
sobre não haver fallback e deixar o código como estava. Recusada porque aquela frase é a regra que
sustenta o recorte do domínio; removê-la para acomodar uma implementação apressada é escolher o
domínio de trás para a frente, exatamente o que o enunciado adverte na Parte A.

**Aceito.** Alinhar o código ao ADR, e não o contrário:

- `SEM_CONTRATO` como quarta saída da política, com valor zero e **sem consumir franquia**;
- a coluna `situacao` em `tarifa`, porque três das quatro saídas valem `0.00` e significam coisas
  diferentes — sem ela o extrato ficaria ambíguo justamente onde a auditoria precisa de clareza;
- a oferta passou a ter **vigência**, e a busca é pela vigente *na competência do evento*. O ADR
  diz "vigente" duas vezes, e sem isso um replay de agosto feito em outubro encontraria o contrato
  errado — o fechamento mensal, que é o quarto critério do domínio, deixaria de ser reproduzível;
- as outras duas saídas que o ADR declara e o código não tinha: **tarifação por faixa de valor** e
  **teto mensal atingido**. É o "aprova, recusa e limita" do critério 1.

**Efeito colateral, e ele importa.** Um teste automatizado já verde — o `cli-9999` recebendo
R$ 1,90 no sexto Pix — estava *provando o comportamento errado*. Foi reescrito como
`clienteSemContratoNaoECobrado`. Um teste que passa não é evidência de que a regra está certa: é
evidência de que o código faz o que o teste diz, e aquele teste tinha sido escrito a partir do
código, não a partir do ADR.

*Onde isso aparece:* `SituacaoDaTarifaVO`, `DecisaoDeTarifacaoVO`, `FaixaDeTarifaVO`, o
`buscarOfertaVigente` do repositório e os testes 7 a 11 do `IdempotenciaTest`.

---

#### 7. Confrontar o código com a especificação da regra — dois defeitos que os testes não pegavam

**Pedido** (15/08, ao receber de Evandro o `regra-de-tarifacao.md`). Comparar o consumidor com a
especificação detalhada e alinhar o que divergisse.

**Sugerido.** A ferramenta comparou item a item e apontou seis divergências, duas delas defeitos de
cálculo — as outras quatro eram nomenclatura e dados de exemplo.

**Aceito — o estouro do teto cobrado parcialmente.** A regra tem um passo que o código não tinha:
quando a tarifa não cabe no espaço restante do teto, cobra-se *o que cabe*, não a tarifa inteira e
não zero. Com teto de R$ 2.000,00 e R$ 1.995,00 já cobrados, um Pix de R$ 6.000,00 (faixa
R$ 10,00) fechava o mês em R$ 2.005,00 — **acima do teto contratado**. A invariante
`valorTarifadoNaCompetencia ≤ teto` que a especificação declara simplesmente não era garantida.

**Aceito — a fronteira das faixas é exclusiva.** O código tratava o limite superior como inclusivo
(`valor <= 500`); a especificação define "limite inferior inclusivo, superior exclusivo". A
diferença aparece exatamente nos valores redondos, que são os mais comuns numa transferência: um
Pix de R$ 500,00 pagava R$ 0,50 e deveria pagar R$ 1,00. A coluna foi renomeada de `valor_ate`
para `valor_abaixo_de`, porque "até" se lê como inclusivo e foi assim que o erro entrou.

**Aceito — só o Pix isento consome franquia.** O código incrementava a contagem também nos
tarifados. A decisão saía igual (uma vez atingido o limite, o contador para nele), mas o acumulado
crescia sem parar e violava a invariante `unidadesFranquiaConsumidas ≤ franquia do plano` — e é
esse acumulado que o relatório de fechamento lê para dizer quantas isenções o contrato concedeu.

**RECUSADO — renomear o contrato do fio para o vocabulário da especificação.** A especificação usa
`idEmpresa`, `idTransacaoPix` e `liquidadoEm`; o evento publicado usa `clienteId`, `pixId` e
`ocorridoEm`. Razão técnica da recusa: o contrato já está publicado pelo `servico-pix` e consumido
aqui, e renomear os três campos quebraria os dois lados — produtor, consumidor, schema e as duas
baterias de teste — **sem mudar uma única regra de negócio**. Custo alto, benefício zero em
comportamento, e a um dia do prazo. O README do consumidor ganhou uma tabela de equivalência entre
os dois vocabulários, que resolve o problema real (quem lê a spec e o código não se perder) sem
tocar em código que funciona.

**RECUSADO — implementar a compensação junto.** A especificação traz a seção de compensação inteira
(estorno, `unidadesFranquiaEstornadas`, `valorEstornadoNaCompetencia`, `PixMarcadoNaoTarifavel`).
Recusada porque é a saga da aula 05, e a própria especificação marca esses dois acumuladores como
"lidos apenas pelo fechamento", invisíveis para a ordem de avaliação. Implementá-los agora seria
carregar estado que nada nesta etapa consome — e o ADR-002 já reserva compensar e fechar para
iterações posteriores.

**A lição que se repete.** É a segunda vez nesta entrega que um teste verde estava certificando um
comportamento errado. Na interação 6 era o plano padrão; aqui eram o teto e a fronteira das faixas.
Nos dois casos o teste tinha sido escrito a partir do código, e não da regra — um teste derivado da
implementação só prova que a implementação é ela mesma. Os testes 11, 12 e 13 nasceram da
especificação, não do código, e foram escritos para falhar antes de passar.

---

<!--
  Demais integrantes: acrescentem a sua subseção abaixo, no mesmo formato
  (### Nome (matrícula) — parte pela qual respondeu).
-->
