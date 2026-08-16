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
 *   oferta             o contrato vigente da empresa — a REGRA que decide
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
     * A chave e o eventoId (ce_id), nao o idTransacaoPix: dois eventos diferentes podem
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
     * A competencia vem do liquidadoEm do evento, e a busca precisa acompanhar:
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
    public Optional<OfertaVO> buscarOfertaVigente(String idEmpresa, String competencia) {

        // Sem as faixas, de proposito: buscá-las dentro do RowMapper dispararia
        // uma segunda consulta com este ResultSet ainda aberto, o que parte dos
        // drivers recusa. Duas consultas em sequencia, e nao uma aninhada.
        List<OfertaVO> vigentes = jdbc.query(
                "SELECT id_empresa, vigencia_inicio, pix_gratuitos_mes, teto_mensal "
                        + "  FROM oferta "
                        + " WHERE id_empresa = ? "
                        + "   AND vigencia_inicio <= ? "
                        + "   AND (vigencia_fim IS NULL OR vigencia_fim >= ?) "
                        + " ORDER BY vigencia_inicio DESC",
                (rs, linha) -> new OfertaVO(
                        rs.getString("id_empresa"),
                        rs.getString("vigencia_inicio"),
                        rs.getInt("pix_gratuitos_mes"),
                        rs.getBigDecimal("teto_mensal"),
                        Collections.<FaixaDeTarifaVO>emptyList()),
                idEmpresa, competencia, competencia);

        if (vigentes.isEmpty()) {
            return Optional.empty();
        }

        OfertaVO semFaixas = vigentes.get(0);
        List<FaixaDeTarifaVO> faixas =
                buscarFaixas(semFaixas.getIdEmpresa(), semFaixas.getVigenciaInicio());

        return Optional.of(new OfertaVO(
                semFaixas.getIdEmpresa(),
                semFaixas.getVigenciaInicio(),
                semFaixas.getPixGratuitosMes(),
                semFaixas.getTetoMensal(),
                faixas));
    }

    /** As faixas de preco de uma vigencia, na ordem em que devem ser avaliadas. */
    private List<FaixaDeTarifaVO> buscarFaixas(String idEmpresa, String vigenciaInicio) {
        return jdbc.query(
                "SELECT valor_abaixo_de, valor_tarifa "
                        + "  FROM oferta_faixa "
                        + " WHERE id_empresa = ? AND vigencia_inicio = ? "
                        + " ORDER BY ordem",
                (rs, linha) -> new FaixaDeTarifaVO(
                        rs.getBigDecimal("valor_abaixo_de"),
                        rs.getBigDecimal("valor_tarifa")),
                idEmpresa, vigenciaInicio);
    }

    // ------------------------------------------------------------------
    // tarifa - o efeito de negocio
    // ------------------------------------------------------------------

    /**
     * Quantas unidades de franquia a empresa ja consumiu na competencia — o
     * `unidadesFranquiaConsumidas` da especificacao.
     *
     * Nao e "quantas linhas de tarifa existem": so o Pix ISENTO consome. Os
     * tarifados e os nao cobrados viram linha, porque sao fatos, mas nao gastam
     * uma cota — os primeiros existem justamente porque nao havia mais nenhuma.
     * Contar tudo violaria a invariante da especificacao
     * (unidadesFranquiaConsumidas <= franquia do plano).
     *
     * Quem sabe quais situacoes consomem e o proprio enum: a lista do IN e
     * derivada dele, e nao escrita a mao, para que uma situacao nova
     * acrescentada amanha nao passe silenciosamente por fora desta contagem.
     *
     * DERIVAR EM VEZ DE ACUMULAR e deliberado. A especificacao descreve um
     * campo `unidadesFranquiaConsumidas` que incrementa a cada isencao; aqui ele
     * e calculado por COUNT sobre a propria tabela `tarifa`. O resultado e o
     * mesmo e a propriedade importante se mantem — o acumulado nunca decrementa
     * —, mas sem um UPDATE em delta, que e exatamente o tipo de operacao que a
     * reentrega quebra: contar linhas e naturalmente idempotente, somar +1 nao.
     */
    public long contarFranquiaConsumida(String idEmpresa, String competencia) {
        List<String> situacoes = situacoesQueConsomemFranquia();

        StringBuilder marcadores = new StringBuilder();
        for (int i = 0; i < situacoes.size(); i++) {
            if (i > 0) {
                marcadores.append(", ");
            }
            marcadores.append('?');
        }

        List<Object> parametros = new ArrayList<Object>();
        parametros.add(idEmpresa);
        parametros.add(competencia);
        parametros.addAll(situacoes);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tarifa "
                        + " WHERE id_empresa = ? AND competencia = ? "
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

    /**
     * Total em reais ja cobrado da empresa na competencia — o
     * `valorTarifadoNaCompetencia` da especificacao. Alimenta o teto mensal.
     *
     * Derivado por SUM sobre as linhas de cobranca, no mesmo espirito do
     * DERIVAR EM VEZ DE ACUMULAR documentado em contarFranquiaConsumida. A
     * propriedade que sustenta o teto e que este acumulado NUNCA DECREMENTA:
     * estorno e ajuste de fatura em acumulador proprio
     * (valorEstornadoNaCompetencia, na especificacao) e JAMAIS pode virar linha
     * negativa na tabela `tarifa` — se virar, o SUM decrementa, o teto reabre
     * em silencio e decisoes ja tomadas ficam inconsistentes.
     */
    public BigDecimal totalTarifadoNaCompetencia(String idEmpresa, String competencia) {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM tarifa WHERE id_empresa = ? AND competencia = ?",
                BigDecimal.class, idEmpresa, competencia);
        if (total == null) {
            return BigDecimal.ZERO;
        }
        return total;
    }

    /** Todos os Pix processados da empresa na competencia, cobrados ou nao. */
    public long contarPixNaCompetencia(String idEmpresa, String competencia) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tarifa WHERE id_empresa = ? AND competencia = ?",
                Long.class, idEmpresa, competencia);
        if (total == null) {
            return 0L;
        }
        return total.longValue();
    }

    /** Quantos Pix da empresa naquela competencia terminaram numa dada situacao. */
    public long contarPorSituacao(String idEmpresa, String competencia,
            SituacaoDaTarifaVO situacao) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tarifa "
                        + " WHERE id_empresa = ? AND competencia = ? AND situacao = ?",
                Long.class, idEmpresa, competencia, situacao.name());
        if (total == null) {
            return 0L;
        }
        return total.longValue();
    }

    /**
     * Uma linha por Pix processado, sempre — inclusive quando nao houve
     * cobranca. A isencao, a falta de contrato e o teto atingido tambem sao
     * fatos, e e a coluna situacao que os distingue de uma cobranca de zero.
     *
     * O eventoId vem COMO PARAMETRO, e nao de evento.getEventoId(). As duas
     * tabelas precisam gravar a MESMA identidade, e a identidade que vale e a
     * que o listener resolveu — o ce_id do envelope, com o corpo apenas como
     * rede de seguranca.
     *
     * Tirar daqui do corpo criaria duas fontes para a mesma chave, e o modo de
     * falha seria o pior possivel: um produtor que publicasse ce_id diferente
     * do eventoId do corpo passaria pela deduplicacao (ce_id novo) e estouraria
     * na chave primaria desta tabela (evento_id repetido). A transacao faz
     * rollback, a excecao sobe ao listener, o offset nunca e confirmado, e a
     * particao inteira trava em retentativa — exatamente o que o
     * TarifacaoListener documenta querer evitar quando trata o ce_id ausente.
     */
    public void registrarTarifa(String eventoId, PixRealizadoEvent evento,
            String competencia, DecisaoDeTarifacaoVO decisao) {
        jdbc.update("INSERT INTO tarifa "
                        + "(evento_id, id_empresa, id_transacao_pix, competencia, "
                        + " situacao, valor, liquidado_em) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventoId,
                evento.getIdEmpresa(),
                evento.getIdTransacaoPix(),
                competencia,
                decisao.getSituacao().name(),
                decisao.getValor(),
                Timestamp.from(evento.getLiquidadoEm()));
    }

    public void limparTarifas() {
        jdbc.update("DELETE FROM tarifa");
    }
}
