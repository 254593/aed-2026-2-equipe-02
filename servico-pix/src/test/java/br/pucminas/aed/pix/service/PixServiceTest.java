package br.pucminas.aed.pix.service;

import java.math.BigDecimal;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import br.pucminas.aed.pix.domain.PixRealizadoEvent;
import br.pucminas.aed.pix.domain.RealizacaoPixVO;

@ExtendWith(MockitoExtension.class)
class PixServiceTest {

    private static final String TOPICO = "pagamentos.pix.realizado.v1";
    private static final String ORIGEM = "/pagamentos/servico-pix";

    @Mock
    private KafkaTemplate<String, Object> clienteDoBroker;

    @Mock
    private ResultadoPublicacaoListener resultadoPublicacaoListener;

    private PixService pixService;

    @BeforeEach
    void preparar() {
        Clock relogio = Clock.fixed(
                Instant.parse("2026-08-14T13:00:00.000Z"), ZoneOffset.UTC);
        pixService = new PixService(clienteDoBroker, resultadoPublicacaoListener,
                relogio, TOPICO, ORIGEM, TOPICO);
    }

    @Test
    void publicaContratoEsperadoPeloConsumidor() {
        CompletableFuture<SendResult<String, Object>> retorno = new CompletableFuture<>();
        when(clienteDoBroker.send(any(ProducerRecord.class))).thenReturn(retorno);

        RealizacaoPixVO realizacao = new RealizacaoPixVO(
                "pix-001", "cli-0001", new BigDecimal("150.00"),
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Cliente Ficticio");

        PixRealizadoEvent evento = pixService.realizar(realizacao);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(clienteDoBroker).send(captor.capture());
        verify(resultadoPublicacaoListener).acompanhar(evento.getEventoId(), retorno);

        ProducerRecord<String, Object> registro = captor.getValue();
        assertThat(registro.topic()).isEqualTo(TOPICO);
        assertThat(registro.key()).isEqualTo("cli-0001");
        assertThat(registro.value()).isSameAs(evento);
        assertThat(evento.getOcorridoEm()).isEqualTo("2026-08-14T13:00:00Z");
        assertThat(cabecalho(registro, "ce_specversion")).isEqualTo("1.0");
        assertThat(cabecalho(registro, "ce_id")).isEqualTo(evento.getEventoId());
        assertThat(cabecalho(registro, "ce_source")).isEqualTo(ORIGEM);
        assertThat(cabecalho(registro, "ce_type")).isEqualTo(TOPICO);
        assertThat(cabecalho(registro, "ce_time")).isEqualTo("2026-08-14T13:00:00Z");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("lanca excecao quando realizacao e nula")
    void lancaExcecaoQuandoRealizacaoENula() {
        assertThrows(IllegalArgumentException.class,
                () -> pixService.realizar(null));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("lanca excecao quando pixId e branco")
    void lancaExcecaoQuandoPixIdEmBranco() {
        RealizacaoPixVO realizacao = new RealizacaoPixVO(
                "", "cli-0001", new BigDecimal("150.00"),
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Cliente Ficticio");

        assertThrows(IllegalArgumentException.class,
                () -> pixService.realizar(realizacao));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("lanca excecao quando clienteId e branco")
    void lancaExcecaoQuandoClienteIdEmBranco() {
        RealizacaoPixVO realizacao = new RealizacaoPixVO(
                "pix-001", "", new BigDecimal("150.00"),
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Cliente Ficticio");

        assertThrows(IllegalArgumentException.class,
                () -> pixService.realizar(realizacao));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("lanca excecao quando valor e zero")
    void lancaExcecaoQuandoValorEZero() {
        RealizacaoPixVO realizacao = new RealizacaoPixVO(
                "pix-001", "cli-0001", BigDecimal.ZERO,
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Cliente Ficticio");

        assertThrows(IllegalArgumentException.class,
                () -> pixService.realizar(realizacao));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("lanca excecao quando valor e negativo")
    void lancaExcecaoQuandoValorENegativo() {
        RealizacaoPixVO realizacao = new RealizacaoPixVO(
                "pix-001", "cli-0001", new BigDecimal("-1.00"),
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Cliente Ficticio");

        assertThrows(IllegalArgumentException.class,
                () -> pixService.realizar(realizacao));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("eventoId e unico a cada chamada")
    void eventoIdUnicoACadaChamada() {
        when(clienteDoBroker.send(any(ProducerRecord.class)))
                .thenReturn(new CompletableFuture<>(), new CompletableFuture<>());

        RealizacaoPixVO realizacao = new RealizacaoPixVO(
                "pix-001", "cli-0001", new BigDecimal("150.00"),
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Cliente Ficticio");

        PixRealizadoEvent primeiro = pixService.realizar(realizacao);
        PixRealizadoEvent segundo = pixService.realizar(realizacao);

        assertThat(primeiro.getEventoId()).isNotEqualTo(segundo.getEventoId());
    }

    private String cabecalho(ProducerRecord<String, Object> registro, String nome) {
        Header cabecalho = registro.headers().lastHeader(nome);
        assertThat(cabecalho).isNotNull();
        return new String(cabecalho.value(), UTF_8);
    }
}
