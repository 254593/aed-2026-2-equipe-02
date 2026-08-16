package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios de FaixaDeTarifaVO.
 *
 * A regra critica e: o limite superior e EXCLUSIVO. Um Pix de exatamente
 * R$ 500,00 nao pertence a faixa "abaixo de 500" — pertence a proxima.
 * E o tipo de erro que custa dinheiro e que a documentacao nomeia como
 * "foi assim que a primeira versao desta classe errou".
 *
 * Sem Kafka, sem banco, sem Spring: testes rapidos de objeto de dominio.
 */
class FaixaDeTarifaVOTest {

    @Test
    @DisplayName("valor estritamente abaixo do limite pertence a faixa")
    void cobreValorAbaixoDoLimite() {
        FaixaDeTarifaVO faixa = new FaixaDeTarifaVO(new BigDecimal("500.00"),
                new BigDecimal("0.50"));

        assertThat(faixa.cobre(new BigDecimal("499.99"))).isTrue();
        assertThat(faixa.cobre(new BigDecimal("1.00"))).isTrue();
    }

    @Test
    @DisplayName("valor IGUAL ao limite nao pertence a faixa — fronteira e EXCLUSIVA")
    void naoCobreValorIgualAoLimite() {
        // Este e o caso que "a primeira versao desta classe errou":
        // R$ 500,00 paga R$ 1,00 (faixa seguinte), e nao R$ 0,50.
        FaixaDeTarifaVO faixa = new FaixaDeTarifaVO(new BigDecimal("500.00"),
                new BigDecimal("0.50"));

        assertThat(faixa.cobre(new BigDecimal("500.00"))).isFalse();
    }

    @Test
    @DisplayName("valor acima do limite nao pertence a faixa")
    void naoCobreValorAcimaDoLimite() {
        FaixaDeTarifaVO faixa = new FaixaDeTarifaVO(new BigDecimal("500.00"),
                new BigDecimal("0.50"));

        assertThat(faixa.cobre(new BigDecimal("500.01"))).isFalse();
        assertThat(faixa.cobre(new BigDecimal("9999.99"))).isFalse();
    }

    @Test
    @DisplayName("ultima faixa (limite nulo) cobre qualquer valor")
    void ultimaFaixaSempreCobre() {
        // valorAbaixoDe == null significa "daqui para cima" — a ultima faixa.
        FaixaDeTarifaVO ultimaFaixa = new FaixaDeTarifaVO(null, new BigDecimal("10.00"));

        assertThat(ultimaFaixa.cobre(new BigDecimal("0.01"))).isTrue();
        assertThat(ultimaFaixa.cobre(new BigDecimal("500.00"))).isTrue();
        assertThat(ultimaFaixa.cobre(new BigDecimal("999999.99"))).isTrue();
    }

    @Test
    @DisplayName("comparacao usa compareTo, nao equals — escala do BigDecimal nao importa")
    void comparacaoIgnoraEscalaDoLimite() {
        // BigDecimal.equals distingue 500 de 500.00 pela escala; compareTo nao.
        // O banco pode devolver a coluna com escala diferente conforme o driver
        // — se a comparacao fosse por equals, teria falso negativo.
        FaixaDeTarifaVO faixa = new FaixaDeTarifaVO(new BigDecimal("500"),
                new BigDecimal("0.50"));

        // R$ 500,00 (escala 2) contra o limite 500 (escala 0): deve ser exclusivo
        assertThat(faixa.cobre(new BigDecimal("500.00"))).isFalse();
        assertThat(faixa.cobre(new BigDecimal("499.99"))).isTrue();
    }
}
