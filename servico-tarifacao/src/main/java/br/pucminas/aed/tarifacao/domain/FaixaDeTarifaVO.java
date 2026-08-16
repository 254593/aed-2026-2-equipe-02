package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Uma faixa da tabela de tarifas: valor ABAIXO DE tanto custa tanto.
 *
 * FRONTEIRA EXCLUSIVA, E ISSO MUDA DINHEIRO. A especificacao da regra define
 * "limite inferior inclusivo, superior exclusivo", e a diferenca aparece
 * exatamente nos valores redondos, que sao os mais comuns numa transferencia:
 *
 *     Pix de R$ 500,00  com fronteira EXCLUSIVA -> faixa dos 500..1000 -> R$ 1,00
 *     Pix de R$ 500,00  com fronteira inclusiva -> faixa dos ate 500   -> R$ 0,50
 *
 * O campo se chama valorAbaixoDe, e nao valorAte, de proposito: "ate" se le
 * como inclusivo e foi assim que a primeira versao desta classe errou. O nome
 * carrega a semantica para que ninguem precise lembrar dela.
 *
 * A faixa nao guarda limite inferior: ele e o limite superior da faixa
 * anterior, e a ordem de avaliacao garante que nao ha intervalo descoberto nem
 * valor pertencente a duas faixas. A ultima faixa tem limite nulo — "daqui para
 * cima".
 *
 * Objeto de valor: sem identidade, imutavel, so faz sentido dentro da oferta
 * que o contem.
 */
public final class FaixaDeTarifaVO {

    /** Limite superior EXCLUSIVO. Nulo significa a ultima faixa, sem teto. */
    private final BigDecimal valorAbaixoDe;
    private final BigDecimal valorTarifa;

    public FaixaDeTarifaVO(BigDecimal valorAbaixoDe, BigDecimal valorTarifa) {
        this.valorAbaixoDe = valorAbaixoDe;
        this.valorTarifa = Objects.requireNonNull(valorTarifa, "valorTarifa e obrigatoria");
    }

    /**
     * Se um Pix deste valor cai nesta faixa.
     *
     * compareTo, e nao equals: BigDecimal considera 500 e 500.00 objetos
     * diferentes por causa da escala, e a comparacao por equals produziria
     * falso negativo conforme o banco devolvesse a coluna com uma escala ou
     * outra.
     */
    public boolean cobre(BigDecimal valorDoPix) {
        if (valorAbaixoDe == null) {
            return true;
        }
        return valorDoPix.compareTo(valorAbaixoDe) < 0;
    }

    public BigDecimal getValorAbaixoDe() {
        return valorAbaixoDe;
    }

    public BigDecimal getValorTarifa() {
        return valorTarifa;
    }

    @Override
    public String toString() {
        String limite;
        if (valorAbaixoDe == null) {
            limite = "daqui para cima";
        } else {
            limite = "abaixo de " + valorAbaixoDe;
        }
        return "FaixaDeTarifaVO{" + limite + " -> " + valorTarifa + "}";
    }
}
