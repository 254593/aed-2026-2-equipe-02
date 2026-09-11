-- ---------------------------------------------------------------------------
-- Memoria do que ja foi processado. E o que sustenta a idempotencia.
--
-- A chave e o eventoId, que chega no cabecalho ce_id do envelope CloudEvents.
-- Nao e o idTransacaoPix: dois eventos DIFERENTES podem falar do mesmo Pix (um
-- PixRealizado hoje e um PixDevolvido amanha), e deduplicar pela entidade
-- descartaria o segundo como se fosse repeticao do primeiro.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS evento_processado (
  evento_id     VARCHAR(64) PRIMARY KEY,
  processado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- RETENCAO: o EventoProcessadoExpurgador remove diariamente os ce_id com mais
-- de 30 dias (prazo configuravel em tarifacao.deduplicacao.*). A janela tem
-- de ser MAIOR que a retencao do topico — se for menor, um replay de mensagem
-- antiga encontra a tabela ja limpa e passa pela deduplicacao como se fosse
-- evento novo, cobrando a empresa duas vezes.

-- ---------------------------------------------------------------------------
-- O contrato comercial da empresa: a REGRA que decide isencao e tarifa.
--
-- A OFERTA TEM VIGENCIA, e isso nao e detalhe de cadastro.
--
-- O ADR-002 decide que "empresa sem contrato de tarifacao vigente NA DATA DE
-- COMPETENCIA nao e cobrada". A competencia sai do liquidadoEm do evento, nunca
-- do relogio da maquina — logo a busca da oferta tambem precisa ser feita
-- CONTRA A COMPETENCIA DO EVENTO. Um replay do topico feito em outubro tem que
-- reencontrar a oferta que vigia em agosto; se buscasse a oferta "atual", o
-- reprocessamento produziria um valor diferente do original e o fechamento
-- mensal deixaria de ser reproduzivel — que e justamente o quarto criterio do
-- ADR ("algo que valha a pena reprocessar").
--
-- Vigencia em competencia (YYYY-MM), com as duas pontas INCLUSIVAS.
-- vigencia_fim nula significa contrato em aberto.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oferta (
  id_empresa        VARCHAR(32)   NOT NULL,
  vigencia_inicio   VARCHAR(7)    NOT NULL,   -- YYYY-MM, inclusivo
  vigencia_fim      VARCHAR(7),               -- YYYY-MM, inclusivo; NULL = em aberto
  pix_gratuitos_mes INT           NOT NULL,
  teto_mensal       NUMERIC(10,2),            -- NULL = sem teto de gasto no mes
  PRIMARY KEY (id_empresa, vigencia_inicio)
);

-- VARCHAR(7) no formato YYYY-MM permite comparar competencias com <= e >=
-- lexicograficamente: '2026-08' <= '2026-09' e verdade como texto. So funciona
-- porque o mes tem zero a esquerda e o ano vem primeiro; e a mesma razao pela
-- qual ISO-8601 e ordenavel como string, e o motivo de nao guardar '8/2026'.

-- ---------------------------------------------------------------------------
-- As faixas de tarifa de uma oferta: o Pix acima da franquia custa conforme o
-- VALOR transferido, nao um preco unico.
--
-- valor_abaixo_de e o limite superior EXCLUSIVO da faixa — a especificacao
-- define "limite inferior inclusivo, superior exclusivo". A coluna nao se chama
-- valor_ate porque "ate" se le como inclusivo, e foi assim que a primeira
-- versao desta tabela errou: um Pix de exatamente R$ 500,00 caia na faixa de
-- baixo e pagava a tarifa errada.
--
-- O limite inferior nao e armazenado: e o superior da faixa anterior. A ultima
-- faixa tem valor_abaixo_de nulo, que significa "daqui para cima". A coluna
-- `ordem` da a chave primaria e a leitura deterministica; a regra de escolha da
-- faixa mora no OfertaVO, em Java, e nao numa clausula SQL — decisao de dominio
-- fica onde se possa ler e testar.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oferta_faixa (
  id_empresa      VARCHAR(32)   NOT NULL,
  vigencia_inicio VARCHAR(7)    NOT NULL,
  ordem           INT           NOT NULL,
  valor_abaixo_de NUMERIC(15,2),              -- EXCLUSIVO; NULL = ultima faixa
  valor_tarifa    NUMERIC(10,2) NOT NULL,
  PRIMARY KEY (id_empresa, vigencia_inicio, ordem)
);

-- ---------------------------------------------------------------------------
-- O efeito de negocio. Uma linha por Pix processado, SEMPRE — inclusive quando
-- nao houve cobranca. A isencao tambem e um fato do dominio.
--
-- A coluna `situacao` guarda QUAL das quatro saidas da politica produziu esta
-- linha, e nao apenas quanto custou. Sem ela, 0.00 seria ambiguo: um Pix isento
-- por franquia, um Pix de empresa sem contrato e um Pix acima do teto mensal
-- valem todos zero e significam coisas diferentes. A auditoria e a contestacao
-- comercial precisam distinguir os tres, e a contagem de franquia depende
-- disso: SEM_CONTRATO e TETO_ATINGIDO nao consomem franquia.
--
-- evento_id como chave primaria e rede de seguranca, nao o mecanismo: quem
-- impede o efeito duplicado e a tabela evento_processado, dentro da mesma
-- transacao. Se um dia a deduplicacao falhar, o banco recusa a insercao em vez
-- de cobrar de novo.
--
-- O CHECK (valor >= 0) NAO E ZELO GENERICO: e a invariante do teto, tornada
-- executavel. O teto mensal e derivado por SUM(valor) sobre esta tabela, e o
-- acumulado precisa ser MONOTONICO — so cresce. Uma unica linha negativa
-- gravada aqui, por exemplo tentando registrar um estorno como o inverso da
-- cobranca, faria o SUM decrementar e o teto REABRIR EM SILENCIO: Pix que ja
-- tinham saido como TETO_ATINGIDO voltariam a ser cobrados, e decisoes ja
-- tomadas ficariam inconsistentes entre si.
--
-- Estorno e ajuste de FATURA, com acumulador proprio
-- (valorEstornadoNaCompetencia), e nunca reversao de uma decisao ja tomada. Ver
-- docs/regra-de-tarifacao.md, secao Compensacao, e o ADR-002: "nada e apagado
-- nem decrementado". Sem este CHECK, a regra depende de alguem ler o javadoc
-- antes de escrever a saga da aula 05; com ele, o banco recusa.
-- ---------------------------------------------------------------------------
-- A COLUNA `versao` FAZ DESTA TABELA UM EVENT STORE, e nao apenas um registro.
--
-- O stream de um agregado e (id_empresa, competencia) — o CicloDeTarifacao do
-- ADR-005. NAO existe coluna stream_id: as duas colunas ja sao a identidade, e
-- uma terceira concatenando-as seria redundancia sem constraint que a
-- disciplinasse, livre para divergir em silencio.
--
-- A versao e a ORDEM LOGICA do fato dentro do stream, e e por ela que o replay
-- percorre o historico — nunca por liquidado_em, que e dado e nao indice.
--
-- O INDICE UNICO E O MECANISMO DE DETECCAO DE ESCRITA CONCORRENTE. Hoje existe
-- um escritor por empresa, garantido pela chave de particao do ADR-003, entao
-- ele nunca dispara. Ele existe para o dia em que a compensacao puser um
-- orquestrador emitindo estornos enquanto Pix novos chegam pela particao: sem
-- ele, os dois calculariam a mesma versao e o acumulado ficaria errado sem que
-- nada acusasse; com ele, o segundo leva violacao e a transacao inteira volta.
--
-- E indice unico, e nao ADD CONSTRAINT, porque `CREATE UNIQUE INDEX IF NOT
-- EXISTS` e idempotente e este arquivo roda a cada subida da aplicacao.
--
-- A VERSAO E POSITIVA, E ISSO E CONSTRAINT E NAO CONVENCAO. O projetor usa 0
-- como sentinela de "nada projetado ainda" (COALESCE(MAX(versao_projetada), 0)),
-- entao um fato gravado com versao 0 seria ao mesmo tempo um fato real e a
-- ausencia de fatos: a descoberta nunca o marcaria como pendente e a fatura o
-- sub-reportaria para sempre, em silencio. Vale aqui a mesma doutrina do CHECK
-- do valor, trinta linhas acima — invariante que depende de alguem ler o javadoc
-- nao e invariante.
--
-- liquidado_em E `WITH TIME ZONE`. A competencia sai do liquidadoEm em UTC
-- (TarifacaoService.competenciaDe), e uma coluna sem fuso guardava a hora de
-- parede da JVM: um Pix de 2026-08-01T01:00:00Z num host em America/Sao_Paulo
-- ia para o stream de 2026-08 com liquidado_em em 2026-07-31. A chave do stream
-- e o timestamp da propria linha discordavam do mes, e o conteudo da tabela
-- deixava de ser reproduzivel entre hosts.
--
-- BANCO PREEXISTENTE, e a assimetria e so uma: a coluna `versao` chega pelo
-- ALTER TABLE logo abaixo do CREATE, mas o TIPO de liquidado_em nao muda —
-- `CREATE TABLE IF NOT EXISTS` e no-op e nao ha ALTER de tipo aqui, de proposito,
-- porque converter a coluna reinterpretaria fatos ja gravados no fuso em que
-- foram gravados. Num banco criado antes desta mudanca, liquidado_em continua
-- sem fuso: para ter o tipo novo, `docker compose down -v` e suba de novo.
CREATE TABLE IF NOT EXISTS tarifa (
  evento_id        VARCHAR(64)   PRIMARY KEY,
  id_empresa       VARCHAR(32)   NOT NULL,
  id_transacao_pix VARCHAR(64)   NOT NULL,
  competencia      VARCHAR(7)    NOT NULL,  -- YYYY-MM, do liquidadoEm do evento
  situacao         VARCHAR(20)   NOT NULL,  -- SEM_CONTRATO|FRANQUIA|FAIXA|TETO_PARCIAL|TETO_ATINGIDO
  valor            NUMERIC(10,2) NOT NULL
                     CONSTRAINT tarifa_valor_nao_negativo CHECK (valor >= 0),
  liquidado_em     TIMESTAMP WITH TIME ZONE NOT NULL,
  versao           BIGINT        NOT NULL   -- ordem do fato no stream (id_empresa, competencia)
                     CONSTRAINT tarifa_versao_positiva CHECK (versao > 0)
);

-- ---------------------------------------------------------------------------
-- MIGRACAO PARA BANCO PREEXISTENTE — e ela precisa vir ANTES do indice unico.
--
-- `CREATE TABLE IF NOT EXISTS` e no-op num banco que ja tem a tabela, entao a
-- coluna `versao` nao chegava; o `CREATE UNIQUE INDEX` abaixo entao referenciava
-- coluna inexistente, levantava 42703 e, com `continue-on-error` no default
-- false, o DataSourceScriptDatabaseInitializer DERRUBAVA a aplicacao na subida.
-- Quem tinha o volume da aula 04 no disco nao subia mais.
--
-- A coluna entra NULLABLE de proposito. Inventar versao para fato historico e
-- escrever ordem que ninguem observou; deixando nula, o fato continua no log e o
-- `versao > ?` do projetor o exclui por semantica de SQL, sem caso especial. O
-- fold conta esses fatos por COUNT(*), que e o que a descoberta compara.
--
-- Num banco novo a coluna ja existe com NOT NULL e o CHECK acima; num migrado
-- ela e nullable e sem CHECK. A assimetria e o preco de nao ter Flyway, e esta
-- aqui escrita em vez de descoberta em producao.
-- ---------------------------------------------------------------------------
ALTER TABLE tarifa ADD COLUMN IF NOT EXISTS versao BIGINT;

CREATE INDEX IF NOT EXISTS idx_tarifa_empresa_competencia ON tarifa (id_empresa, competencia);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tarifa_stream_versao
    ON tarifa (id_empresa, competencia, versao);

-- ---------------------------------------------------------------------------
-- A PROJECAO. Derivada, descartavel, e nada mais.
--
-- Uma linha por (id_empresa, competencia), desnormalizada para o fechamento da
-- competencia. NENHUM codigo escreve aqui alem do projetor, e nenhuma decisao le
-- daqui: a tarifacao continua lendo os fatos da `tarifa`. Se um dia a decisao
-- passar a ler a projecao, ela deixa de ser derivada e vira fonte da verdade sem
-- que ninguem tenha decidido isso.
--
-- `versao_projetada` e a ultima versao do stream ja incorporada. Ela existe por
-- dois motivos: o projetor avanca a partir dela, em vez de recalcular tudo; e a
-- DEFASAGEM passa a ser medida, e nao estimada — o atraso desta linha e
-- (versao atual do stream - versao_projetada), em fatos.
--
-- Apagar esta tabela inteira e SEGURO por construcao, e ha teste que exige isso:
-- o projetor reconstroi do zero e chega ao mesmo resultado. Se um dia apagar
-- quebrar alguma coisa, a projecao deixou de ser cache.
-- ---------------------------------------------------------------------------
-- OS QUATRO ACUMULADORES DO CICLO, e nao dois. A `regra-de-tarifacao.md`, secao
-- "Estado acumulado — CicloDeTarifacao", define quatro e afirma que "A fatura
-- final e o liquido: valorTarifadoNaCompetencia - valorEstornadoNaCompetencia",
-- marcando os dois de estorno como lidos por "apenas o fechamento" — isto e, por
-- esta tabela. Sem coluna para eles, `total_tarifado` seria o BRUTO e a fatura
-- superestimaria o liquido do contrato quando a saga da aula 06 entrasse, sem
-- espaco no schema para corrigir.
--
-- Os dois entram agora, com DEFAULT 0, e ficam em zero ate a compensacao existir
-- — o projetor nao os escreve. Nao e coluna morta: e a fronteira do agregado
-- declarada no schema antes de haver dado, que e a unica hora em que isso e
-- barato. Ver ADR-005, consequencia 10.
--
-- E os CHECKs. `tarifa.valor` carrega um com doze linhas de justificativa sobre
-- monotonicidade, e nada disso valia para as colunas derivadas dele. Um valor
-- negativo aqui significa projetor com defeito, e o banco passa a recusar.
CREATE TABLE IF NOT EXISTS fatura_competencia (
  id_empresa             VARCHAR(32)   NOT NULL,
  competencia            VARCHAR(7)    NOT NULL,
  total_tarifado         NUMERIC(12,2) NOT NULL CHECK (total_tarifado    >= 0),
  total_estornado        NUMERIC(12,2) NOT NULL DEFAULT 0
                                                CHECK (total_estornado   >= 0),
  qtd_pix                BIGINT        NOT NULL CHECK (qtd_pix           >= 0),
  qtd_sem_contrato       BIGINT        NOT NULL CHECK (qtd_sem_contrato  >= 0),
  qtd_franquia           BIGINT        NOT NULL CHECK (qtd_franquia      >= 0),
  qtd_faixa              BIGINT        NOT NULL CHECK (qtd_faixa         >= 0),
  qtd_teto_parcial       BIGINT        NOT NULL CHECK (qtd_teto_parcial  >= 0),
  qtd_teto_atingido      BIGINT        NOT NULL CHECK (qtd_teto_atingido >= 0),
  qtd_franquia_estornada BIGINT        NOT NULL DEFAULT 0
                                                CHECK (qtd_franquia_estornada >= 0),
  versao_projetada       BIGINT        NOT NULL CHECK (versao_projetada  >= 0),
  PRIMARY KEY (id_empresa, competencia)
);

-- Mesma razao do ALTER da `tarifa`: num banco preexistente o CREATE acima e
-- no-op e as duas colunas de estorno nao chegariam.
ALTER TABLE fatura_competencia
  ADD COLUMN IF NOT EXISTS total_estornado NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE fatura_competencia
  ADD COLUMN IF NOT EXISTS qtd_franquia_estornada BIGINT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- Carga de exemplo. Dados FICTICIOS: o repositorio e publico.
--
-- Cliente SEM linha vigente nesta tabela NAO E COBRADO — nao ha plano padrao.
-- Cobrar sem contrato e cobranca indevida, com exposicao a devolucao em dobro
-- (CDC, art. 42, paragrafo unico). Ver ADR-002, secao Decisao.
-- ---------------------------------------------------------------------------

-- emp-0001  PLANO PJ, exatamente como a especificacao da regra o define:
--           10 isencoes por competencia, teto de R$ 2.000,00 e a tabela de
--           quatro faixas. Repare nos limites: EXCLUSIVOS. Um Pix de
--           R$ 500,00 nao cai na primeira faixa, e sim na segunda.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('emp-0001', '2026-01', NULL, 10, 2000.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0001', '2026-01', 1,  500.00,  0.50) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0001', '2026-01', 2, 1000.00,  1.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0001', '2026-01', 3, 5000.00,  5.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0001', '2026-01', 4, NULL,    10.00) ON CONFLICT DO NOTHING;

-- emp-0002  franquia curta (2), para exercitar o fim da franquia sem publicar
--           onze eventos. Mesma tabela de faixas do Plano PJ, sem teto.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('emp-0002', '2026-01', NULL, 2, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0002', '2026-01', 1,  500.00,  0.50) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0002', '2026-01', 2, 1000.00,  1.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0002', '2026-01', 3, 5000.00,  5.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0002', '2026-01', 4, NULL,    10.00) ON CONFLICT DO NOTHING;

-- emp-0003  sem isencao: tarifa desde o primeiro Pix
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('emp-0003', '2026-01', NULL, 0, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0003', '2026-01', 1, NULL, 0.99) ON CONFLICT DO NOTHING;

-- emp-0004  TROCA DE PLANO: 2 isencoes ate 2026-07, 10 isencoes a partir de 2026-08.
--           O mesmo evento reprocessado tem de reencontrar a oferta da SUA
--           competencia, e nao a vigente hoje.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('emp-0004', '2026-01', '2026-07', 2, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0004', '2026-01', 1, NULL, 4.90) ON CONFLICT DO NOTHING;
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('emp-0004', '2026-08', NULL, 10, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0004', '2026-08', 1, NULL, 2.50) ON CONFLICT DO NOTHING;

-- emp-0005  CONTRATO ENCERRADO em 2026-07 e sem sucessora: em 2026-08 nao ha
--           oferta vigente, e o Pix sai SEM_CONTRATO, valor 0.00.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('emp-0005', '2026-01', '2026-07', 5, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0005', '2026-01', 1, NULL, 1.90) ON CONFLICT DO NOTHING;

-- emp-0006  TETO BAIXO (R$ 25,00), sem isencao, a R$ 10,00 o Pix. Existe para
--           exercitar as DUAS saidas do teto sem publicar 200 eventos:
--             Pix 1  ->  0 + 10 cabe   -> FAIXA         10,00   (acumulado 10)
--             Pix 2  -> 10 + 10 cabe   -> FAIXA         10,00   (acumulado 20)
--             Pix 3  -> 20 + 10 estoura-> TETO_PARCIAL   5,00   (acumulado 25)
--             Pix 4  -> 25 >= 25       -> TETO_ATINGIDO  0,00   (acumulado 25)
--           O acumulado para exatamente no teto: e a invariante
--           valor_tarifado_na_competencia <= teto, que so o parcial garante.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('emp-0006', '2026-01', NULL, 0, 25.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('emp-0006', '2026-01', 1, NULL, 10.00) ON CONFLICT DO NOTHING;
