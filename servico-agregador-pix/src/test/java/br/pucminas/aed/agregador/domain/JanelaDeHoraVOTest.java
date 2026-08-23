package br.pucminas.aed.agregador.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A janela e alinhada por tempo, e nao pela hora em que o processo subiu. */
class JanelaDeHoraVOTest {

    @Test
    @DisplayName("1 - a janela comeca na hora cheia, nao no instante do evento")
    void alinhaNaHoraCheia() {
        JanelaDeHoraVO janela = JanelaDeHoraVO.de(Instant.parse("2026-08-23T14:35:20.123Z"));

        assertThat(janela.getInicio()).isEqualTo(Instant.parse("2026-08-23T14:00:00Z"));
        assertThat(janela.getFim()).isEqualTo(Instant.parse("2026-08-23T15:00:00Z"));
    }

    @Test
    @DisplayName("2 - instantes diferentes da mesma hora caem na MESMA janela")
    void mesmaHoraMesmaJanela() {
        JanelaDeHoraVO a = JanelaDeHoraVO.de(Instant.parse("2026-08-23T14:00:00Z"));
        JanelaDeHoraVO b = JanelaDeHoraVO.de(Instant.parse("2026-08-23T14:59:59.999Z"));

        assertThat(a).isEqualTo(b);
        assertThat(a.getInicio()).isEqualTo(b.getInicio());
    }

    @Test
    @DisplayName("3 - o fim e EXCLUSIVO: 15:00:00 pertence a janela das 15h")
    void fimExclusivo() {
        JanelaDeHoraVO janelaDas14 = JanelaDeHoraVO.de(Instant.parse("2026-08-23T14:30:00Z"));
        Instant viradaDaHora = Instant.parse("2026-08-23T15:00:00Z");

        // se o fim fosse inclusivo, a virada contaria em duas janelas
        assertThat(janelaDas14.contem(viradaDaHora)).isFalse();
        assertThat(JanelaDeHoraVO.de(viradaDaHora).getInicio()).isEqualTo(viradaDaHora);
    }

    @Test
    @DisplayName("4 - a janela e a mesma calculada mil vezes: e o que torna o replay reproduzivel")
    void calculoEDeterministico() {
        Instant instante = Instant.parse("2026-08-23T14:35:20.123Z");
        JanelaDeHoraVO primeira = JanelaDeHoraVO.de(instante);

        for (int i = 0; i < 1000; i++) {
            assertThat(JanelaDeHoraVO.de(instante)).isEqualTo(primeira);
        }
    }
}
