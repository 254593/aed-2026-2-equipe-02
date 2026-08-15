-- ---------------------------------------------------------------------------
-- Memoria do que ja foi processado. E o que sustenta a idempotencia.
--
-- A chave e o eventoId, que chega no cabecalho ce_id do envelope CloudEvents.
-- Nao e o pixId: dois eventos DIFERENTES podem falar do mesmo Pix (um
-- PixRealizado hoje e um PixDevolvido amanha), e deduplicar pela entidade
-- descartaria o segundo como se fosse repeticao do primeiro.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS evento_processado (
  evento_id     VARCHAR(64) PRIMARY KEY,
  processado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- RETENCAO: esta tabela cresce para sempre e precisa de expurgo. A janela tem
-- de ser MAIOR que a retencao do topico — se for menor, um replay de mensagem
-- antiga encontra a tabela ja limpa e passa pela deduplicacao como se fosse
-- evento novo, cobrando o cliente duas vezes.
--   DELETE FROM evento_processado WHERE processado_em < now() - INTERVAL '7 days';

-- ---------------------------------------------------------------------------
-- O contrato comercial do cliente: a REGRA que decide isencao e tarifa.
--
-- A OFERTA TEM VIGENCIA, e isso nao e detalhe de cadastro.
--
-- O ADR-002 decide que "empresa sem contrato de tarifacao vigente NA DATA DE
-- COMPETENCIA nao e cobrada". A competencia sai do ocorridoEm do evento, nunca
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
  cliente_id        VARCHAR(32)   NOT NULL,
  vigencia_inicio   VARCHAR(7)    NOT NULL,   -- YYYY-MM, inclusivo
  vigencia_fim      VARCHAR(7),               -- YYYY-MM, inclusivo; NULL = em aberto
  pix_gratuitos_mes INT           NOT NULL,
  teto_mensal       NUMERIC(10,2),            -- NULL = sem teto de gasto no mes
  PRIMARY KEY (cliente_id, vigencia_inicio)
);

-- VARCHAR(7) no formato YYYY-MM permite comparar competencias com <= e >=
-- lexicograficamente: '2026-08' <= '2026-09' e verdade como texto. So funciona
-- porque o mes tem zero a esquerda e o ano vem primeiro; e a mesma razao pela
-- qual ISO-8601 e ordenavel como string, e o motivo de nao guardar '8/2026'.

-- ---------------------------------------------------------------------------
-- As faixas de tarifa de uma oferta: o Pix acima da franquia custa conforme o
-- VALOR transferido, nao um preco unico.
--
-- valor_ate e o limite superior INCLUSIVO da faixa. A ultima faixa tem
-- valor_ate nulo, que significa "daqui para cima". A coluna `ordem` existe para
-- dar chave primaria e leitura deterministica; a regra de escolha da faixa mora
-- no OfertaVO, em Java, e nao numa clausula SQL — decisao de dominio fica onde
-- se possa ler e testar.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oferta_faixa (
  cliente_id      VARCHAR(32)   NOT NULL,
  vigencia_inicio VARCHAR(7)    NOT NULL,
  ordem           INT           NOT NULL,
  valor_ate       NUMERIC(15,2),              -- NULL = ultima faixa, sem limite
  valor_tarifa    NUMERIC(10,2) NOT NULL,
  PRIMARY KEY (cliente_id, vigencia_inicio, ordem)
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
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tarifa (
  evento_id   VARCHAR(64)   PRIMARY KEY,
  cliente_id  VARCHAR(32)   NOT NULL,
  pix_id      VARCHAR(64)   NOT NULL,
  competencia VARCHAR(7)    NOT NULL,   -- YYYY-MM, derivada do ocorridoEm do evento
  situacao    VARCHAR(20)   NOT NULL,   -- SEM_CONTRATO | ISENTO_FRANQUIA | TARIFADO | TETO_ATINGIDO
  valor       NUMERIC(10,2) NOT NULL,
  ocorrido_em TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tarifa_cliente_competencia ON tarifa (cliente_id, competencia);

-- ---------------------------------------------------------------------------
-- Carga de exemplo. Dados FICTICIOS: o repositorio e publico.
--
-- Cliente SEM linha vigente nesta tabela NAO E COBRADO — nao ha plano padrao.
-- Cobrar sem contrato e cobranca indevida, com exposicao a devolucao em dobro
-- (CDC, art. 42, paragrafo unico). Ver ADR-002, secao Decisao.
-- ---------------------------------------------------------------------------

-- cli-0001  plano com 5 isencoes e tres faixas de valor, sem teto
INSERT INTO oferta (cliente_id, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0001', '2026-01', NULL, 5, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0001', '2026-01', 1, 500.00, 1.90) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0001', '2026-01', 2, 5000.00, 3.50) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0001', '2026-01', 3, NULL, 7.00) ON CONFLICT DO NOTHING;

-- cli-0002  plano enxuto: 2 isencoes, faixa unica
INSERT INTO oferta (cliente_id, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0002', '2026-01', NULL, 2, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0002', '2026-01', 1, NULL, 3.50) ON CONFLICT DO NOTHING;

-- cli-0003  sem isencao: tarifa desde o primeiro Pix
INSERT INTO oferta (cliente_id, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0003', '2026-01', NULL, 0, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0003', '2026-01', 1, NULL, 0.99) ON CONFLICT DO NOTHING;

-- cli-0004  TROCA DE PLANO: 2 isencoes ate 2026-07, 10 isencoes a partir de 2026-08.
--           O mesmo evento reprocessado tem de reencontrar a oferta da SUA
--           competencia, e nao a vigente hoje.
INSERT INTO oferta (cliente_id, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0004', '2026-01', '2026-07', 2, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0004', '2026-01', 1, NULL, 4.90) ON CONFLICT DO NOTHING;
INSERT INTO oferta (cliente_id, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0004', '2026-08', NULL, 10, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0004', '2026-08', 1, NULL, 2.50) ON CONFLICT DO NOTHING;

-- cli-0005  CONTRATO ENCERRADO em 2026-07 e sem sucessora: em 2026-08 nao ha
--           oferta vigente, e o Pix sai SEM_CONTRATO, valor 0.00.
INSERT INTO oferta (cliente_id, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0005', '2026-01', '2026-07', 5, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0005', '2026-01', 1, NULL, 1.90) ON CONFLICT DO NOTHING;

-- cli-0006  TETO MENSAL de R$ 1,98: sem isencao, a R$ 0,99 o Pix. Os dois
--           primeiros sao cobrados e atingem o teto; do terceiro em diante o
--           Pix sai 0.00 como TETO_ATINGIDO.
INSERT INTO oferta (cliente_id, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0006', '2026-01', NULL, 0, 1.98) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (cliente_id, vigencia_inicio, ordem, valor_ate, valor_tarifa)
     VALUES ('cli-0006', '2026-01', 1, NULL, 0.99) ON CONFLICT DO NOTHING;
