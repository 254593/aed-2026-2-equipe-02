package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * O que a politica de tarifacao decidiu sobre um Pix: a situacao e o valor.
 *
 * Os dois andam juntos e nao podem ser separados sem perder sentido — 0.00
 * sozinho nao diz se o Pix foi isento, se a empresa nao tinha contrato ou se o
 * teto do mes ja tinha sido atingido. Devolver um par nomeado, em vez de um
 * BigDecimal solto, e o que permite ao TarifacaoService gravar a linha completa
 * sem redescobrir o motivo.
 *
 * As fabricas estaticas existem para que cada saida da politica seja
 * inconfundivel na chamada, e para impedir combinacao invalida: nao ha como
 * construir um SEM_CONTRATO que cobre.
 */
public final class DecisaoDeTarifacaoVO {

    /** Escala 2 para casar com a coluna NUMERIC(10,2) e sair "0.00" no log. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final SituacaoDaTarifaVO situacao;
    private final BigDecimal valor;

    private DecisaoDeTarifacaoVO(SituacaoDaTarifaVO situacao, BigDecimal valor) {
        this.situacao = Objects.requireNonNull(situacao, "situacao e obrigatoria");
        this.valor = Objects.requireNonNull(valor, "valor e obrigatorio");
    }

    /** Nao ha oferta vigente na competencia do Pix: nao se cobra. */
    public static DecisaoDeTarifacaoVO semContrato() {
        return new DecisaoDeTarifacaoVO(SituacaoDaTarifaVO.SEM_CONTRATO, ZERO);
    }

    /** O Pix coube na franquia mensal do plano. */
    public static DecisaoDeTarifacaoVO isentoPorFranquia() {
        return new DecisaoDeTarifacaoVO(SituacaoDaTarifaVO.ISENTO_FRANQUIA, ZERO);
    }

    /** Acima da franquia: cobrado pela faixa de valor. */
    public static DecisaoDeTarifacaoVO tarifado(BigDecimal valor) {
        return new DecisaoDeTarifacaoVO(SituacaoDaTarifaVO.TARIFADO, valor);
    }

    /** O total cobrado na competencia ja atingiu o teto do plano. */
    public static DecisaoDeTarifacaoVO tetoAtingido() {
        return new DecisaoDeTarifacaoVO(SituacaoDaTarifaVO.TETO_ATINGIDO, ZERO);
    }

    public SituacaoDaTarifaVO getSituacao() {
        return situacao;
    }

    public BigDecimal getValor() {
        return valor;
    }

    @Override
    public String toString() {
        return "DecisaoDeTarifacaoVO{" + situacao + ", valor=" + valor + "}";
    }
}
