package br.pucminas.aed.pix.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Dados recebidos pela API para registrar que um Pix foi realizado. */
public final class RealizacaoPixVO {

    private final String idTransacaoPix;
    private final String idEmpresa;
    private final BigDecimal valor;
    private final String chavePix;
    private final String tipoChave;
    private final String bancoDestino;
    private final String endToEndId;
    private final String pagadorNome;

    @JsonCreator
    public RealizacaoPixVO(@JsonProperty("idTransacaoPix") String idTransacaoPix,
                           @JsonProperty("idEmpresa") String idEmpresa,
                           @JsonProperty("valor") BigDecimal valor,
                           @JsonProperty("chavePix") String chavePix,
                           @JsonProperty("tipoChave") String tipoChave,
                           @JsonProperty("bancoDestino") String bancoDestino,
                           @JsonProperty("endToEndId") String endToEndId,
                           @JsonProperty("pagadorNome") String pagadorNome) {
        this.idTransacaoPix = idTransacaoPix;
        this.idEmpresa = idEmpresa;
        this.valor = valor;
        this.chavePix = chavePix;
        this.tipoChave = tipoChave;
        this.bancoDestino = bancoDestino;
        this.endToEndId = endToEndId;
        this.pagadorNome = pagadorNome;
    }

    public String getIdTransacaoPix() {
        return idTransacaoPix;
    }

    public String getIdEmpresa() {
        return idEmpresa;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public String getChavePix() {
        return chavePix;
    }

    public String getTipoChave() {
        return tipoChave;
    }

    public String getBancoDestino() {
        return bancoDestino;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public String getPagadorNome() {
        return pagadorNome;
    }
}

