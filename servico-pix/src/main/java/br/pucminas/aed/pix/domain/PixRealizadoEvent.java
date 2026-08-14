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
    private final Instant ocorridoEm;
    private final String pixId;
    private final String clienteId;
    private final BigDecimal valor;
    private final String chavePix;
    private final String tipoChave;
    private final String bancoDestino;
    private final String endToEndId;
    private final String pagadorNome;

    @JsonCreator
    public PixRealizadoEvent(@JsonProperty("eventoId") String eventoId,
                             @JsonProperty("ocorridoEm") Instant ocorridoEm,
                             @JsonProperty("pixId") String pixId,
                             @JsonProperty("clienteId") String clienteId,
                             @JsonProperty("valor") BigDecimal valor,
                             @JsonProperty("chavePix") String chavePix,
                             @JsonProperty("tipoChave") String tipoChave,
                             @JsonProperty("bancoDestino") String bancoDestino,
                             @JsonProperty("endToEndId") String endToEndId,
                             @JsonProperty("pagadorNome") String pagadorNome) {
        this.eventoId = Objects.requireNonNull(eventoId, "eventoId e obrigatorio");
        this.ocorridoEm = Objects.requireNonNull(ocorridoEm, "ocorridoEm e obrigatorio");
        this.pixId = Objects.requireNonNull(pixId, "pixId e obrigatorio");
        this.clienteId = Objects.requireNonNull(clienteId, "clienteId e obrigatorio");
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

    public Instant getOcorridoEm() {
        return ocorridoEm;
    }

    public String getPixId() {
        return pixId;
    }

    public String getClienteId() {
        return clienteId;
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
                + ", pixId=" + pixId
                + ", clienteId=" + clienteId + "}";
    }
}

