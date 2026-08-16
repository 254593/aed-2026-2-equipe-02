package br.pucminas.aed.pix;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.pucminas.aed.pix.domain.PixRealizadoEvent;

class KafkaConfigTest {

    @Test
    void serializaDataEmIso8601ENaoEmEpoch() throws Exception {
        ObjectMapper objectMapper = new KafkaConfig().objectMapper();
        PixRealizadoEvent evento = new PixRealizadoEvent(
                "evt-001", Instant.parse("2026-08-14T13:00:00.000Z"),
                "pix-001", "emp-0001", new BigDecimal("150.00"),
                "fulano@exemplo.com", "EMAIL", "999",
                "E99900000202608141300000000001", "Empresa Ficticia");

        String json = objectMapper.writeValueAsString(evento);

        assertThat(json).contains("\"liquidadoEm\":\"2026-08-14T13:00:00Z\"");
        assertThat(json).doesNotContain("1786712400");
    }

    @Test
    void declaraTopicoComTresParticoes() {
        NewTopic topico = new KafkaConfig().topicoPixRealizado(
                "pagamentos.pix.realizado.v1");

        assertThat(topico.name()).isEqualTo("pagamentos.pix.realizado.v1");
        assertThat(topico.numPartitions()).isEqualTo(3);
    }
}

