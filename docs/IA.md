# Registro do uso de IA — Equipe 02

Cada integrante registra aqui as próprias interações, sob a seção da aula correspondente.
Formato de cada entrada: **o que foi pedido · o que a ferramenta sugeriu · o que foi aceito ·
o que foi RECUSADO e por quê**.

A recusa não é enfeite: ela é a evidência de que houve um critério do lado de cá. Registro sem
recusa é indício de que a ferramenta decidiu no lugar da equipe.

---

## Aula 02

### Allainn Christiam (254337) — consumidor de tarifação, infraestrutura e teste

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

*Verificação prática:* no teste de ponta a ponta, as 9 mensagens de `emp-0001` foram todas para a
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

#### 8. A recusa da interação 7, revertida — e por que a reversão também é uma decisão

**Pedido** (16/08). Sincronizar os campos entre consumidor, publicador e documentos, adotando em
todos o vocabulário da especificação.

**O que isso contradiz.** A interação 7, do dia anterior, registra a recusa de fazer exatamente
isto. Aquela recusa não é apagada aqui de propósito: ela mostra o critério que existia no momento —
custo alto, benefício zero em comportamento, véspera de prazo — e este registro mostra por que o
critério mudou.

**Por que a recusa não se sustentou.** O argumento era que renomear quebraria os dois lados sem
mudar uma regra de negócio. A parte factual estava certa: nenhuma regra mudou, e os 25 testes
passaram antes e depois. O erro estava em tratar "não muda comportamento" como se fosse "não tem
benefício". Duas grafias para a mesma coisa é o erro que a seção 9 do enunciado lista como fácil de
evitar, e o custo dele não aparece em teste nenhum — aparece em cada leitura em que alguém precisa
traduzir `idEmpresa` da spec para `clienteId` do código. A tabela de equivalência que eu tinha
proposto como paliativo era, ela mesma, a prova do problema: um documento que existe só para
traduzir dois nomes da mesma coisa.

**Aceito.** `clienteId` → `idEmpresa`, `pixId` → `idTransacaoPix`, `ocorridoEm` → `liquidadoEm`,
no contrato do fio, nas classes dos dois serviços, nas colunas das quatro tabelas, no ADR-002 e nos
READMEs. A prosa acompanhou: onde se lia "cliente", agora se lê "empresa", porque o domínio é de
contas PJ.

**RECUSADO — renomear também a classe e o tópico.** A especificação chama o fato de `PixLiquidado`
e o log de `pix.liquidado`; aqui seguem `PixRealizadoEvent` e `pagamentos.pix.realizado.v1`. Razão
técnica: o `ce_type` acompanha o nome do tópico, e trocá-lo invalidaria os offsets do grupo
`tarifacao` e todos os comandos colados nos READMEs e nos scripts — sem que nada disso melhore a
leitura, já que o nome do evento é coerente e está no particípio nas duas grafias. Fica anotado
como dívida no README do consumidor, para ser paga numa etapa em que o tópico já vá mudar por outro
motivo.

**O que a ferramenta fez de errado aqui, e vale registrar.** O rename mecânico por `sed` trocou os
identificadores mas deixou a prosa dos comentários falando em "cliente", e criou uma concordância
quebrada ("dois Pix do mesma empresa") e um trecho de README que passou a afirmar o contrário do
que o commit fazia — "o contrato do fio não foi renomeado de propósito". Nada disso quebra teste, e
por isso nada disso seria pego por uma bateria verde. Foi encontrado relendo os arquivos.

---

#### 9. Code review do próprio código — a identidade do evento tinha duas fontes

**Pedido** (16/08). Rodar teste, revisão de código e conferência do enunciado antes de fechar a
entrega.

**Encontrado.** Um defeito que os 25 testes verdes não pegavam, e que a ferramenta havia escrito
sem perceber. A identidade do evento vinha de **duas fontes diferentes**:

- `evento_processado.evento_id` ← o `eventoId` resolvido pelo `TarifacaoListener`, que é o `ce_id`
  do envelope (com o corpo apenas como rede de segurança);
- `tarifa.evento_id` ← `evento.getEventoId()`, **sempre do corpo**.

Com um produtor fora do contrato — `ce_id` diferente do `eventoId` do corpo — duas mensagens
passariam pela deduplicação (dois `ce_id` distintos, ambos novos) e colidiriam na chave primária da
`tarifa`, que já teria a identidade do corpo. A transação faz rollback, a exceção sobe ao listener,
o offset nunca é confirmado, e a **partição inteira trava em retentativa** atrás de uma mensagem
envenenada.

O agravante: é exatamente o modo de falha que o javadoc do próprio `TarifacaoListener` diz querer
evitar, oito linhas acima, ao justificar por que o `ce_id` ausente não pode virar `null`. O
raciocínio estava certo e escrito; a implementação o contrariava no arquivo ao lado.

**Aceito.** `registrarTarifa` passou a receber o `eventoId` resolvido como parâmetro, em vez de
tirá-lo do corpo. Uma identidade, uma fonte.

**Por que os testes não pegaram.** Todos publicavam `ce_id` igual ao `eventoId` do corpo — como o
`servico-pix` faz, e como o contrato exige. A bateria inteira exercitava só o caminho em que as
duas fontes coincidem, e por isso a duplicidade era invisível. O teste 7
(`ceIdDivergenteDoCorpoNaoTravaAParticao`) foi escrito para o caminho que faltava, e **verificado
contra o código antigo**: reintroduzindo o defeito, ele falha por `ConditionTimeout` — a segunda
linha nunca aparece porque a partição travou. Só depois passou com a correção.

**A lição, pela terceira vez nesta entrega.** Nas interações 6 e 7 o problema era teste escrito a
partir do código. Aqui é a variação mais silenciosa: teste escrito a partir do **caminho feliz do
contrato**. Uma bateria verde prova que o código faz o que os testes exercitam — não que o código
está correto para as entradas que ele vai receber de fato.

---

### Jhonathan Carvo (2582390) — documentação e revisão do projeto

Ferramenta: GitHub Copilot. Interação em 16/08/2026.

#### 1. Revisão de documentação e clareza de execução

**Pedido.** Melhorar a legibilidade do README principal para facilitar a execução e a compreensão do projeto sem precisar ler o código inteiro.

**Sugerido.** A ferramenta propôs organização por quick start, troubleshooting, fluxo do sistema e resumo do contrato do evento Kafka.

**Aceito.** A estrutura simplificada, os passos de execução rápida e a explicação do contrato do evento.

**Resultado.** O README ficou mais acessível para onboarding, revisão e apresentação final, preservando o comportamento do sistema intacto.

---

---

### Evandro V. Junior (254593) — ADR-002, especificação da regra e idempotência do publisher

#### 10. Como demonstrar manualmente a deduplicação

**O que foi pedido.** Habilitar a verificação manual exigida pelo item 5 do checklist: o mesmo
evento entregue três vezes produzindo efeito uma vez.

**O que a ferramenta sugeriu.** Um script PowerShell publicando direto no tópico, com `docker exec`
e `kafka-console-producer` usando `ce_id` fixo — já que o `eventoId` era gerado no servidor pelo
`PixService`, e repetir o POST criava sempre um evento novo.

**O que foi aceito.** Tornar o `eventoId` **opcional no request**: informado, vira a identidade do
fato publicado e o POST repetido produz o mesmo evento; ausente, o serviço gera um UUID. Aceito
também o argumento da ferramenta de que isso não é conveniência de teste — hoje um retry do POST por
timeout publicava dois eventos distintos para o mesmo Pix, ambos passavam pela deduplicação (que é
pelo `eventoId`) e a empresa era cobrada duas vezes, exatamente a cobrança indevida que o ADR-002
existe para impedir. E aceita a ressalva de **não** incluir o campo no `pix-exemplo.json`: com uma
chave fixa ali, quem repetisse o comando do README veria Pix reais descartados como reentrega.

**O que foi RECUSADO — publicar direto no broker.** Razão técnica: contorna a aplicação em vez de
exercitá-la. A verificação manual passaria a validar o consumidor com uma mensagem que o publisher
nunca produziu, deixando o caminho real sem cobertura; e amarraria a demonstração ao Docker e ao
nome do container. Pior: o defeito de idempotência do POST continuaria existindo, agora encoberto
por um teste que passa.

---

#### 11. Anonimizar a regra vinda da operação real

**O que foi pedido.** Adaptar a política de tarifação trazida da experiência profissional para o
repositório público, sem expor regra interna nem incorrer em quebra de sigilo.

**O que a ferramenta sugeriu.** Trocar os nomes — empresa, sistema interno, cliente — por fictícios
e manter o resto da regra como estava, apoiando-se no aviso do enunciado de que "a regra de negócio
é o que se avalia, e ela sobrevive à troca de nomes".

**O que foi aceito.** A troca de nomes em si (`Core de Pagamentos`, `Parceiro de Lançamentos`,
`Cadastro de Ofertas`, `emp-NNNN`) e a exigência de dados fictícios em toda carga de exemplo.

**O que foi RECUSADO — manter a regra da operação real, apenas com nomes trocados.** Razão técnica:
o que identifica a operação não é o nome do sistema, é a própria regra — a sequência de passos, os
pontos de decisão e a calibragem dos limites. Nome trocado sobre regra intacta é anonimização
aparente. A saída foi **substituir por uma regra mais simples, com menos passos**: franquia, faixa e
teto, três verificações em sequência, no lugar do fluxo original. O recorte mais curto preserva o
que o enunciado avalia — decisão sobre estado acumulado, que aprova, limita e recusa — e descarta o
que pertence à empresa; e, de quebra, é defensável na apresentação e implementável no prazo.

---

#### 12. Compensação onde bastava resiliência

**O que foi pedido.** Propor o caminho de exceção com compensação exigido pelo terceiro critério do
domínio.

**O que a ferramenta sugeriu.** Compensar quando o débito no Parceiro de Lançamentos falhasse:
desfazer o efeito local e devolver a franquia consumida.

**O que foi aceito.** Nada dessa proposta. O gatilho adotado foi outro: recusa do Faturamento por
**contrato de tarifação inativo na competência**.

**O que foi RECUSADO — compensar por indisponibilidade do parceiro.** Razão técnica: indisponibilidade
se resolve com **retry com backoff e DLQ**, não com compensação — repetir muda o resultado, e onde
repetir resolve não há saga. Essa fronteira é **regra do domínio**, não escolha de implementação, e
está declarada no ADR-002: recusa por contrato inativo é definitiva, repetir não muda o resultado, o
que a distingue da indisponibilidade do parceiro, que é retry e DLQ. É também onde aparece a
**vantagem do assíncrono**: o fato permanece no tópico, o offset não avança, o consumidor tenta de
novo quando o parceiro voltar, e nada precisa ser desfeito. Num fluxo síncrono a falha do parceiro
derrubaria a operação e exigiria desfazer o que já tinha sido feito; no assíncrono ela vira espera.
Modelar compensação para esse caso importaria para a arquitetura orientada a eventos um problema que
ela já resolve, e produziria uma saga disparada por falha de infraestrutura — confundindo recuperação
de falha com regra de negócio.

---

#### 13. Devolução de Pix apresentada como transação compensatória

**O que foi pedido.** Revisar a ADR contra os quatro critérios da Parte A e apontar o que faltava.

**O que a ferramenta sugeriu.** Por conta própria, ao apontar que o critério de compensação estava
em aberto, ofereceu a **devolução do Pix** (devolução comum ou MED) como o caminho de compensação do
domínio: Pix devolvido, tarifa estornada. A devolução não estava no recorte proposto pela equipe —
entrou na conversa pela ferramenta.

**O que foi aceito.** O reconhecimento de que a devolução existe no domínio e precisa de tratamento —
registrada nas Consequências aceitas do ADR-002 como fato de negócio posterior, com processo próprio,
fora do escopo desta etapa.

**O que foi RECUSADO — classificá-la como compensação de saga.** Razão técnica: transação
compensatória desfaz uma etapa já commitada quando uma etapa **posterior da mesma saga** falha. A
devolução não tem nenhuma dessas propriedades: chega dias depois (no MED, até 80), sobre uma saga que
**terminou com sucesso**, e é disparada por um fato novo, não por falha de etapa — não há instância
de saga aberta esperando por ela. É um estorno: operação de negócio de primeira classe, caminho feliz
de outro processo. Some-se a razão de escopo: modelar a devolução acrescentaria um fluxo inteiro —
janela do MED, disputa, reversão no parceiro — **sem ganho para o que este exercício avalia**, já que
o caminho de compensação já está coberto pela recusa por contrato inativo. Por isso foi
desconsiderada neste momento.

---

## Aula 03

### Jhonathan Carvo (2582390) — documentação e agregador

Ferramenta: Claude Haiku 4.5 (GitHub Copilot). Interações de 23/08/2026.

---

#### 1. Escolha do relógio para a agregação: event time vs processing time

**Pedido.** Definir qual relógio usar na agregação de Pix por hora: o instante da liquidação (`liquidadoEm` do evento) ou o momento do processamento (hora do consumidor)?

**Sugerido.** Apresentadas ambas as opções com trade-offs:
- **Processing time:** mais simples de implementar, latência mínima (evento aparece na agregação quase instantaneamente)
- **Event time:** mais complexo, mas garante reproduzibilidade (se reprocessar, o resultado é idêntico) e reflete a realidade do domínio

**Aceito.** Event time (`liquidadoEm`). Razão: em um contexto de tarifação e faturamento, a precisão sobre *quando realmente aconteceu* é mais importante que a latência de *quando ficamos sabendo*. Uma transação liquidada às 14:00 UTC deve contar para o faturamento das 14:00–15:00, mesmo que chegue com atraso.

**RECUSADO — processing time.** Razão de negócio: usar a hora do processador como realidade violaria o contrato do faturamento. A fatura de um cliente deve refletir as transações que *realmente ocorreram* naquele dia, não as que *o sistema conheceu naquele dia*. Rede lenta, reprocessamento ou até uma semana de downtime não podem mudar retroativamente a fatura de ontem. A consequência aceita é que a agregação pode ter latência — o que é aceitável e até desejável em um sistema contábil.

---

#### 2. Implementação usando Kafka Streams vs agregação manual

**Pedido.** Como implementar a agregação por hora com qualidade de produção?

**Sugerido.** Duas caminhos:
- **Kafka Streams:** framework específico para streaming, com janelas alinhadas, state store, watermark automático
- **Agregação manual em memória:** implementada no listener, mais simples para prototipagem, mas sem persistência

**Aceito.** Agregação manual em memória **como prototipagem para demonstrar o conceito**. Mantém o foco no entendimento da janela alinhada por tempo e da diferença entre relógios, sem a complexidade adicional do framework.

**Recusado — Kafka Streams como entrega.** Razão técnica: o enunciado não pede Kafka Streams, e implementá-lo aqui adicionaria dependências, configuração de RocksDB e state store sem agregação adicional de conhecimento *desta etapa*. O desafio (opcional) da Aula 03 oferece Kafka Streams como bônus para quem quiser. A implementação manual **deixa visível** a mecânica de alinhamento de janela, que é o que a avaliação pede.

**Nota implementada:** comentários no código de produção deixam clara a rota para produção (persistência em RocksDB, watermark, rebalanceamento), diferenciando este protótipo do que seria necessário em produção.

---

#### 3. Significado de cada campo do contrato: evitar ambiguidade

**Pedido.** Documentar o contrato do evento com campos, tipos e obrigatoriedade.

**Sugerido.** Tabela padrão: campo, tipo, obrigatório/opcional. Conciso, suficiente.

**Aceito.** Tabela com campos, tipos, obrigatoriedade **e SIGNIFICADO em frase**, como o enunciado destaca. Aditivamente: nota sobre mudanças perigosas (exemplo: se `valor` começar a incluir frete, o esquema aceita, mas o contrato foi violado).

**Recusado — significado como uma palavra.** Razão de comunicação: `valor: "O valor do Pix"` não esclarece: é o valor líquido? Inclui tarifa? E depois? A frase completa *"Valor monetário do Pix em reais; pode ser omitido em cenários específicos, mas quando presente é essencial para o cálculo de tarifa"* deixa claro o escopo e a consequência de ausência. Essa é exatamente a mudança de contrato disfarçada de mudança de implementação que o enunciado exemplifica.

---

#### 4. Pergunta de negócio para a agregação

**Pedido.** Qual pergunta a agregação deveria responder?

**Sugerido.** Três opções:
- "Quantos Pix liquidados por hora?" (simples, responde infraestrutura)
- "Quanto foi liquidado em reais por hora?" (alinha com receita e faturamento)
- "Qual a média de Pix por hora?" (análise de padrão)

**Aceito.** "Quanto foi liquidado em Pix por hora?" (segunda opção). Razão de negócio: responde a uma pergunta que alguém do faturamento faria: *"Qual foi meu volume de receita a cada hora?"* É informação que alimenta diretamente o sistema de tarifação e faturamento.

**Recusado — "Quantos eventos por minuto."** Razão: é métrica de infraestrutura, não de negócio. Responde-se com processing time sem pensar, e não exercita a escolha do relógio, que é o ponto central da aula.

---

#### 5. Idempotência do agregador: deduplicar por `eventoId`

**Pedido.** O agregador soma Pix reentregues (R$ 150 x 3 entregas = R$ 450). O enunciado não pede explicitamente idempotência. Manter como está ou implementar?

**Sugerido.** Três caminhos:
1. Deduplicar por `eventoId` (Set em memória) — **recomendado**
2. Documentar como consequência aceita e deixar como está
3. Deduplicar + documentar a decisão

**Aceito.** Opção 3: **Deduplicar e documentar.** Razão de coerência: a etapa anterior (`servico-tarifacao`) deduplica por `eventoId` para não cobrar 2x. O agregador deve manter o **mesmo princípio**: contar Pix únicos, não entregas.

A métrica "quanto foi liquidado" é pergunta de negócio que alimenta faturamento. Faturar R$ 450 por um Pix de R$ 150 reentregue invalidaria a métrica — é pior descartar a reentrega sem avisar. O Set de `eventosAgregados` garante idempotência com custo de memória proporcional ao volume diário.

**RECUSADO — opção 2, deixar errado e documentar.** Razão técnica: O slide específico do professor (**"O agregador soma reentregas"**) é proposital — é para a equipe reconhecer o defeito e corrigi-lo, exatamente como fizemos com a `SituacaoDaTarifa` na etapa 2 (quando o código contradisse o ADR). Aceitar silenciosamente o número errado é incompatível com "decisão de verdade" que o enunciado pede.

---

- `SEM_CONTRATO` como quarta saída da política, com valor zero e **sem consumir franquia**;
- a coluna `situacao` em `tarifa`, porque três das quatro saídas valem `0.00` e significam coisas
  diferentes — sem ela o extrato ficaria ambíguo justamente onde a auditoria precisa de clareza;
- a oferta passou a ter **vigência**, e a busca é pela vigente *na competência do evento*. O ADR
  diz "vigente" duas vezes, e sem isso um replay de agosto feito em outubro encontraria o contrato
  errado — o fechamento mensal, que é o quarto critério do domínio, deixaria de ser reproduzível;
- as outras duas saídas que o ADR declara e o código não tinha: **tarifação por faixa de valor** e
  **teto mensal atingido**. É o "aprova, recusa e limita" do critério 1.

**Efeito colateral, e ele importa.** Um teste automatizado já verde — o `emp-9999` recebendo
R$ 1,90 no sexto Pix — estava *provando o comportamento errado*. Foi reescrito como
`clienteSemContratoNaoECobrado`. Um teste que passa não é evidência de que a regra está certa: é
evidência de que o código faz o que o teste diz, e aquele teste tinha sido escrito a partir do
código, não a partir do ADR.

*Onde isso aparece:* `SituacaoDaTarifaVO`, `DecisaoDeTarifacaoVO`, `FaixaDeTarifaVO`, o
`buscarOfertaVigente` do repositório e os testes 7 a 11 do `IdempotenciaTest`.

---

### Allainn Christiam (254337) — revisão da etapa 2 e correção do agregador

Ferramenta: Claude Code (Claude Opus). Interações de 23/08/2026.

---

#### 1. Revisar a etapa 2 antes da tag — o agregador não desserializava nada

**Pedido.** Conferir se a entrega da aula 03 atende ao enunciado, antes de fechar a tag.

**Encontrado, e não sugerido.** O `servico-agregador-pix` não conseguia ler uma única mensagem do
produtor real. O `servico-pix` publica com `setAddTypeInfo(false)` — sem o cabeçalho `__TypeId__` —,
e o agregador estava configurado com `spring.json.type.mapping`, que **depende** desse cabeçalho:

```
IllegalStateException: No type information in headers and no default type provided
```

Pior que falhar: o `DefaultErrorHandler` não trata `SerializationException` ("cannot process
'SerializationException's directly"), então a mensagem era reentregue para sempre e a partição
travava.

**Como apareceu.** Escrevendo um teste que publica **JSON cru**, do jeito que o produtor publica. A
bateria do agregador não existia; se tivesse sido escrita publicando objeto Java, teria passado e
provado que o serviço funciona com um produtor que não existe.

**Aceito.** `spring.json.use.type.headers: false` mais `value.default.type`, como o
`servico-tarifacao` já fazia desde a etapa 1, e `ErrorHandlingDeserializer` envolvendo o
`JsonDeserializer` — carga malformada passa a custar um registro, não a partição.

---

#### 2. A agregação perdia o acumulado a cada troca de hora

**Encontrado.** O listener guardava **uma** `agregacaoAtual`, não um mapa de janelas. Um evento de
hora diferente descartava o acumulado, e um retardatário **reiniciava a janela do zero**:

```
14:10  R$100  ->  janela 14h: 100
14:20  R$200  ->  janela 14h: 300
15:05  R$50   ->  janela 15h: 50
14:30  R$400  ->  janela 14h: 400     <- os 300 sumiram
```

O `docs/entregas/aula-03.md` afirmava que o retardatário era "agregado no seu lugar correto". O
código não fazia isso — e com três partições e várias empresas publicando, horas diferentes se
intercalam o tempo todo, então o defeito não dependia de retardatário para aparecer.

**Aceito.** `ConcurrentHashMap` de janelas, com `compute` atômico por chave. O teste 4 da bateria
publica exatamente a sequência acima e exige 700,00 na janela das 14h.

---

#### 3. Estado no listener, e pacotes fora do padrão

**Encontrado.** O acumulado e a decisão de "é janela nova" moravam no listener; os pacotes eram
`listener/` e `config/`. A rubrica desta etapa nomeia como Insuficiente exatamente **"regra de
negócio no listener"**, e o padrão são quatro pacotes: raiz, `controller`, `domain`, `service`.

**Aceito.** Estado movido para o `AgregadorService`, `listener/` renomeado para `controller/`,
`KafkaConfig` para a raiz, e a classe aninhada `JanelaDeHora` virou `JanelaDeHoraVO` em `domain`,
com sufixo da lista fechada.

---

#### 4. A regra de compatibilidade dizia o contrário do que o rótulo escolhia

**Encontrado.** O `docs/contrato.md` escolhia **BACKWARD** e justificava com "o consumidor antigo
consegue desserializar dados novos... e o produtor pode ser atualizado primeiro" — que é a
definição de **FORWARD** na tabela do próprio enunciado. Rótulo e justificativa se contradiziam.

**Aceito.** FORWARD, que é o que o código sustenta: os dois consumidores declaram menos campos do
que o produtor publica e ignoram desconhecidos, então o produtor sobe primeiro e cada consumidor
acompanha no próprio ritmo.

---

#### 5. RECUSADO — reescrever o agregador em Kafka Streams

**Sugerido.** Trocar a agregação manual por Kafka Streams com `windowedBy`, já que a dependência
`kafka-streams` está no `pom.xml` e a API resolveria janela, estado e retardatário de uma vez.

**RECUSADO.** Razão técnica: o enunciado pede Kafka Streams como **desafio opcional, ao lado da
versão à mão, para comparar as duas** — não como substituição. Trocar apagaria justamente o que
está sendo avaliado, que é a equipe decidir o alinhamento da janela e o tratamento do retardatário.
Streams tomaria as duas decisões por nós, e a rubrica não teria o que ler. A dependência não usada
deveria sair do `pom.xml`, mas isso é limpeza, não arquitetura.

---

#### 6. RECUSADO — implementar watermark para fechar a janela

**Sugerido.** Adicionar watermark, descartando eventos que cheguem depois de um limite.

**RECUSADO.** Duas razões. É desafio opcional nesta etapa, e o `docs/entregas/aula-03.md` responde
à pergunta 3 dizendo que **nenhum retardatário é descartado** — implementar o corte tornaria o
documento falso no mesmo dia em que foi escrito. Além disso, para "quanto foi liquidado por hora",
descartar receita real que chegou tarde é pior do que um total que se corrige: a pergunta é de
fechamento contábil, não de painel em tempo real. Fica registrado como dívida, com a decisão
interessante — o que fazer com quem chega depois do corte — em aberto.

---

#### 7. A estrutura do IA.md tinha trocado a autoria de três interações

**Encontrado.** O cabeçalho `## Aula 03` havia sido inserido no meio da seção da Aula 02, e o
arquivo ficou com **duas** seções `## Aula 02`. O efeito colateral é que as interações 7, 8 e 9 —
escritas por mim na etapa anterior — apareciam sob o nome de outro integrante.

**Aceito.** Reorganização em duas seções, uma por aula, com cada bloco de interações sob o autor
que o escreveu. Nada de conteúdo foi alterado, só a ordem dos blocos.

Vale como aviso ao grupo: num arquivo que várias pessoas editam e onde a **autoria é justamente o
que se avalia**, inserir seção nova no meio é fácil de fazer sem perceber. Acrescentem sempre no
fim, sob o próprio cabeçalho.

---

## Aula 04

### Evandro V. Junior (254593) — ADR-003, auditoria dos documentos e escopo da entrega

Ferramenta: Claude Code (Claude Opus 5). Interações de 05/09/2026.

---

#### 1. "O número de partições é irreversível" — a afirmação da ferramenta estava errada

**Pedido.** Revisar a afirmação, escrita pela ferramenta no rascunho do ADR-003, de que aumentar o
número de partições quebraria a ordem e de que o número seria *"na prática, uma decisão irreversível
deste tópico"*. A objeção da equipe: mesmo que a chave passe a cair em outra partição, cada partição
continua ordenada.

**Sugerido originalmente — e incorreto.** O rascunho afirmava que aumentar partições destrói a ordem
das chaves existentes, e concluía pela irreversibilidade. A consequência nº 2 do ADR dizia, na mesma
linha, que o paralelismo tinha "teto de três, e o teto é permanente".

**RECUSADO, e a recusa estava certa.** A objeção procede: a ordem **dentro** da partição nunca se
perde. O que se perde ao aumentar **no lugar** (`--alter --partitions`) é outra coisa — a garantia de
**um único leitor por empresa a cada instante**: com backlog nas duas, os eventos novos de uma
empresa caem numa partição e os antigos ficam em outra, dois consumidores leem "4 de 10 isenções
usadas" e os dois emitem `FRANQUIA`. É a premissa do `read-then-write`, não a ordem, que estava em
jogo. E ela é preservável: o **cutover com dreno** — tópico novo, pausar a publicação, drenar até lag
zero, virar — migra sem janela de leitura concorrente, ao custo de indisponibilidade de publicação.

**Aceito.** O ADR-003 foi corrigido em dois pontos: a pergunta 4 passou a distinguir "aumentar no
lugar" de "migrar", com o procedimento e os custos; e a consequência nº 2 deixou de ser restrição
permanente e virou **dívida dimensionada** — três partições vieram da criação do tópico, não de
dimensionamento, e a recomendação passou a ser subir esse número **agora**, enquanto o dreno leva
segundos. O javadoc de `TarifacaoService` também foi corrigido: ele proibia aumentar partições e
recomendava "rever a granularidade da chave", que é justamente o cenário do `idTransacaoPix` que o
próprio javadoc condena três linhas acima.

---

## Aula 05

### Allainn Christiam (254337) — revisão do event store, da projeção e da migração

Ferramenta: Claude Code (Claude Opus 5). Interações de 06/09/2026.

---

#### 1. A ADR-005 que eu gerei e depois joguei fora

**Pedido.** Escrever a `ADR-005` escolhendo o agregado a ser persistido por Event Sourcing, a partir
dos materiais da aula e do estado do repositório.

**Sugerido.** A ferramenta produziu uma ADR completa escolhendo o `CicloDeTarifacao`
(`idEmpresa` + competência) como agregado, com stream `ciclo-{empresa}-{competência}`, event store
relacional com unicidade em `(stream, versao)` e uma projeção de extrato. Argumentação: auditoria é
requisito regulado, o estorno é fato de negócio, e o determinismo da fatura depende de cinco
condições do transporte.

**RECUSADA — a minha, em favor da do Evandro.** Ao trazer a branch dele, apareceu que ele havia
chegado ao **mesmo agregado** de forma independente, e com fundamentação melhor: a ADR dele mostra
que o código **já** calculava estado como *fold* do log sem chamar assim — `contarFranquiaConsumida()`
é um `COUNT` e `totalTarifadoNaCompetencia()` é um `SUM`, ambos sobre a `tarifa`, e o javadoc já
registrava a razão (*"contar linhas é naturalmente idempotente, somar +1 não"*). A minha ADR
argumentava de fora para dentro, a dele de dentro para fora. Duas ADR-005 no mesmo repositório
seriam duas fontes da verdade sobre a mesma decisão, que é exatamente o que uma ADR existe para
evitar.

**Aceito.** A ADR-005 do repositório é a do Evandro. A minha ficou numa branch descartada, e o que
sobrou dela virou insumo de review. A convergência entre duas análises independentes é o argumento
mais forte a favor da decisão — e não teria aparecido se eu tivesse aceitado a primeira resposta
como definitiva.

---

#### 2. "Derrube o volume com `docker compose down -v`" — RECUSADO

**Pedido.** Revisar por que a aplicação não subia num banco que já existia desde a aula 04.

**Sugerido.** O comentário do `schema.sql`, escrito pela ferramenta junto com a coluna `versao`,
declarava o procedimento: *"BANCO PREEXISTENTE: a coluna entra no CREATE TABLE, entao um banco criado
antes desta mudanca nao a recebe. Derrube com `docker compose down -v` e suba de novo. Nao ha
migracao com backfill aqui de proposito — inventar versao para fatos historicos e escrever ordem que
ninguem observou."*

**RECUSADO.** O argumento contra **fabricar** versão para fato histórico é correto; ele não é
argumento para **apagar o fato**. E a consequência 7 da própria ADR-005 afirma que o log não pode ser
expurgado, com prazo de guarda fiscal — as duas coisas não convivem. Havia ainda o efeito prático que
o comentário subestimava: não era "não recebe a coluna", era o `CREATE UNIQUE INDEX` logo abaixo
referenciando coluna inexistente, `continue-on-error` no default `false`, e o
`DataSourceScriptDatabaseInitializer` **derrubando a aplicação na subida**. Quem tivesse o volume da
aula 04 no disco simplesmente não rodava mais o projeto.

**Aceito, com a alternativa.** `ALTER TABLE tarifa ADD COLUMN IF NOT EXISTS versao BIGINT;` — **nullable**,
antes do índice. Não inventa versão nenhuma, preserva o log, e o `versao > ?` do projetor exclui NULL
por semântica de SQL, sem caso especial. O teste 10 (`o schema.sql pode rodar de novo sobre o banco
já criado`) passou a cobrir isso. A ADR-005 foi corrigida no mesmo passo: o SQL que ela exibia
(`ADD COLUMN versao BIGINT NOT NULL`) não existia em arquivo algum e falharia em Postgres contra
tabela não vazia.

---

#### 3. "Limite a descoberta a uma janela de competências" — RECUSADO por ora

**Pedido.** A query que descobre streams pendentes agrega a `tarifa` inteira a cada 2 segundos, para
sempre, numa tabela que a ADR declara não expurgável. Como reduzir.

**Sugerido.** Restringir a descoberta por uma janela — algo como `WHERE t.competencia >= ?` — para
que o custo do tick ficasse proporcional à atividade e não ao histórico.

**RECUSADO.** A janela troca CPU por uma garantia de domínio que está escrita na entrega da aula 03:
*"Qualquer retardatário é aceito, por mais tarde que chegue"*, e nenhum evento é descartado. Um Pix
liquidado em agosto que chegue em outubro precisa ser projetado na competência de agosto; com janela,
ele nunca seria descoberto — e a falha seria **silenciosa**, que é a pior classe de defeito deste
projeto inteiro. Otimizar leitura ao custo de perder fato é exatamente o que o `CHECK (valor >= 0)`
da `tarifa` existe para impedir em outro lugar.

**Aceito em parte.** A query foi reescrita para que o agregado por stream possa ser servido pelo
índice `uq_tarifa_stream_versao`, e a comparação passou de `>` para `<>` — o que corrige um bug
separado, em que uma marca d'água **adiantada** tirava o stream da descoberta para sempre. O custo
proporcional ao histórico continua, registrado como dívida: a saída correta é uma marca de stream
sujo escrita no `registrarTarifa`, e ela entra na aula 06 junto com a compensação, que é quando o
projetor vai ser mexido de qualquer forma.

---

### Amanda Bouzan (255369) — retenção da memória de deduplicação

Ferramenta: Codex (GPT-5). Interações de 10/09/2026.

---

#### 1. Validar sem subir Kafka, Postgres e Docker

**Pedido.** Encontrar uma contribuição incremental que pudesse ser verificada localmente por testes
unitários, pois o ambiente completo não estava disponível.

**Sugerido.** Tratar compilação e testes unitários como garantia de que nada seria quebrado na
integração.

**RECUSADO.** Testes unitários não validam a configuração efetiva de um tópico já existente, a
execução do agendamento pelo Spring nem a compatibilidade do SQL com um Postgres real. Eles reduzem
o risco e verificam a regra isolada, mas a validação ponta a ponta continua necessária no ambiente
da equipe.

**Aceito.** Isolar o cálculo temporal com relógio fixo, testar o SQL com `JdbcTemplate` simulado e
documentar os comandos que o revisor deverá executar no ambiente completo.

---

#### 2. Usar sete dias para o tópico e também para a deduplicação

**Pedido.** Transformar o comentário de retenção do `schema.sql` em uma política explícita.

**Sugerido.** Manter sete dias nos dois lados porque esse é o prazo já mencionado no arquivo.

**RECUSADO.** Prazos iguais não dão margem para a expiração assíncrona dos segmentos do Kafka nem
para a diferença entre produção e processamento. Durante essa fronteira, um evento ainda legível
no tópico poderia encontrar seu `ce_id` já removido e produzir efeito novamente.

**Aceito.** Declarar sete dias no tópico e 30 dias para `evento_processado`, mantendo como invariante
que a memória de deduplicação seja maior que a retenção da origem. Alterar um prazo exige revisar o
outro na mesma mudança.

---

#### 3. Apagar a própria `tarifa` após o prazo

**Pedido.** Revisar quais dados deveriam ser removidos pelo expurgo.

**Sugerido.** Excluir também registros antigos de `tarifa`, já que eles carregam o mesmo
`evento_id`, reduzindo mais o volume do banco.

**RECUSADO.** As tabelas têm semânticas diferentes. `evento_processado` é um índice operacional de
deduplicação; `tarifa` é o log de negócio e o event store definido no ADR-005, necessário para
auditoria e reconstrução da projeção. Aplicar a mesma retenção destruiria a fonte da verdade.

**Aceito.** O SQL do expurgo referencia somente `evento_processado`, sempre com predicado temporal.
O ADR-006 registra explicitamente que `tarifa` e `fatura_competencia` ficam fora dessa rotina.

---

<!--
  Demais integrantes: acrescentem a sua subseção da Aula 05 acima desta linha, no
  mesmo formato (### Nome (matrícula) — parte pela qual respondeu).
  A rubrica pede TRÊS interações com ao menos UMA recusa justificada POR
  INTEGRANTE — a Aula 04 cobre apenas o Evandro e a Aula 05, apenas o Allainn.
  Faltam: Alexsander, Guilherme, Jhonathan e Samuel.
-->
