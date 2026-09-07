package br.pucminas.aed.tarifacao.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.pucminas.aed.tarifacao.domain.FaturaDaCompetenciaVO;
import br.pucminas.aed.tarifacao.domain.SituacaoDaTarifaVO;

/**
 * O projetor da `fatura_competencia` — o lado de LEITURA do CicloDeTarifacao
 * (ADR-005).
 *
 * ELE E ASSINCRONO, E FORA DA TRANSACAO DA DECISAO. Poderia ser mais simples
 * atualizar a projecao no mesmo commit que grava a tarifa, e a janela de
 * defasagem desapareceria — mas a projecao passaria a ser escrita pelo caminho
 * de decisao, e "derivada" viraria promessa em vez de propriedade. Sendo
 * assincrono, o unico jeito de a projecao existir e alguem tendo lido o log.
 *
 * ELE AVANCA POR VERSAO, NAO POR TEMPO. Para cada stream com fatos pendentes,
 * le apenas o que esta acima da `versao_projetada` e soma sobre a linha
 * existente. Nao ha "processar desde ontem" nem marca d'agua de relogio: a
 * posicao e uma versao, que e ordem logica e nao depende de fuso, de atraso de
 * entrega nem do relogio de quem pergunta.
 *
 * E RECONSTRUIR USA O MESMO CODIGO. `reconstruir()` apaga a tabela e chama
 * `avancar()` — a partir da versao zero, o avanco incremental E o replay
 * completo. Um caminho separado de rebuild seria a forma mais provavel de o
 * teste de reconstrucao passar provando a coerencia de um codigo que nao e o
 * que roda em producao.
 *
 * CONCORRENCIA: uma instancia, um agendamento com fixedDelay, sem sobreposicao.
 * O UPDATE ainda assim carrega a versao lida como guarda — se outro projetor
 * tiver avancado o stream nesse intervalo, a atualizacao nao acha a linha e o
 * ciclo seguinte reprocessa a partir da posicao correta.
 */
@Service
public class FaturaProjetor {

    private static final Logger log = LoggerFactory.getLogger(FaturaProjetor.class);

    private final JdbcTemplate jdbc;

    public FaturaProjetor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Incorpora à projeção todos os fatos ainda não projetados.
     *
     * @return quantos streams avancaram.
     */
    public int avancar() {
        List<StreamPendente> pendentes = jdbc.query(
                "SELECT t.id_empresa, t.competencia, "
                        + "       COALESCE(MAX(f.versao_projetada), 0) AS desde "
                        + "  FROM tarifa t "
                        + "  LEFT JOIN fatura_competencia f "
                        + "         ON f.id_empresa = t.id_empresa "
                        + "        AND f.competencia = t.competencia "
                        + " GROUP BY t.id_empresa, t.competencia "
                        + "HAVING MAX(t.versao) > COALESCE(MAX(f.versao_projetada), 0)",
                (rs, linha) -> new StreamPendente(
                        rs.getString("id_empresa"),
                        rs.getString("competencia"),
                        rs.getLong("desde")));

        int avancados = 0;
        for (StreamPendente stream : pendentes) {
            if (projetar(stream)) {
                avancados++;
            }
        }
        if (avancados > 0) {
            log.info("projecao avancada em {} stream(s)", Integer.valueOf(avancados));
        }
        return avancados;
    }

    /**
     * O TESTE DE SANIDADE DESTA ARQUITETURA, exposto como operacao.
     *
     * Apaga a projecao inteira e a reconstroi pelo log. Se o resultado nao for
     * identico, a projecao havia virado fonte da verdade sem ninguem decidir —
     * alguem escreveu nela por fora, ou o projetor nao e uma funcao pura do
     * historico. E por isso que apagar precisa ser seguro: e assim que se
     * corrige um bug de projetor, no codigo e nao em migracao de dados.
     */
    @Transactional
    public int reconstruir() {
        jdbc.update("DELETE FROM fatura_competencia");
        log.info("projecao apagada; reconstruindo pelo replay do log");
        return avancar();
    }

    /** A linha da projecao, se o stream ja tiver sido projetado. */
    public Optional<FaturaDaCompetenciaVO> buscar(String idEmpresa, String competencia) {
        List<FaturaDaCompetenciaVO> encontradas = jdbc.query(
                "SELECT * FROM fatura_competencia WHERE id_empresa = ? AND competencia = ?",
                (rs, linha) -> {
                    Map<SituacaoDaTarifaVO, Long> porSituacao =
                            new EnumMap<SituacaoDaTarifaVO, Long>(SituacaoDaTarifaVO.class);
                    for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
                        porSituacao.put(situacao, Long.valueOf(rs.getLong(colunaDe(situacao))));
                    }
                    return new FaturaDaCompetenciaVO(
                            rs.getString("id_empresa"),
                            rs.getString("competencia"),
                            rs.getBigDecimal("total_tarifado"),
                            rs.getLong("qtd_pix"),
                            porSituacao,
                            rs.getLong("versao_projetada"));
                },
                idEmpresa, competencia);

        if (encontradas.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(encontradas.get(0));
    }

    /**
     * Incorpora os fatos de UM stream acima da versao ja projetada.
     *
     * NAO leva @Transactional, e a ausencia e deliberada. Ela seria inerte de
     * qualquer forma — o metodo e chamado por avancar(), na propria instancia, e
     * o proxy do Spring nao intercepta auto-invocacao —, e anotacao inerte e
     * pior que anotacao ausente: passa a impressao de uma garantia que nao
     * existe. Nao faz falta porque cada stream sofre UMA escrita, o UPDATE ou o
     * INSERT, e uma instrucao ja e atomica. Quando o caminho e reconstruir(),
     * que e chamado de fora e por isso transacional de verdade, estas escritas
     * participam daquela transacao.
     */
    private boolean projetar(StreamPendente stream) {
        Delta delta = lerDelta(stream);
        if (delta == null || delta.quantidade == 0L) {
            return false;
        }

        int atualizadas = jdbc.update(somaNoUpdate(),
                parametrosDoUpdate(delta, stream));

        if (atualizadas == 0) {
            jdbc.update(insercao(), parametrosDoInsert(delta, stream));
        }
        return true;
    }

    /**
     * O fold dos fatos pendentes de um stream.
     *
     * As colunas por situacao sao derivadas do enum, e nao escritas a mao: se
     * alguem acrescentar uma saida a politica sem acrescentar a coluna, isto
     * falha alto na primeira execucao. A alternativa — cinco expressoes fixas —
     * simplesmente deixaria de contar a saida nova, em silencio, e o total da
     * projecao divergiria do log sem nenhum sinal.
     */
    private Delta lerDelta(StreamPendente stream) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS qtd, COALESCE(SUM(valor), 0) AS total, MAX(versao) AS ate");
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            sql.append(", SUM(CASE WHEN situacao = '").append(situacao.name())
               .append("' THEN 1 ELSE 0 END) AS ").append(colunaDe(situacao));
        }
        sql.append(" FROM tarifa WHERE id_empresa = ? AND competencia = ? AND versao > ?");

        return jdbc.query(sql.toString(), rs -> {
            if (!rs.next()) {
                return null;
            }
            Map<SituacaoDaTarifaVO, Long> porSituacao =
                    new EnumMap<SituacaoDaTarifaVO, Long>(SituacaoDaTarifaVO.class);
            for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
                porSituacao.put(situacao, Long.valueOf(rs.getLong(colunaDe(situacao))));
            }
            return new Delta(rs.getLong("qtd"), rs.getBigDecimal("total"),
                    rs.getLong("ate"), porSituacao);
        }, stream.idEmpresa, stream.competencia, Long.valueOf(stream.desde));
    }

    private String somaNoUpdate() {
        StringBuilder sql = new StringBuilder("UPDATE fatura_competencia SET "
                + " total_tarifado = total_tarifado + ?, qtd_pix = qtd_pix + ?");
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            sql.append(", ").append(colunaDe(situacao))
               .append(" = ").append(colunaDe(situacao)).append(" + ?");
        }
        sql.append(", versao_projetada = ? "
                + " WHERE id_empresa = ? AND competencia = ? AND versao_projetada = ?");
        return sql.toString();
    }

    private Object[] parametrosDoUpdate(Delta delta, StreamPendente stream) {
        List<Object> p = new ArrayList<Object>();
        p.add(delta.total);
        p.add(Long.valueOf(delta.quantidade));
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            p.add(delta.porSituacao.get(situacao));
        }
        p.add(Long.valueOf(delta.ateVersao));
        p.add(stream.idEmpresa);
        p.add(stream.competencia);
        p.add(Long.valueOf(stream.desde));
        return p.toArray();
    }

    private String insercao() {
        StringBuilder colunas = new StringBuilder(
                "INSERT INTO fatura_competencia (id_empresa, competencia, total_tarifado, qtd_pix");
        StringBuilder valores = new StringBuilder(") VALUES (?, ?, ?, ?");
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            colunas.append(", ").append(colunaDe(situacao));
            valores.append(", ?");
        }
        colunas.append(", versao_projetada");
        valores.append(", ?)");
        return colunas.toString() + valores.toString();
    }

    private Object[] parametrosDoInsert(Delta delta, StreamPendente stream) {
        List<Object> p = new ArrayList<Object>();
        p.add(stream.idEmpresa);
        p.add(stream.competencia);
        p.add(delta.total);
        p.add(Long.valueOf(delta.quantidade));
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            p.add(delta.porSituacao.get(situacao));
        }
        p.add(Long.valueOf(delta.ateVersao));
        return p.toArray();
    }

    /** SEM_CONTRATO -> qtd_sem_contrato. A coluna e derivada do enum. */
    private static String colunaDe(SituacaoDaTarifaVO situacao) {
        return "qtd_" + situacao.name().toLowerCase();
    }

    /** Um stream com fatos acima da versao ja projetada. */
    static final class StreamPendente {
        private final String idEmpresa;
        private final String competencia;
        private final long desde;

        StreamPendente(String idEmpresa, String competencia, long desde) {
            this.idEmpresa = idEmpresa;
            this.competencia = competencia;
            this.desde = desde;
        }
    }

    /** O resultado do fold dos fatos pendentes. */
    private static final class Delta {
        private final long quantidade;
        private final BigDecimal total;
        private final long ateVersao;
        private final Map<SituacaoDaTarifaVO, Long> porSituacao;

        Delta(long quantidade, BigDecimal total, long ateVersao,
              Map<SituacaoDaTarifaVO, Long> porSituacao) {
            this.quantidade = quantidade;
            this.total = total;
            this.ateVersao = ateVersao;
            this.porSituacao = porSituacao;
        }
    }
}
