package br.pucminas.aed.agregador.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.pucminas.aed.agregador.domain.PixRealizadoEvent;

class AgregadorServiceTest {

    private static final Instant PRIMEIRA_JANELA = Instant.parse("2026-08-23T14:10:00Z");
    private static final Instant SEGUNDA_JANELA = Instant.parse("2026-08-23T15:05:00Z");

    private final AgregadorService agregador = new AgregadorService();

    @BeforeEach
    void prepararEstado() {
        agregador.limpar();
    }

    @Test
    @DisplayName("reentrega do mesmo eventoId nao duplica a janela")
    void reentregaNaoDuplica() {
        PixRealizadoEvent evento = evento("evt-dup", "pix-001", "emp-0001", "100.00", PRIMEIRA_JANELA);

        assertThat(agregador.registrar(evento)).isNotNull();
        assertThat(agregador.registrar(evento)).isNull();

        assertThat(agregador.da(PRIMEIRA_JANELA).getQuantidade()).isEqualTo(1L);
        assertThat(agregador.da(PRIMEIRA_JANELA).getValorTotal()).isEqualByComparingTo("100.00");
        assertThat(agregador.panorama()).hasSize(1);
    }

    @Test
    @DisplayName("a panoramica e ordenada por janela e a janela vazia retorna zero")
    void panoramaOrdenadoEJanelaVazia() {
        agregador.registrar(evento("evt-1", "pix-101", "emp-0001", "100.00", PRIMEIRA_JANELA));
        agregador.registrar(evento("evt-2", "pix-102", "emp-0001", "200.00", SEGUNDA_JANELA));

        Map<Instant, ?> panorama = agregador.panorama();
        assertThat(panorama).hasSize(2);
        assertThat(panorama.keySet()).containsExactly(
                Instant.parse("2026-08-23T14:00:00Z"),
                Instant.parse("2026-08-23T15:00:00Z"));
        assertThat(agregador.da(Instant.parse("2026-08-23T16:00:00Z")).getQuantidade()).isZero();
        assertThat(agregador.da(Instant.parse("2026-08-23T16:00:00Z")).getValorTotal())
                .isEqualByComparingTo("0.00");
    }

    private PixRealizadoEvent evento(String eventoId, String idTransacaoPix,
            String idEmpresa, String valor, Instant liquidadoEm) {
        return new PixRealizadoEvent(eventoId, liquidadoEm, idTransacaoPix, new BigDecimal(valor));
    }
}
