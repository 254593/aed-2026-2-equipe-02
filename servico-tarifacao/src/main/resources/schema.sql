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
-- O plano do cliente: a REGRA que decide isencao.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oferta (
  cliente_id        VARCHAR(32)   PRIMARY KEY,
  pix_gratuitos_mes INT           NOT NULL,
  valor_tarifa      NUMERIC(10,2) NOT NULL
);

-- ---------------------------------------------------------------------------
-- O efeito de negocio. Uma linha por Pix processado, com valor 0.00 quando o
-- Pix coube na franquia — a isencao tambem e um fato, e e ela que consome a
-- cota do mes.
--
-- Guardar o isento junto do tarifado tem duas consequencias boas: a contagem
-- da franquia sai desta propria tabela, sem contador em coluna separada (que
-- seria um UPDATE em delta, o tipo de operacao que a reentrega quebra), e o
-- extrato mensal do cliente fica completo, pronto para ser reprocessado.
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
  valor       NUMERIC(10,2) NOT NULL,
  ocorrido_em TIMESTAMP     NOT NULL
);

-- VARCHAR(7) e nao CHAR(7): CHAR completa com espacos a direita, e a
-- comparacao de competencia comeca a falhar em silencio.

CREATE INDEX IF NOT EXISTS idx_tarifa_cliente_competencia ON tarifa (cliente_id, competencia);

-- ---------------------------------------------------------------------------
-- Carga de exemplo. Dados FICTICIOS: o repositorio e publico.
-- Cliente sem linha aqui recebe o plano padrao definido no TarifacaoRepository.
-- ---------------------------------------------------------------------------
INSERT INTO oferta (cliente_id, pix_gratuitos_mes, valor_tarifa)
     VALUES ('cli-0001', 5, 1.90) ON CONFLICT DO NOTHING;
INSERT INTO oferta (cliente_id, pix_gratuitos_mes, valor_tarifa)
     VALUES ('cli-0002', 2, 3.50) ON CONFLICT DO NOTHING;
INSERT INTO oferta (cliente_id, pix_gratuitos_mes, valor_tarifa)
     VALUES ('cli-0003', 0, 0.99) ON CONFLICT DO NOTHING;
