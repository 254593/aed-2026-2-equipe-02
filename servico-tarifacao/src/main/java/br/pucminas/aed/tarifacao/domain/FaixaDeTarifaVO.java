package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Uma faixa de tarifa do plano: ate tanto de valor transferido, custa tanto.
 *
 * O ADR-002 declara que o Pix acima da franquia e "tarifado por faixa de
 * valor", e nao por um preco unico. E a forma usual da tarifa bancaria: uma
 * transferencia de R$ 80 e uma de R$ 80.000 custam diferente porque carregam
 * risco e custo operacional diferentes.
 *
 * O limite e INCLUSIVO, e a ultima faixa tem limite nulo — "daqui para cima".
 * Objeto de valor: sem identidade, imutavel, so faz sentido dentro da oferta
 * que o contem.
 */
public final class FaixaDeTarifaVO {

    /** Limite superior inclusivo. Nulo significa faixa sem teto, a ultima. */
    private final BigDecimal valorAte;
    private final BigDecimal valorTarifa;

    public FaixaDeTarifaVO(BigDecimal valorAte, BigDecimal valorTarifa) {
        this.valorAte = valorAte;
        this.valorTarifa = Objects.requireNonNull(valorTarifa, "valorTarifa e obrigatoria");
    }

    /**
     * Se um Pix deste valor cai nesta faixa.
     *
     * compareTo, e nao equals: BigDecimal considera 1.90 e 1.900 objetos
     * diferentes por causa da escala, e a comparacao por equals produziria
     * falso negativo conforme o banco devolvesse a coluna com uma escala ou
     * outra.
     */
    public boolean cobre(BigDecimal valorDoPix) {
        if (valorAte == null) {
            return true;
        }
        return valorDoPix.compareTo(valorAte) <= 0;
    }

    public BigDecimal getValorAte() {
        return valorAte;
    }

    public BigDecimal getValorTarifa() {
        return valorTarifa;
    }

    @Override
    public String toString() {
        String limite;
        if (valorAte == null) {
            limite = "acima";
        } else {
            limite = "ate " + valorAte;
        }
        return "FaixaDeTarifaVO{" + limite + " -> " + valorTarifa + "}";
    }
}
