package br.pucminas.aed.tarifacao.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import br.pucminas.aed.tarifacao.domain.DecisaoDeTarifacaoVO;
import br.pucminas.aed.tarifacao.domain.FaixaDeTarifaVO;
import br.pucminas.aed.tarifacao.domain.OfertaVO;
import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;
import br.pucminas.aed.tarifacao.domain.SituacaoDaTarifaVO;

/**
 * Toda a persistencia do servico, num lugar so. Sao quatro tabelas com papeis
 * bem distintos:
 *
 *   evento_processado  memoria do que ja foi visto — sustenta a IDEMPOTENCIA
 *   oferta             o contrato vigente do cliente — a REGRA que decide
 *   oferta_faixa       o preco por faixa de valor daquele contrato
 *   tarifa             o efeito de negocio — o que nao pode acontecer 2 vezes
 *
 * Elas moram na mesma classe porque duas delas precisam ser escritas na MESMA
 * transacao. E isso que fecha a janela em que o processo morreria entre
 * registrar a chave do evento e aplicar a tarifa.
 *
 * Repositorio nao decide: ele devolve a oferta e os numeros do mes, e quem
 * confronta os dois e o TarifacaoService.
 */
@Repository
public class TarifacaoRepository {

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

    /**
     * A oferta que vigia NA COMPETENCIA DO EVENTO — nunca "a oferta atual".
     *
     * A competencia vem do ocorridoEm do evento, e a busca precisa acompanhar:
     * um replay do topico feito em outubro tem de reencontrar o contrato que
     * vigia em agosto e chegar ao mesmo valor de antes. Buscar pela vigencia de
     * hoje faria o reprocessamento produzir um resultado diferente do original,
     * e o fechamento mensal — o quarto criterio do ADR-002 — deixaria de ser
     * reproduzivel.
     *
     * Devolve Optional VAZIO quando nao ha contrato vigente, e isso e uma
     * resposta legitima do dominio, nao um erro: o ADR-002 decide que empresa
     * sem contrato vigente na competencia nao e cobrada, e que nao existe plano
     * padrao de fallback.
     *
     * Se o cadastro tiver vigencias sobrepostas — o que a chave primaria nao
     * impede — vale a mais recente que ja tenha comecado. Ordenar e escolher e
     * mais seguro que assumir linha unica e estourar em producao.
     */
    public Optional<OfertaVO> buscarOfertaVigente(String clienteId, String competencia) {

        // Sem as faixas, de proposito: buscá-las dentro do RowMapper dispararia
        // uma segunda consulta com este ResultSet ainda aberto, o que parte dos
        // drivers recusa. Duas consultas em sequencia, e nao uma aninhada.
        List<OfertaVO> vigentes = jdbc.query(
                "SELECT cliente_id, vigencia_inicio, pix_gratuitos_mes, teto_mensal "
                        + "  FROM oferta "
                        + " WHERE cliente_id = ? "
                        + "   AND vigencia_inicio <= ? "
                        + "   AND (vigencia_fim IS NULL OR vigencia_fim >= ?) "
                        + " ORDER BY vigencia_inicio DESC",
                (rs, linha) -> new OfertaVO(
                        rs.getString("cliente_id"),
                        rs.getString("vigencia_inicio"),
                        rs.getInt("pix_gratuitos_mes"),
                        rs.getBigDecimal("teto_mensal"),
                        Collections.<FaixaDeTarifaVO>emptyList()),
                clienteId, competencia, competencia);

        if (vigentes.isEmpty()) {
            return Optional.empty();
        }

        OfertaVO semFaixas = vigentes.get(0);
        List<FaixaDeTarifaVO> faixas =
                buscarFaixas(semFaixas.getClienteId(), semFaixas.getVigenciaInicio());

        return Optional.of(new OfertaVO(
                semFaixas.getClienteId(),
                semFaixas.getVigenciaInicio(),
                semFaixas.getPixGratuitosMes(),
                semFaixas.getTetoMensal(),
                faixas));
    }

    /** As faixas de preco de uma vigencia, na ordem em que devem ser avaliadas. */
    private List<FaixaDeTarifaVO> buscarFaixas(String clienteId, String vigenciaInicio) {
        return jdbc.query(
                "SELECT valor_ate, valor_tarifa "
                        + "  FROM oferta_faixa "
                        + " WHERE cliente_id = ? AND vigencia_inicio = ? "
                        + " ORDER BY ordem",
                (rs, linha) -> new FaixaDeTarifaVO(
                        rs.getBigDecimal("valor_ate"),
                        rs.getBigDecimal("valor_tarifa")),
                clienteId, vigenciaInicio);
    }

    // ------------------------------------------------------------------
    // tarifa - o efeito de negocio
    // ------------------------------------------------------------------

    /**
     * Quantos Pix deste cliente ja consumiram franquia na competencia.
     *
     * Nao e "quantas linhas de tarifa existem": SEM_CONTRATO e TETO_ATINGIDO
     * viram linha, porque sao fatos, mas nao gastam uma cota que o cliente nao
     * contratou ou que ja nao esta sendo cobrada. Quem sabe quais situacoes
     * consomem e o proprio enum — a lista do IN e derivada dele, e nao escrita
     * a mao, para que uma situacao nova acrescentada amanha nao passe
     * silenciosamente por fora desta contagem.
     */
    public long contarFranquiaConsumida(String clienteId, String competencia) {
        List<String> situacoes = situacoesQueConsomemFranquia();

        StringBuilder marcadores = new StringBuilder();
        for (int i = 0; i < situacoes.size(); i++) {
            if (i > 0) {
                marcadores.append(", ");
            }
            marcadores.append('?');
        }

        List<Object> parametros = new ArrayList<Object>();
        parametros.add(clienteId);
        parametros.add(competencia);
        parametros.addAll(situacoes);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tarifa "
                        + " WHERE cliente_id = ? AND competencia = ? "
                        + "   AND situacao IN (" + marcadores + ")",
                Long.class, parametros.toArray());

        if (total == null) {
            return 0L;
        }
        return total.longValue();
    }

    private List<String> situacoesQueConsomemFranquia() {
        List<String> nomes = new ArrayList<String>();
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            if (situacao.consomeFranquia()) {
                nomes.add(situacao.name());
            }
        }
        return nomes;
    }

    /** Total em reais ja cobrado do cliente na competencia. Alimenta o teto mensal. */
    public BigDecimal totalTarifadoNaCompetencia(String clienteId, String competencia) {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM tarifa WHERE cliente_id = ? AND competencia = ?",
                BigDecimal.class, clienteId, competencia);
        if (total == null) {
            return BigDecimal.ZERO;
        }
        return total;
    }

    /** Todos os Pix processados do cliente na competencia, cobrados ou nao. */
    public long contarPixNaCompetencia(String clienteId, String competencia) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tarifa WHERE cliente_id = ? AND competencia = ?",
                Long.class, clienteId, competencia);
        if (total == null) {
            return 0L;
        }
        return total.longValue();
    }

    /** Quantos Pix do cliente naquela competencia terminaram numa dada situacao. */
    public long contarPorSituacao(String clienteId, String competencia,
            SituacaoDaTarifaVO situacao) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tarifa "
                        + " WHERE cliente_id = ? AND competencia = ? AND situacao = ?",
                Long.class, clienteId, competencia, situacao.name());
        if (total == null) {
            return 0L;
        }
        return total.longValue();
    }

    /**
     * Uma linha por Pix processado, sempre — inclusive quando nao houve
     * cobranca. A isencao, a falta de contrato e o teto atingido tambem sao
     * fatos, e e a coluna situacao que os distingue de uma cobranca de zero.
     */
    public void registrarTarifa(PixRealizadoEvent evento, String competencia,
            DecisaoDeTarifacaoVO decisao) {
        jdbc.update("INSERT INTO tarifa "
                        + "(evento_id, cliente_id, pix_id, competencia, situacao, valor, ocorrido_em) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                evento.getEventoId(),
                evento.getClienteId(),
                evento.getPixId(),
                competencia,
                decisao.getSituacao().name(),
                decisao.getValor(),
                Timestamp.from(evento.getOcorridoEm()));
    }

    public void limparTarifas() {
        jdbc.update("DELETE FROM tarifa");
    }
}
