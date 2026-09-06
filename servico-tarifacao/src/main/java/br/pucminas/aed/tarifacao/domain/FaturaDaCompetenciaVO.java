package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Uma linha da projecao `fatura_competencia` — o modelo de LEITURA do
 * CicloDeTarifacao (ADR-005).
 *
 * E derivada e descartavel. Nenhuma decisao de tarifacao le daqui: a politica
 * continua lendo os fatos da tabela `tarifa`. Se um dia a decisao passar a ler
 * esta projecao, ela deixa de ser cache e vira fonte da verdade sem que
 * ninguem tenha decidido isso.
 *
 * `versaoProjetada` e a ultima versao do stream ja incorporada, e nao e
 * detalhe de implementacao vazado para o modelo: e o que torna a DEFASAGEM
 * mensuravel em vez de estimada. O atraso desta linha e a distancia entre a
 * versao atual do stream e este numero, em fatos — nao em segundos, que
 * dependeriam do relogio de quem pergunta.
 */
public final class FaturaDaCompetenciaVO {

    private final String idEmpresa;
    private final String competencia;
    private final BigDecimal totalTarifado;
    private final long quantidadeDePix;
    private final Map<SituacaoDaTarifaVO, Long> porSituacao;
    private final long versaoProjetada;

    public FaturaDaCompetenciaVO(String idEmpresa,
                                 String competencia,
                                 BigDecimal totalTarifado,
                                 long quantidadeDePix,
                                 Map<SituacaoDaTarifaVO, Long> porSituacao,
                                 long versaoProjetada) {

        this.idEmpresa = Objects.requireNonNull(idEmpresa, "idEmpresa e obrigatorio");
        this.competencia = Objects.requireNonNull(competencia, "competencia e obrigatoria");
        this.totalTarifado = Objects.requireNonNull(totalTarifado, "totalTarifado e obrigatorio");
        this.quantidadeDePix = quantidadeDePix;
        this.versaoProjetada = versaoProjetada;

        Map<SituacaoDaTarifaVO, Long> copia = new EnumMap<SituacaoDaTarifaVO, Long>(SituacaoDaTarifaVO.class);
        if (porSituacao != null) {
            copia.putAll(porSituacao);
        }
        this.porSituacao = Collections.unmodifiableMap(copia);
    }

    public String getIdEmpresa() {
        return idEmpresa;
    }

    public String getCompetencia() {
        return competencia;
    }

    public BigDecimal getTotalTarifado() {
        return totalTarifado;
    }

    public long getQuantidadeDePix() {
        return quantidadeDePix;
    }

    /** Quantos Pix da competencia terminaram numa dada situacao. */
    public long getQuantidadeDe(SituacaoDaTarifaVO situacao) {
        Long quantidade = porSituacao.get(situacao);
        if (quantidade == null) {
            return 0L;
        }
        return quantidade.longValue();
    }

    public Map<SituacaoDaTarifaVO, Long> getPorSituacao() {
        return porSituacao;
    }

    public long getVersaoProjetada() {
        return versaoProjetada;
    }

    @Override
    public String toString() {
        return "FaturaDaCompetenciaVO{" + idEmpresa + " " + competencia
                + ", total=" + totalTarifado
                + ", pix=" + quantidadeDePix
                + ", ateVersao=" + versaoProjetada + "}";
    }
}
