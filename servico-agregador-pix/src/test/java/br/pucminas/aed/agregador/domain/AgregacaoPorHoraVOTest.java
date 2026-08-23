package br.pucminas.aed.agregador.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgregacaoPorHoraVOTest {

    private static final JanelaDeHoraVO JANELA =
            JanelaDeHoraVO.de(Instant.parse("2026-08-23T14:10:00Z"));

    @Test
    @DisplayName("1 - somar nao muta: devolve instancia nova")
    void somarEImutavel() {
        AgregacaoPorHoraVO vazia = AgregacaoPorHoraVO.vazia(JANELA);
        AgregacaoPorHoraVO comUm = vazia.somar(new BigDecimal("100.00"));

        assertThat(vazia.getQuantidade()).isZero();
        assertThat(vazia.getValorTotal()).isEqualByComparingTo("0.00");
        assertThat(comUm.getQuantidade()).isEqualTo(1L);
        assertThat(comUm.getValorTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("2 - valor nulo soma zero mas AINDA CONTA como Pix")
    void valorNuloContaNaQuantidade() {
        AgregacaoPorHoraVO agregacao = AgregacaoPorHoraVO.vazia(JANELA)
                .somar(new BigDecimal("100.00"))
                .somar(null);

        // o Pix aconteceu; descarta-lo faria a contagem divergir do numero real
        assertThat(agregacao.getQuantidade()).isEqualTo(2L);
        assertThat(agregacao.getValorTotal()).isEqualByComparingTo("100.00");
    }
}
