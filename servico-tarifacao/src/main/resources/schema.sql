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

-- RETENCAO: esta tabela cresce para sempre e precisa de expurgo. A janela tem
-- de ser MAIOR que a retencao do topico — se for menor, um replay de mensagem
-- antiga encontra a tabela ja limpa e passa pela deduplicacao como se fosse
-- evento novo, cobrando a empresa duas vezes.
--   DELETE FROM evento_processado WHERE processado_em < now() - INTERVAL '7 days';

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
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tarifa (
  evento_id   VARCHAR(64)   PRIMARY KEY,
  id_empresa  VARCHAR(32)   NOT NULL,
  id_transacao_pix      VARCHAR(64)   NOT NULL,
  competencia VARCHAR(7)    NOT NULL,   -- YYYY-MM, derivada do liquidadoEm do evento
  situacao    VARCHAR(20)   NOT NULL,   -- SEM_CONTRATO|FRANQUIA|FAIXA|TETO_PARCIAL|TETO_ATINGIDO
  valor       NUMERIC(10,2) NOT NULL,
  liquidado_em TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tarifa_empresa_competencia ON tarifa (id_empresa, competencia);

-- ---------------------------------------------------------------------------
-- Carga de exemplo. Dados FICTICIOS: o repositorio e publico.
--
-- Cliente SEM linha vigente nesta tabela NAO E COBRADO — nao ha plano padrao.
-- Cobrar sem contrato e cobranca indevida, com exposicao a devolucao em dobro
-- (CDC, art. 42, paragrafo unico). Ver ADR-002, secao Decisao.
-- ---------------------------------------------------------------------------

-- cli-0001  PLANO PJ, exatamente como a especificacao da regra o define:
--           10 isencoes por competencia, teto de R$ 2.000,00 e a tabela de
--           quatro faixas. Repare nos limites: EXCLUSIVOS. Um Pix de
--           R$ 500,00 nao cai na primeira faixa, e sim na segunda.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0001', '2026-01', NULL, 10, 2000.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0001', '2026-01', 1,  500.00,  0.50) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0001', '2026-01', 2, 1000.00,  1.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0001', '2026-01', 3, 5000.00,  5.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0001', '2026-01', 4, NULL,    10.00) ON CONFLICT DO NOTHING;

-- cli-0002  franquia curta (2), para exercitar o fim da franquia sem publicar
--           onze eventos. Mesma tabela de faixas do Plano PJ, sem teto.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0002', '2026-01', NULL, 2, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0002', '2026-01', 1,  500.00,  0.50) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0002', '2026-01', 2, 1000.00,  1.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0002', '2026-01', 3, 5000.00,  5.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0002', '2026-01', 4, NULL,    10.00) ON CONFLICT DO NOTHING;

-- cli-0003  sem isencao: tarifa desde o primeiro Pix
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0003', '2026-01', NULL, 0, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0003', '2026-01', 1, NULL, 0.99) ON CONFLICT DO NOTHING;

-- cli-0004  TROCA DE PLANO: 2 isencoes ate 2026-07, 10 isencoes a partir de 2026-08.
--           O mesmo evento reprocessado tem de reencontrar a oferta da SUA
--           competencia, e nao a vigente hoje.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0004', '2026-01', '2026-07', 2, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0004', '2026-01', 1, NULL, 4.90) ON CONFLICT DO NOTHING;
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0004', '2026-08', NULL, 10, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0004', '2026-08', 1, NULL, 2.50) ON CONFLICT DO NOTHING;

-- cli-0005  CONTRATO ENCERRADO em 2026-07 e sem sucessora: em 2026-08 nao ha
--           oferta vigente, e o Pix sai SEM_CONTRATO, valor 0.00.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0005', '2026-01', '2026-07', 5, NULL) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0005', '2026-01', 1, NULL, 1.90) ON CONFLICT DO NOTHING;

-- cli-0006  TETO BAIXO (R$ 25,00), sem isencao, a R$ 10,00 o Pix. Existe para
--           exercitar as DUAS saidas do teto sem publicar 200 eventos:
--             Pix 1  ->  0 + 10 cabe   -> FAIXA         10,00   (acumulado 10)
--             Pix 2  -> 10 + 10 cabe   -> FAIXA         10,00   (acumulado 20)
--             Pix 3  -> 20 + 10 estoura-> TETO_PARCIAL   5,00   (acumulado 25)
--             Pix 4  -> 25 >= 25       -> TETO_ATINGIDO  0,00   (acumulado 25)
--           O acumulado para exatamente no teto: e a invariante
--           valor_tarifado_na_competencia <= teto, que so o parcial garante.
INSERT INTO oferta (id_empresa, vigencia_inicio, vigencia_fim, pix_gratuitos_mes, teto_mensal)
     VALUES ('cli-0006', '2026-01', NULL, 0, 25.00) ON CONFLICT DO NOTHING;
INSERT INTO oferta_faixa (id_empresa, vigencia_inicio, ordem, valor_abaixo_de, valor_tarifa)
     VALUES ('cli-0006', '2026-01', 1, NULL, 10.00) ON CONFLICT DO NOTHING;
