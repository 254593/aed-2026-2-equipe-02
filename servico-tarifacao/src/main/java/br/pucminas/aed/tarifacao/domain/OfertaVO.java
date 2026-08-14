package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * O plano contratado pelo cliente: quantos Pix ele tem isentos por mes e quanto
 * custa cada Pix a partir do primeiro excedente.
 *
 * E um objeto de valor: nao tem identidade propria, nao muda depois de criado e
 * so faz sentido junto do cliente que o contratou. Por isso VO, e nao Entity.
 *
 * Repare que ele guarda a REGRA, nao o resultado: quem decide se um Pix
 * especifico e isento e o TarifacaoService, comparando o consumo do mes com a
 * franquia daqui. O VO nao conhece o evento.
 */
public final class OfertaVO {

    private final String clienteId;
    private final int pixGratuitosMes;
    private final BigDecimal valorTarifa;

    public OfertaVO(String clienteId, int pixGratuitosMes, BigDecimal valorTarifa) {
        this.clienteId = Objects.requireNonNull(clienteId, "clienteId e obrigatorio");
        this.pixGratuitosMes = pixGratuitosMes;
        this.valorTarifa = Objects.requireNonNull(valorTarifa, "valorTarifa e obrigatoria");
    }

    public String getClienteId() {
        return clienteId;
    }

    public int getPixGratuitosMes() {
        return pixGratuitosMes;
    }

    public BigDecimal getValorTarifa() {
        return valorTarifa;
    }

    @Override
    public String toString() {
        return "OfertaVO{clienteId=" + clienteId
                + ", pixGratuitosMes=" + pixGratuitosMes
                + ", valorTarifa=" + valorTarifa + "}";
    }
}
