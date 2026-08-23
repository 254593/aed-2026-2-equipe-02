package br.pucminas.aed.agregador.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A visao QUE ESTE SERVICO TEM do fato PixRealizado.
 *
 * CONSUMIDOR TOLERANTE, e de proposito. O servico-pix publica dez campos; aqui
 * declaramos os quatro que a agregacao usa. Os outros seis sao ignorados por
 * @JsonIgnoreProperties, e e isso que permite ao produtor acrescentar campos
 * sem quebrar este consumidor — exatamente a compatibilidade que o
 * docs/contrato.md promete. Cada campo declarado a mais seria uma dependencia
 * nossa sobre o formato alheio, sem nada em troca.
 *
 * Nao ha JAR compartilhado com o servico-pix nem com o servico-tarifacao: o
 * contrato entre os tres e o JSON no topico, e cada lado tem a sua classe.
 *
 * Imutabilidade explicita, e nao record: campos private final, sem setter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PixRealizadoEvent {

    private final String eventoId;

    /** EVENT TIME: o instante em que o Pix liquidou no SPI. Define a janela. */
    private final Instant liquidadoEm;

    private final String idTransacaoPix;
    private final BigDecimal valor;

    @JsonCreator
    public PixRealizadoEvent(@JsonProperty("eventoId") String eventoId,
                             @JsonProperty("liquidadoEm") Instant liquidadoEm,
                             @JsonProperty("idTransacaoPix") String idTransacaoPix,
                             @JsonProperty("valor") BigDecimal valor) {

        this.eventoId = Objects.requireNonNull(eventoId, "eventoId e obrigatorio");
        this.liquidadoEm = Objects.requireNonNull(liquidadoEm, "liquidadoEm e obrigatorio");
        this.idTransacaoPix = idTransacaoPix;
        this.valor = valor;
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

    public BigDecimal getValor() {
        return valor;
    }

    @Override
    public String toString() {
        return "PixRealizadoEvent{eventoId=" + eventoId
                + ", liquidadoEm=" + liquidadoEm
                + ", idTransacaoPix=" + idTransacaoPix
                + ", valor=" + valor + "}";
    }
}
