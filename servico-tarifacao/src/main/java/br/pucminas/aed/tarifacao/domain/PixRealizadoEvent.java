package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A visao QUE O SERVICO DE TARIFACAO TEM do fato "Pix realizado".
 *
 * O servico-pix tem a classe dele; este tem a sua. As duas nao se conhecem, e e
 * de proposito: o contrato entre os dois servicos e o JSON no topico.
 *
 * CONSUMIDOR TOLERANTE — repare no que NAO esta declarado aqui. O evento
 * publicado carrega tambem chavePix, tipoChave, bancoDestino, endToEndId e
 * pagadorNome. Nenhum deles existe nesta classe, porque decidir se um Pix e
 * isento ou tarifado nao depende de nenhum deles. Campos desconhecidos sao
 * ignorados por @JsonIgnoreProperties, e e isso que permite ao servico-pix
 * acrescentar campos sem quebrar a tarifacao.
 *
 * Cada campo declarado vira uma dependencia sua sobre o formato alheio. Declare
 * apenas o que voce usa.
 *
 * IMUTABILIDADE EXPLICITA, e nao record: os campos sao private final, nao ha
 * setter e o unico caminho de construcao e o construtor anotado. O enunciado
 * pede que os mecanismos fiquem a vista.
 *
 * Esta classe nao importa nada de Kafka nem de Spring. Ela e dominio.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PixRealizadoEvent {

    /** Identidade do FATO. E a chave de deduplicacao, e viaja no cabecalho ce_id. */
    private final String eventoId;

    /** Quando o Pix aconteceu no dominio — nao quando o broker recebeu a mensagem. */
    private final Instant ocorridoEm;

    /** Identidade da TRANSACAO de negocio. Nao confundir com o eventoId. */
    private final String pixId;

    /** De quem e a franquia mensal que este Pix consome. */
    private final String clienteId;

    /** Valor transferido. Nao entra no calculo hoje, mas fica no registro da tarifa. */
    private final BigDecimal valor;

    @JsonCreator
    public PixRealizadoEvent(@JsonProperty("eventoId") String eventoId,
                             @JsonProperty("ocorridoEm") Instant ocorridoEm,
                             @JsonProperty("pixId") String pixId,
                             @JsonProperty("clienteId") String clienteId,
                             @JsonProperty("valor") BigDecimal valor) {

        this.eventoId = Objects.requireNonNull(eventoId, "eventoId e obrigatorio");
        this.ocorridoEm = Objects.requireNonNull(ocorridoEm, "ocorridoEm e obrigatorio");
        this.pixId = Objects.requireNonNull(pixId, "pixId e obrigatorio");
        this.clienteId = Objects.requireNonNull(clienteId, "clienteId e obrigatorio");
        this.valor = valor;
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

    @Override
    public String toString() {
        return "PixRealizadoEvent{eventoId=" + eventoId
                + ", pixId=" + pixId
                + ", clienteId=" + clienteId + "}";
    }
}
