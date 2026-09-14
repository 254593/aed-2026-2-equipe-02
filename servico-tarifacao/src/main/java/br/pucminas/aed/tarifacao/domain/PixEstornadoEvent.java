package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Visao do consumidor sobre o fato de compensacao. */
public final class PixEstornadoEvent {
    private final String eventoId;
    private final String eventoOriginalId;
    private final Instant estornadoEm;
    private final String idTransacaoPix;
    private final String idEmpresa;
    private final BigDecimal valor;
    private final String motivo;

    @JsonCreator
    public PixEstornadoEvent(@JsonProperty("eventoId") String eventoId,
                             @JsonProperty("eventoOriginalId") String eventoOriginalId,
                             @JsonProperty("estornadoEm") Instant estornadoEm,
                             @JsonProperty("idTransacaoPix") String idTransacaoPix,
                             @JsonProperty("idEmpresa") String idEmpresa,
                             @JsonProperty("valor") BigDecimal valor,
                             @JsonProperty("motivo") String motivo) {
        this.eventoId = Objects.requireNonNull(eventoId, "eventoId e obrigatorio");
        this.eventoOriginalId = Objects.requireNonNull(eventoOriginalId, "eventoOriginalId e obrigatorio");
        this.estornadoEm = Objects.requireNonNull(estornadoEm, "estornadoEm e obrigatorio");
        this.idTransacaoPix = Objects.requireNonNull(idTransacaoPix, "idTransacaoPix e obrigatorio");
        this.idEmpresa = Objects.requireNonNull(idEmpresa, "idEmpresa e obrigatorio");
        this.valor = Objects.requireNonNull(valor, "valor e obrigatorio");
        this.motivo = Objects.requireNonNull(motivo, "motivo e obrigatorio");
    }

    public String getEventoId() { return eventoId; }
    public String getEventoOriginalId() { return eventoOriginalId; }
    public Instant getEstornadoEm() { return estornadoEm; }
    public String getIdTransacaoPix() { return idTransacaoPix; }
    public String getIdEmpresa() { return idEmpresa; }
    public BigDecimal getValor() { return valor; }
    public String getMotivo() { return motivo; }
}
