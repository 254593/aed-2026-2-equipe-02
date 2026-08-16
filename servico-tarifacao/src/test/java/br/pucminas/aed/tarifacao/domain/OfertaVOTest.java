package br.pucminas.aed.tarifacao.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios de OfertaVO — a regra de negocio de tarifacao.
 *
 * Foco em tarifaPara(), que percorre as faixas e decide o preco, e em
 * temTetoMensal(), que condiciona a verificacao do teto no TarifacaoService.
 *
 * Sem Kafka, sem banco, sem Spring.
 */
class OfertaVOTest {

    /** Tabela de faixas do Plano PJ, exatamente como no schema.sql. */
    private static final List<FaixaDeTarifaVO> FAIXAS_PLANO_PJ = Arrays.asList(
            new FaixaDeTarifaVO(new BigDecimal("500.00"), new BigDecimal("0.50")),
            new FaixaDeTarifaVO(new BigDecimal("1000.00"), new BigDecimal("1.00")),
            new FaixaDeTarifaVO(new BigDecimal("5000.00"), new BigDecimal("5.00")),
            new FaixaDeTarifaVO(null, new BigDecimal("10.00")));

    private OfertaVO oferta(int franquia, BigDecimal teto, List<FaixaDeTarifaVO> faixas) {
        return new OfertaVO("emp-teste", "2026-01", franquia, teto, faixas);
    }

    // ------------------------------------------------------------------
    // tarifaPara — escolha de faixa
    // ------------------------------------------------------------------

    @Test
    @DisplayName("valor na primeira faixa retorna a tarifa correta")
    void tarifaParaPrimeiraFaixa() {
        OfertaVO o = oferta(0, null, FAIXAS_PLANO_PJ);

        assertThat(o.tarifaPara(new BigDecimal("100.00"))).isEqualByComparingTo("0.50");
        assertThat(o.tarifaPara(new BigDecimal("499.99"))).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("valor igual ao limite da primeira faixa cai na segunda — fronteira exclusiva")
    void tarifaParaFronteiraExclusiva() {
        OfertaVO o = oferta(0, null, FAIXAS_PLANO_PJ);

        // R$ 500,00 exato: nao pertence a "abaixo de 500", pertence a "500..1000"
        assertThat(o.tarifaPara(new BigDecimal("500.00"))).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("valor na ultima faixa (sem limite) usa a tarifa da ultima faixa")
    void tarifaParaUltimaFaixa() {
        OfertaVO o = oferta(0, null, FAIXAS_PLANO_PJ);

        assertThat(o.tarifaPara(new BigDecimal("5000.00"))).isEqualByComparingTo("10.00");
        assertThat(o.tarifaPara(new BigDecimal("999999.99"))).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("oferta sem faixas lancca IllegalStateException — contrato mal cadastrado")
    void tarifaParaSemFaixasLancaExcecao() {
        // Oferta sem faixas e contrato mal cadastrado, nao contrato gratuito.
        // Deixar passar como zero esconderia o erro dentro do extrato da empresa.
        OfertaVO o = oferta(0, null, Collections.emptyList());

        assertThatThrownBy(() -> o.tarifaPara(new BigDecimal("100.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nao tem faixa que cubra");
    }

    // ------------------------------------------------------------------
    // temTetoMensal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("teto nulo significa plano sem teto mensal")
    void semTetoMensal() {
        OfertaVO o = oferta(5, null, FAIXAS_PLANO_PJ);

        assertThat(o.temTetoMensal()).isFalse();
    }

    @Test
    @DisplayName("teto nao nulo significa plano com teto mensal")
    void comTetoMensal() {
        OfertaVO o = oferta(0, new BigDecimal("2000.00"), FAIXAS_PLANO_PJ);

        assertThat(o.temTetoMensal()).isTrue();
        assertThat(o.getTetoMensal()).isEqualByComparingTo("2000.00");
    }

    // ------------------------------------------------------------------
    // imutabilidade da lista de faixas
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a lista de faixas e imutavel — modificar a original nao afeta o VO")
    void listaImutavel() {
        List<FaixaDeTarifaVO> faixasOriginais = Arrays.asList(
                new FaixaDeTarifaVO(null, new BigDecimal("0.50")));
        OfertaVO o = oferta(0, null, faixasOriginais);

        List<FaixaDeTarifaVO> faixas = o.getFaixas();
        assertThatThrownBy(() -> faixas.add(new FaixaDeTarifaVO(null, BigDecimal.ONE)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
