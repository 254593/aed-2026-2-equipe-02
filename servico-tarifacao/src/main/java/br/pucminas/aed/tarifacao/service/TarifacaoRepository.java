package br.pucminas.aed.tarifacao.service;

import java.math.BigDecimal;
import java.sql.Timestamp;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import br.pucminas.aed.tarifacao.domain.OfertaVO;
import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;

/**
 * Toda a persistencia do servico, num lugar so. Sao tres tabelas com papeis bem
 * distintos:
 *
 *   evento_processado  memoria do que ja foi visto — sustenta a IDEMPOTENCIA
 *   oferta             o plano do cliente — a REGRA que decide isencao
 *   tarifa             o efeito de negocio — o que nao pode acontecer 2 vezes
 *
 * As tres moram na mesma classe porque duas delas precisam ser escritas na
 * MESMA transacao. E isso que fecha a janela em que o processo morreria entre
 * registrar a chave do evento e aplicar a tarifa.
 *
 * Os metodos recebem valores, nao o evento inteiro, exceto onde a linha da
 * tarifa e literalmente o espelho do evento. Repositorio nao orquestra: quem
 * decide o valor e o TarifacaoService.
 */
@Repository
public class TarifacaoRepository {

    /**
     * Plano usado quando o cliente nao tem linha em `oferta`. Num sistema real
     * isso viria de um cadastro ou de uma chamada ao sistema de produtos; aqui
     * o padrao evita que um Pix de cliente desconhecido derrube o consumidor e
     * trave a particao inteira.
     */
    private static final int FRANQUIA_PADRAO = 5;
    private static final BigDecimal TARIFA_PADRAO = new BigDecimal("1.90");

    private final JdbcTemplate jdbc;

    public TarifacaoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // idempotencia
    // ------------------------------------------------------------------

    /**
     * INSERT ... ON CONFLICT DO NOTHING devolve 1 quando a linha e nova e 0
     * quando ja existia. E isso, e so isso, que distingue "primeira entrega" de
     * "reentrega".
     *
     * A chave e o eventoId (ce_id), nao o pixId: dois eventos diferentes podem
     * falar do mesmo Pix — um PixRealizado e, mais tarde, um PixDevolvido.
     * Deduplicar pelo id da entidade descartaria o segundo como se fosse
     * repeticao do primeiro.
     *
     * @return true se o evento e novo; false se ja foi processado antes.
     */
    public boolean registrarEventoSeNovo(String eventoId) {
        int linhas = jdbc.update(
                "INSERT INTO evento_processado (evento_id) VALUES (?) ON CONFLICT DO NOTHING",
                eventoId);
        return linhas == 1;
    }

    public long contarEventosProcessados() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM evento_processado", Long.class);
        if (total == null) {
            return 0L;
        }
        return total.longValue();
    }

    public void limparEventosProcessados() {
        jdbc.update("DELETE FROM evento_processado");
    }

    // ------------------------------------------------------------------
    // oferta - a regra
    // ------------------------------------------------------------------

    public OfertaVO buscarOferta(String clienteId) {
        try {
            return jdbc.queryForObject(
                    "SELECT cliente_id, pix_gratuitos_mes, valor_tarifa FROM oferta WHERE cliente_id = ?",
                    (rs, linha) -> new OfertaVO(
                            rs.getString("cliente_id"),
                            rs.getInt("pix_gratuitos_mes"),
                            rs.getBigDecimal("valor_tarifa")),
                    clienteId);
        } catch (EmptyResultDataAccessException clienteSemPlano) {
            return new OfertaVO(clienteId, FRANQUIA_PADRAO, TARIFA_PADRAO);
        }
    }

    // ------------------------------------------------------------------
    // tarifa - o efeito de negocio
    // ------------------------------------------------------------------

    /**
     * Quantos Pix deste cliente ja foram processados na competencia.
     *
     * Conta a tabela `tarifa` inteira, e nao so as linhas com valor maior que
     * zero: o Pix isento tambem consome franquia, e por isso tambem vira linha.
     * Um contador em coluna separada faria o mesmo trabalho e ainda seria um
     * UPDATE em delta, que e o tipo de operacao que a reentrega quebra.
     */
    public long contarPixNaCompetencia(String clienteId, String competencia) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tarifa WHERE cliente_id = ? AND competencia = ?",
                Long.class, clienteId, competencia);
        if (total == null) {
            return 0L;
        }
        return total.longValue();
    }

    /** Uma linha por Pix. Valor 0.00 quando isento — a isencao tambem e um fato. */
    public void registrarTarifa(PixRealizadoEvent evento, String competencia, BigDecimal valor) {
        jdbc.update("INSERT INTO tarifa "
                        + "(evento_id, cliente_id, pix_id, competencia, valor, ocorrido_em) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                evento.getEventoId(),
                evento.getClienteId(),
                evento.getPixId(),
                competencia,
                valor,
                Timestamp.from(evento.getOcorridoEm()));
    }

    public BigDecimal totalTarifadoNaCompetencia(String clienteId, String competencia) {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM tarifa WHERE cliente_id = ? AND competencia = ?",
                BigDecimal.class, clienteId, competencia);
        if (total == null) {
            return BigDecimal.ZERO;
        }
        return total;
    }

    public void limparTarifas() {
        jdbc.update("DELETE FROM tarifa");
    }
}
