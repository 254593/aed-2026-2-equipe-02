package br.pucminas.aed.pix.controller;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.http.ResponseEntity;

import br.pucminas.aed.pix.domain.PixRealizadoEvent;
import br.pucminas.aed.pix.domain.RealizacaoPixVO;
import br.pucminas.aed.pix.service.PixService;

class PixControllerTest {

    @Test
    void responde202QuandoPublicacaoEConfiadaAoKafka() {
        PixService pixService = mock(PixService.class);
        PixController controller = new PixController(pixService);
        RealizacaoPixVO realizacao = new RealizacaoPixVO(
                "pix-001", "cli-0001", new BigDecimal("150.00"),
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Cliente Ficticio");
        PixRealizadoEvent evento = new PixRealizadoEvent(
                "evt-001", Instant.parse("2026-08-14T13:00:00.000Z"),
                "pix-001", "cli-0001", new BigDecimal("150.00"),
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Cliente Ficticio");
        when(pixService.realizar(realizacao)).thenReturn(evento);

        ResponseEntity<PixRealizadoEvent> resposta = controller.realizar(realizacao);

        assertThat(resposta.getStatusCode().value()).isEqualTo(202);
        assertThat(resposta.getBody()).isSameAs(evento);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("responde 400 com corpo JSON quando entrada e invalida")
    void responde400QuandoEntradaEhInvalida() {
        PixService pixService = mock(PixService.class);
        PixController controller = new PixController(pixService);

        ResponseEntity<?> resposta = controller.tratarEntradaInvalida(
                new IllegalArgumentException("idTransacaoPix e obrigatorio"));

        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> corpo = (java.util.Map<String, String>) resposta.getBody();
        assertThat(corpo).containsKey("erro");
        assertThat(corpo.get("erro")).isEqualTo("idTransacaoPix e obrigatorio");
    }
}
