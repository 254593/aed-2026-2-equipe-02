package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * O contrato comercial vigente da empresa numa competencia: quantos Pix ele tem
 * isentos no mes, quanto custa cada faixa de valor acima disso, e qual o teto
 * de gasto mensal, se houver.
 *
 * ELE GUARDA A REGRA, NAO O RESULTADO. Quem decide o que acontece com um Pix
 * especifico e o TarifacaoService, confrontando esta oferta com o consumo ja
 * registrado no mes. O VO nao conhece o evento nem o banco — sabe apenas
 * responder "quanto custaria um Pix deste valor" e "qual o meu teto".
 *
 * A AUSENCIA DESTE OBJETO TAMBEM E UMA RESPOSTA. Cliente sem oferta vigente na
 * competencia nao produz um OfertaVO padrao: produz nenhum, e o service trata
 * isso como SEM_CONTRATO. Nao existe plano de fallback, e a razao esta no
 * ADR-002 — cobrar sem contrato e cobranca indevida.
 *
 * Objeto de valor: sem identidade propria, imutavel, com copia defensiva da
 * lista de faixas.
 */
public final class OfertaVO {

    private final String idEmpresa;
    private final String vigenciaInicio;
    private final int pixGratuitosMes;

    /** Teto de gasto no mes. Nulo significa plano sem teto. */
    private final BigDecimal tetoMensal;

    private final List<FaixaDeTarifaVO> faixas;

    public OfertaVO(String idEmpresa,
                    String vigenciaInicio,
                    int pixGratuitosMes,
                    BigDecimal tetoMensal,
                    List<FaixaDeTarifaVO> faixas) {

        this.idEmpresa = Objects.requireNonNull(idEmpresa, "idEmpresa e obrigatorio");
        this.vigenciaInicio = Objects.requireNonNull(vigenciaInicio, "vigenciaInicio e obrigatoria");
        this.pixGratuitosMes = pixGratuitosMes;
        this.tetoMensal = tetoMensal;

        List<FaixaDeTarifaVO> copia = new ArrayList<FaixaDeTarifaVO>();
        if (faixas != null) {
            copia.addAll(faixas);
        }
        this.faixas = Collections.unmodifiableList(copia);
    }

    /**
     * Quanto custa um Pix deste valor, ja passada a franquia.
     *
     * Percorre as faixas na ordem cadastrada e devolve a primeira que cobre o
     * valor. Oferta sem faixa nenhuma e contrato mal cadastrado, nao contrato
     * gratuito: deixar passar como zero esconderia o erro de cadastro dentro do
     * extrato da empresa, onde ninguem procuraria por ele.
     */
    public BigDecimal tarifaPara(BigDecimal valorDoPix) {
        for (FaixaDeTarifaVO faixa : faixas) {
            if (faixa.cobre(valorDoPix)) {
                return faixa.getValorTarifa();
            }
        }
        throw new IllegalStateException(
                "oferta de " + idEmpresa + " vigente desde " + vigenciaInicio
                        + " nao tem faixa que cubra um Pix de " + valorDoPix);
    }

    public boolean temTetoMensal() {
        return tetoMensal != null;
    }

    public String getIdEmpresa() {
        return idEmpresa;
    }

    public String getVigenciaInicio() {
        return vigenciaInicio;
    }

    public int getPixGratuitosMes() {
        return pixGratuitosMes;
    }

    public BigDecimal getTetoMensal() {
        return tetoMensal;
    }

    public List<FaixaDeTarifaVO> getFaixas() {
        return faixas;
    }

    @Override
    public String toString() {
        return "OfertaVO{idEmpresa=" + idEmpresa
                + ", vigenciaInicio=" + vigenciaInicio
                + ", pixGratuitosMes=" + pixGratuitosMes
                + ", tetoMensal=" + tetoMensal
                + ", faixas=" + faixas.size() + "}";
    }
}
