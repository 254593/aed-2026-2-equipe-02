package br.pucminas.aed.pix.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Fato publicado pelo servico-pix. A classe e imutavel de forma explicita:
 * campos private final, construtor unico e nenhum setter.
 */
public final class PixRealizadoEvent {

    private final String eventoId;
    private final Instant liquidadoEm;
    private final String idTransacaoPix;
    private final String idEmpresa;
    private final BigDecimal valor;
    private final String chavePix;
    private final String tipoChave;
    private final String bancoDestino;
    private final String endToEndId;
    private final String pagadorNome;

    @JsonCreator
    public PixRealizadoEvent(@JsonProperty("eventoId") String eventoId,
                             @JsonProperty("liquidadoEm") Instant liquidadoEm,
                             @JsonProperty("idTransacaoPix") String idTransacaoPix,
                             @JsonProperty("idEmpresa") String idEmpresa,
                             @JsonProperty("valor") BigDecimal valor,
                             @JsonProperty("chavePix") String chavePix,
                             @JsonProperty("tipoChave") String tipoChave,
                             @JsonProperty("bancoDestino") String bancoDestino,
                             @JsonProperty("endToEndId") String endToEndId,
                             @JsonProperty("pagadorNome") String pagadorNome) {
        this.eventoId = Objects.requireNonNull(eventoId, "eventoId e obrigatorio");
        this.liquidadoEm = Objects.requireNonNull(liquidadoEm, "liquidadoEm e obrigatorio");
        this.idTransacaoPix = Objects.requireNonNull(idTransacaoPix, "idTransacaoPix e obrigatorio");
        this.idEmpresa = Objects.requireNonNull(idEmpresa, "idEmpresa e obrigatorio");
        this.valor = valor;
        this.chavePix = chavePix;
        this.tipoChave = tipoChave;
        this.bancoDestino = bancoDestino;
        this.endToEndId = endToEndId;
        this.pagadorNome = pagadorNome;
    }

    public String getEventoId() {
        return eventoId;
    }

    public Instant getLiquidadoEm() {
        return liquidadoEm;
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

    @Override
    public String toString() {
        return "PixRealizadoEvent{eventoId=" + eventoId
                + ", idTransacaoPix=" + idTransacaoPix
                + ", idEmpresa=" + idEmpresa + "}";
    }
}

