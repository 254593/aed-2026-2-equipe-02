package br.pucminas.aed.pix.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Dados recebidos pela API para registrar que um Pix foi realizado. */
public final class RealizacaoPixVO {

    /**
     * CHAVE DE IDEMPOTENCIA DO COMANDO, opcional.
     *
     * Informado, e ele que vira a identidade do fato publicado (o ce_id do
     * envelope): repetir o mesmo POST produz o MESMO evento, e o consumidor o
     * descarta como reentrega. Ausente, o servico gera um UUID.
     *
     * Existe porque o POST e repetivel por fora: um timeout ou uma falha de
     * rede fazem o cliente tentar de novo, e sem a chave o servico publicaria
     * DOIS eventos distintos para o mesmo Pix — identidades diferentes, mesmo
     * idTransacaoPix. Como a deduplicacao do consumidor e pelo eventoId, os
     * dois passariam e a empresa seria cobrada duas vezes. E o mesmo papel do
     * cabecalho Idempotency-Key das APIs de pagamento.
     */
    private final String eventoId;

    private final String idTransacaoPix;
    private final String idEmpresa;
    private final BigDecimal valor;
    private final String chavePix;
    private final String tipoChave;
    private final String bancoDestino;
    private final String endToEndId;
    private final String pagadorNome;

    @JsonCreator
    public RealizacaoPixVO(@JsonProperty("eventoId") String eventoId,
                           @JsonProperty("idTransacaoPix") String idTransacaoPix,
                           @JsonProperty("idEmpresa") String idEmpresa,
                           @JsonProperty("valor") BigDecimal valor,
                           @JsonProperty("chavePix") String chavePix,
                           @JsonProperty("tipoChave") String tipoChave,
                           @JsonProperty("bancoDestino") String bancoDestino,
                           @JsonProperty("endToEndId") String endToEndId,
                           @JsonProperty("pagadorNome") String pagadorNome) {
        this.eventoId = eventoId;
        this.idTransacaoPix = idTransacaoPix;
        this.idEmpresa = idEmpresa;
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

