package br.pucminas.aed.agregador.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Quanto foi liquidado numa janela de uma hora: o total em reais e quantos Pix.
 *
 * IMUTAVEL. Somar produz uma instancia nova, e nao muta esta. E o que permite
 * guardar a agregacao num mapa concorrente sem lock: a substituicao do valor e
 * atomica, e ninguem enxerga um acumulado pela metade.
 *
 * Objeto de valor: sem identidade propria alem da janela que o define.
 */
public final class AgregacaoPorHoraVO {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final JanelaDeHoraVO janela;
    private final BigDecimal valorTotal;
    private final long quantidade;

    private AgregacaoPorHoraVO(JanelaDeHoraVO janela, BigDecimal valorTotal, long quantidade) {
        this.janela = Objects.requireNonNull(janela, "janela e obrigatoria");
        this.valorTotal = Objects.requireNonNull(valorTotal, "valorTotal e obrigatorio");
        this.quantidade = quantidade;
    }

    /** A janela ainda vazia — nenhum Pix contabilizado. */
    public static AgregacaoPorHoraVO vazia(JanelaDeHoraVO janela) {
        return new AgregacaoPorHoraVO(janela, ZERO, 0L);
    }

    /**
     * Soma um Pix a esta agregacao, devolvendo uma nova.
     *
     * Valor nulo conta como zero na soma, mas AINDA CONTA na quantidade: o Pix
     * aconteceu. Descarta-lo faria a contagem de transacoes divergir do numero
     * real de eventos, e a pergunta "quantos Pix por hora" passaria a mentir
     * por causa de um campo ausente.
     */
    public AgregacaoPorHoraVO somar(BigDecimal valor) {
        BigDecimal acrescimo = valor == null ? ZERO : valor;
        return new AgregacaoPorHoraVO(janela, valorTotal.add(acrescimo), quantidade + 1);
    }

    public JanelaDeHoraVO getJanela() {
        return janela;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public long getQuantidade() {
        return quantidade;
    }

    @Override
    public String toString() {
        return "janela " + janela + " | R$ " + valorTotal + " | " + quantidade + " Pix";
    }
}
