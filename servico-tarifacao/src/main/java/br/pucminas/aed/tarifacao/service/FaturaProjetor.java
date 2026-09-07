package br.pucminas.aed.tarifacao.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
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
 * ELE ESCREVE O FOLD ABSOLUTO, NAO UM DELTA. Cada passagem recalcula o estado
 * inteiro do stream a partir do log e o ATRIBUI a linha da projecao. Nao ha
 * `total = total + ?` em lugar nenhum, e a razao esta escrita neste mesmo
 * projeto, no javadoc de TarifacaoRepository.contarFranquiaConsumida: "contar
 * linhas e naturalmente idempotente, somar +1 nao". Somar delta obrigava uma
 * marca d'agua a funcionar como trava, e uma trava perdida deixava a projecao
 * permanentemente curta em silencio. Atribuindo o absoluto, duas passagens
 * concorrentes escrevem o MESMO valor e a ultima a vencer esta certa.
 *
 * A CONSEQUENCIA E QUE ELE SE CURA. Qualquer divergencia entre o log e a
 * projecao — linha apagada, marca d'agua adiantada por restauracao parcial,
 * escrita concorrente — desaparece na passagem seguinte, porque a passagem
 * seguinte nao confia no que estava gravado. Nao existe estado do qual nao se
 * consiga voltar.
 *
 * `versao_projetada` CONTINUA GRAVADA, mas como POSICAO RELATADA e nao como
 * trava: e o que permite medir a defasagem em fatos, e o que a consulta de
 * descoberta compara para saber quais streams estao fora de sincronia.
 *
 * E RECONSTRUIR USA O MESMO CODIGO. `reconstruir()` apaga a tabela e chama
 * `avancar()`. Com o fold absoluto os dois caminhos passaram a ser o mesmo de
 * fato, e nao por argumento: `avancar()` sozinho ja reescreve o estado correto,
 * e o DELETE virou conveniencia, nao mecanismo.
 */
@Service
public class FaturaProjetor {

    private static final Logger log = LoggerFactory.getLogger(FaturaProjetor.class);

    private final JdbcTemplate jdbc;

    public FaturaProjetor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reprojeta todo stream cuja projecao nao corresponde ao log.
     *
     * A COMPARACAO E DE DESIGUALDADE, NAO DE "MAIOR QUE". Com `>` um
     * `versao_projetada` ADIANTADO em relacao ao log — restauracao parcial da
     * `tarifa`, expurgo, contaminacao entre testes — deixava o stream fora do
     * resultado para sempre, e a consulta de defasagem da entrega passava a
     * relatar atraso NEGATIVO, isto e, mais saudavel que zero. Comparando com
     * `<>`, o desvio nos dois sentidos e detectado e corrigido.
     *
     * A contagem de fatos entra na comparacao junto com a versao porque uma
     * das duas pode divergir sozinha: um banco migrado carrega fatos com
     * `versao` nula (ver o ALTER TABLE no schema.sql), e ali a versao maxima
     * nao muda quando um fato antigo e reprojetado.
     *
     * @return quantos streams foram reprojetados.
     */
    public int avancar() {
        List<StreamPendente> pendentes = jdbc.query(
                "SELECT s.id_empresa, s.competencia "
                        + "  FROM (SELECT id_empresa, competencia, "
                        + "               COALESCE(MAX(versao), 0) AS ultima, "
                        + "               COUNT(*)                 AS fatos "
                        + "          FROM tarifa "
                        + "         GROUP BY id_empresa, competencia) s "
                        + "  LEFT JOIN fatura_competencia f "
                        + "         ON f.id_empresa  = s.id_empresa "
                        + "        AND f.competencia = s.competencia "
                        + " WHERE f.id_empresa IS NULL "
                        + "    OR s.ultima <> f.versao_projetada "
                        + "    OR s.fatos  <> f.qtd_pix",
                (rs, linha) -> new StreamPendente(
                        rs.getString("id_empresa"),
                        rs.getString("competencia")));

        int reprojetados = 0;
        for (StreamPendente stream : pendentes) {
            if (projetar(stream)) {
                reprojetados++;
            }
        }
        if (reprojetados > 0) {
            // A frase e citada literalmente em docs/entregas/aula-05.md; nao mudar
            // sem atualizar o documento junto.
            log.info("projecao avancada em {} stream(s)", Integer.valueOf(reprojetados));
        }
        return reprojetados;
    }

    /**
     * O TESTE DE SANIDADE DESTA ARQUITETURA, exposto como operacao.
     *
     * Apaga a projecao inteira e a reconstroi pelo log. Se o resultado nao for
     * identico, a projecao havia virado fonte da verdade sem ninguem decidir —
     * alguem escreveu nela por fora, ou o projetor nao e uma funcao pura do
     * historico. E por isso que apagar precisa ser seguro: e assim que se
     * corrige um bug de projetor, no codigo e nao em migracao de dados.
     *
     * Com o fold absoluto, o DELETE deixou de ser NECESSARIO para reconstruir —
     * `avancar()` sozinho reescreve o estado correto de qualquer linha
     * divergente. Ele continua aqui porque tambem remove linha de stream que
     * nao existe mais no log, o que o avanco por si nao faz.
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
     * Reescreve a linha de UM stream com o fold absoluto do log.
     *
     * SAO DOIS STATEMENTS, E OS DOIS SAO IDEMPOTENTES. O primeiro garante que a
     * linha existe, com `ON CONFLICT DO NOTHING` — o mesmo idioma que o
     * `registrarEventoSeNovo` deste repositorio ja usa, e portanto ja provado
     * em Postgres e no H2 em MODE=PostgreSQL. O segundo ATRIBUI o fold.
     *
     * A ordem importa e e esta: inserir-se-novo antes de atualizar elimina o
     * caso que a versao anterior deste metodo errava. Ela fazia o inverso —
     * UPDATE e, se nenhuma linha casasse, INSERT — sem conseguir distinguir
     * "a linha nao existe" de "a linha existe e mudou". No segundo caso o
     * INSERT colidia com a chave primaria, ou pior: gravava um delta parcial
     * como se fosse o total, e o stream nunca era revisitado.
     *
     * NAO leva @Transactional, e a ausencia e deliberada: seria inerte, porque
     * o metodo e chamado por avancar() na propria instancia e o proxy do Spring
     * nao intercepta auto-invocacao — e anotacao inerte e pior que anotacao
     * ausente. Nao faz falta porque os dois statements convergem para o mesmo
     * estado independentemente da ordem em que se intercalem com outra
     * passagem. Quando o caminho e reconstruir(), chamado de fora e por isso
     * transacional de verdade, estas escritas participam daquela transacao.
     */
    private boolean projetar(StreamPendente stream) {
        Fold fold = lerFold(stream);
        if (fold == null) {
            return false;
        }

        jdbc.update(insercaoSeNova(), parametrosDaInsercao(fold, stream));
        jdbc.update(atribuicao(), parametrosDaAtribuicao(fold, stream));
        return true;
    }

    /**
     * O fold do stream INTEIRO — sem `versao > ?`.
     *
     * As colunas por situacao sao derivadas do enum, e nao escritas a mao: se
     * alguem acrescentar uma saida a politica sem acrescentar a coluna, isto
     * falha alto na primeira execucao. A alternativa — cinco expressoes fixas —
     * simplesmente deixaria de contar a saida nova, em silencio, e o total da
     * projecao divergiria do log sem nenhum sinal.
     */
    private Fold lerFold(StreamPendente stream) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS qtd, COALESCE(SUM(valor), 0) AS total, "
                        + "COALESCE(MAX(versao), 0) AS ate");
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            sql.append(", SUM(CASE WHEN situacao = '").append(situacao.name())
               .append("' THEN 1 ELSE 0 END) AS ").append(colunaDe(situacao));
        }
        sql.append(" FROM tarifa WHERE id_empresa = ? AND competencia = ?");

        return jdbc.query(sql.toString(), rs -> {
            if (!rs.next()) {
                return null;
            }
            Map<SituacaoDaTarifaVO, Long> porSituacao =
                    new EnumMap<SituacaoDaTarifaVO, Long>(SituacaoDaTarifaVO.class);
            for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
                porSituacao.put(situacao, Long.valueOf(rs.getLong(colunaDe(situacao))));
            }
            return new Fold(rs.getLong("qtd"), rs.getBigDecimal("total"),
                    rs.getLong("ate"), porSituacao);
        }, stream.idEmpresa, stream.competencia);
    }

    /** Garante a existencia da linha sem sobrescrever o que ja estiver la. */
    private String insercaoSeNova() {
        StringBuilder colunas = new StringBuilder(
                "INSERT INTO fatura_competencia (id_empresa, competencia, total_tarifado, qtd_pix");
        StringBuilder valores = new StringBuilder(") VALUES (?, ?, ?, ?");
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            colunas.append(", ").append(colunaDe(situacao));
            valores.append(", ?");
        }
        colunas.append(", versao_projetada");
        valores.append(", ?) ON CONFLICT DO NOTHING");
        return colunas.toString() + valores.toString();
    }

    private Object[] parametrosDaInsercao(Fold fold, StreamPendente stream) {
        List<Object> p = new ArrayList<Object>();
        p.add(stream.idEmpresa);
        p.add(stream.competencia);
        p.add(fold.total);
        p.add(Long.valueOf(fold.quantidade));
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            p.add(fold.porSituacao.get(situacao));
        }
        p.add(Long.valueOf(fold.ateVersao));
        return p.toArray();
    }

    /** ATRIBUI o fold. Repare que nao existe `coluna = coluna + ?` aqui. */
    private String atribuicao() {
        StringBuilder sql = new StringBuilder("UPDATE fatura_competencia SET "
                + " total_tarifado = ?, qtd_pix = ?");
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            sql.append(", ").append(colunaDe(situacao)).append(" = ?");
        }
        sql.append(", versao_projetada = ? "
                + " WHERE id_empresa = ? AND competencia = ?");
        return sql.toString();
    }

    private Object[] parametrosDaAtribuicao(Fold fold, StreamPendente stream) {
        List<Object> p = new ArrayList<Object>();
        p.add(fold.total);
        p.add(Long.valueOf(fold.quantidade));
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            p.add(fold.porSituacao.get(situacao));
        }
        p.add(Long.valueOf(fold.ateVersao));
        p.add(stream.idEmpresa);
        p.add(stream.competencia);
        return p.toArray();
    }

    /**
     * SEM_CONTRATO -> qtd_sem_contrato. A coluna e derivada do enum.
     *
     * Locale.ROOT NAO E ZELO GENERICO. Quatro dos cinco valores do enum tem a
     * letra I, e `toLowerCase()` sem locale usa o default da JVM: em turco,
     * FRANQUIA vira "franquıa", com o i sem pingo. Todas as colunas geradas
     * passariam a nao existir, e como o unico caminho em producao e o
     * @Scheduled, o Spring engoliria a excecao — a aplicacao subiria limpa,
     * consumiria o topico, e a fatura ficaria vazia para sempre sem um erro
     * visivel. O mesmo build, na mesma versao, dependendo do locale do host.
     */
    private static String colunaDe(SituacaoDaTarifaVO situacao) {
        return "qtd_" + situacao.name().toLowerCase(Locale.ROOT);
    }

    /** Um stream cuja projecao nao corresponde ao log. */
    static final class StreamPendente {
        private final String idEmpresa;
        private final String competencia;

        StreamPendente(String idEmpresa, String competencia) {
            this.idEmpresa = idEmpresa;
            this.competencia = competencia;
        }
    }

    /** O estado do stream inteiro, recalculado do log. */
    private static final class Fold {
        private final long quantidade;
        private final BigDecimal total;
        private final long ateVersao;
        private final Map<SituacaoDaTarifaVO, Long> porSituacao;

        Fold(long quantidade, BigDecimal total, long ateVersao,
             Map<SituacaoDaTarifaVO, Long> porSituacao) {
            this.quantidade = quantidade;
            this.total = total;
            this.ateVersao = ateVersao;
            this.porSituacao = porSituacao;
        }
    }
}
