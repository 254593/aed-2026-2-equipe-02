package br.pucminas.aed.tarifacao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EventoProcessadoExpurgadorTest {

    private static final Instant AGORA = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    void calculaLimiteDeTrintaDiasSemDependerDoRelogioDaMaquina() {
        TarifacaoRepository repositorio = Mockito.mock(TarifacaoRepository.class);
        Instant limiteEsperado = Instant.parse("2026-08-11T12:00:00Z");
        when(repositorio.expurgarEventosProcessadosAntesDe(limiteEsperado)).thenReturn(3);

        EventoProcessadoExpurgador expurgador = new EventoProcessadoExpurgador(
                repositorio,
                Duration.ofDays(30),
                Clock.fixed(AGORA, ZoneOffset.UTC));

        assertThat(expurgador.expurgarEm(AGORA)).isEqualTo(3);
        verify(repositorio).expurgarEventosProcessadosAntesDe(limiteEsperado);
    }

    @Test
    void oAgendamentoUsaORelogioInjetado() {
        TarifacaoRepository repositorio = Mockito.mock(TarifacaoRepository.class);
        EventoProcessadoExpurgador expurgador = new EventoProcessadoExpurgador(
                repositorio,
                Duration.ofDays(30),
                Clock.fixed(AGORA, ZoneOffset.UTC));

        expurgador.expurgar();

        verify(repositorio).expurgarEventosProcessadosAntesDe(
                Instant.parse("2026-08-11T12:00:00Z"));
    }

    @Test
    void recusaRetencaoNulaOuNegativa() {
        TarifacaoRepository repositorio = Mockito.mock(TarifacaoRepository.class);
        Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);

        assertThatIllegalArgumentException().isThrownBy(() ->
                new EventoProcessadoExpurgador(repositorio, Duration.ZERO, relogio));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EventoProcessadoExpurgador(repositorio, Duration.ofDays(-1), relogio));
    }
}
