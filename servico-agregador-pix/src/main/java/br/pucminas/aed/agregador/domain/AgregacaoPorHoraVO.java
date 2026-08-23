package br.pucminas.aed.agregador.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Resultado da agregação de Pix por janela de tempo (hora).
 * Imutável, representa a soma de valores liquidados em uma hora específica.
 */
public final class AgregacaoPorHoraVO {

    private final Instant inicioJanela;
    private final Instant fimJanela;
    private final BigDecimal valorTotal;
    private final long quantidade;

    @JsonCreator
    public AgregacaoPorHoraVO(@JsonProperty("inicioJanela") Instant inicioJanela,
                              @JsonProperty("fimJanela") Instant fimJanela,
                              @JsonProperty("valorTotal") BigDecimal valorTotal,
                              @JsonProperty("quantidade") long quantidade) {
        this.inicioJanela = Objects.requireNonNull(inicioJanela, "inicioJanela é obrigatório");
        this.fimJanela = Objects.requireNonNull(fimJanela, "fimJanela é obrigatório");
        this.valorTotal = Objects.requireNonNull(valorTotal, "valorTotal é obrigatório");
        this.quantidade = quantidade;
    }

    public Instant getInicioJanela() {
        return inicioJanela;
    }

    public Instant getFimJanela() {
        return fimJanela;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public long getQuantidade() {
        return quantidade;
    }

    @Override
    public String toString() {
        return "AgregacaoPorHora{" +
                "janela=[" + inicioJanela + ", " + fimJanela + "]" +
                ", valorTotal=" + valorTotal +
                ", quantidade=" + quantidade +
                "}";
    }
}
