package br.pucminas.aed.pix.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Comando operacional para publicar o fato de estorno. */
public final class EstornoPixVO {

    private final String eventoId;
    private final String eventoOriginalId;
    private final String idTransacaoPix;
    private final String idEmpresa;
    private final BigDecimal valor;
    private final String motivo;

    @JsonCreator
    public EstornoPixVO(@JsonProperty("eventoId") String eventoId,
                        @JsonProperty("eventoOriginalId") String eventoOriginalId,
                        @JsonProperty("idTransacaoPix") String idTransacaoPix,
                        @JsonProperty("idEmpresa") String idEmpresa,
                        @JsonProperty("valor") BigDecimal valor,
                        @JsonProperty("motivo") String motivo) {
        this.eventoId = eventoId;
        this.eventoOriginalId = eventoOriginalId;
        this.idTransacaoPix = idTransacaoPix;
        this.idEmpresa = idEmpresa;
        this.valor = valor;
        this.motivo = motivo;
    }

    public String getEventoId() { return eventoId; }
    public String getEventoOriginalId() { return eventoOriginalId; }
    public String getIdTransacaoPix() { return idTransacaoPix; }
    public String getIdEmpresa() { return idEmpresa; }
    public BigDecimal getValor() { return valor; }
    public String getMotivo() { return motivo; }
}
