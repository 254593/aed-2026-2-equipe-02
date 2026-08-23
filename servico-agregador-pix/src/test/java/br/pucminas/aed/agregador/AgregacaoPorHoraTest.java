package br.pucminas.aed.agregador;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import br.pucminas.aed.agregador.service.AgregadorService;

/**
 * Prova que o agregador responde "quanto foi liquidado por hora" por EVENT TIME.
 *
 * Publica JSON CRU, exatamente como o servico-pix publica: sem cabecalho de tipo
 * (o produtor usa setAddTypeInfo(false)). Se o teste publicasse objeto Java, ele
 * provaria que o agregador funciona com um produtor que nao existe — foi assim
 * que a primeira versao passou despercebida sem conseguir desserializar nada.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = "pagamentos.pix.realizado.v1")
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "logging.level.br.pucminas.aed=INFO"
})
class AgregacaoPorHoraTest {

    private static final String TOPICO = "pagamentos.pix.realizado.v1";

    private static final Instant DENTRO_DAS_14 = Instant.parse("2026-08-23T14:10:00Z");
    private static final Instant DENTRO_DAS_15 = Instant.parse("2026-08-23T15:05:00Z");

    private static final Duration PRAZO = Duration.ofSeconds(20);

    @Autowired
    private AgregadorService agregador;

    @Value("${spring.embedded.kafka.brokers}")
    private String servidores;

    private KafkaTemplate<String, String> publicador;

    @BeforeEach
    void prepararEstado() {
        agregador.limpar();

        Map<String, Object> config = new HashMap<String, Object>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        ProducerFactory<String, String> fabrica = new DefaultKafkaProducerFactory<String, String>(config);
        this.publicador = new KafkaTemplate<String, String>(fabrica);
    }

    @Test
    @DisplayName("1 - o agregador desserializa o JSON que o servico-pix publica")
    void desserializaOContratoDoFio() {
        publicar("pix-001", "emp-0001", "100.00", "2026-08-23T14:10:00.000Z");

        aguardarQuantidade(DENTRO_DAS_14, 1L);
        assertThat(agregador.da(DENTRO_DAS_14).getValorTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("2 - Pix da mesma hora somam na mesma janela")
    void somaPorJanela() {
        publicar("pix-101", "emp-0001", "100.00", "2026-08-23T14:10:00.000Z");
        publicar("pix-102", "emp-0001", "200.50", "2026-08-23T14:20:00.000Z");
        publicar("pix-103", "emp-0001", "300.00", "2026-08-23T14:59:59.000Z");

        aguardarQuantidade(DENTRO_DAS_14, 3L);
        assertThat(agregador.da(DENTRO_DAS_14).getValorTotal()).isEqualByComparingTo("600.50");
    }

    @Test
    @DisplayName("3 - uma reentrega com o mesmo eventoId nao altera a agregacao")
    void ignoraReentregaDoMesmoEvento() {
        publicar("pix-a", "emp-0001", "100.00", "2026-08-23T14:10:00.000Z");
        publicar("pix-b", "emp-0001", "150.00", "2026-08-23T14:20:00.000Z");
        publicar("pix-c", "emp-0001", "200.00", "2026-08-23T14:30:00.000Z");
        publicar("pix-b", "emp-0001", "150.00", "2026-08-23T14:20:00.000Z");

        aguardarQuantidade(DENTRO_DAS_14, 3L);
        assertThat(agregador.da(DENTRO_DAS_14).getValorTotal()).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("4 - horas diferentes ficam em janelas separadas")
    void janelasNaoSeMisturam() {
        publicar("pix-201", "emp-0001", "100.00", "2026-08-23T14:10:00.000Z");
        publicar("pix-202", "emp-0001", "50.00", "2026-08-23T15:05:00.000Z");

        aguardarQuantidade(DENTRO_DAS_14, 1L);
        aguardarQuantidade(DENTRO_DAS_15, 1L);
        assertThat(agregador.da(DENTRO_DAS_14).getValorTotal()).isEqualByComparingTo("100.00");
        assertThat(agregador.da(DENTRO_DAS_15).getValorTotal()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("5 - o RETARDATARIO soma na janela dele, sem reiniciar a contagem")
    void retardatarioSomaNaJanelaCorreta() {
        // Este e o teste que a versao anterior nao passava: com uma janela
        // corrente unica, o evento de 14:30 chegando depois do de 15:05
        // descartava os 300 ja acumulados e recomecava do zero.
        publicar("pix-a", "emp-0001", "100.00", "2026-08-23T14:10:00.000Z");
        publicar("pix-b", "emp-0001", "200.00", "2026-08-23T14:20:00.000Z");
        publicar("pix-c", "emp-0001", "50.00", "2026-08-23T15:05:00.000Z");
        publicar("pix-d", "emp-0001", "400.00", "2026-08-23T14:30:00.000Z");

        aguardarQuantidade(DENTRO_DAS_14, 3L);

        assertThat(agregador.da(DENTRO_DAS_14).getValorTotal())
                .as("100 + 200 + 400 do retardatario")
                .isEqualByComparingTo("700.00");
        assertThat(agregador.da(DENTRO_DAS_15).getValorTotal()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("6 - campos que o agregador nao declara sao ignorados")
    void consumidorTolerante() {
        // O JSON tem seis campos que esta classe nao declara. Se o consumidor
        // nao fosse tolerante, a desserializacao falharia e nada seria agregado
        // — e a compatibilidade que o docs/contrato.md promete seria falsa.
        publicar("pix-301", "emp-0001", "42.00", "2026-08-23T14:40:00.000Z");

        aguardarQuantidade(DENTRO_DAS_14, 1L);
        assertThat(agregador.da(DENTRO_DAS_14).getValorTotal()).isEqualByComparingTo("42.00");
    }

    @Test
    @DisplayName("7 - a agregacao usa o liquidadoEm do evento, nao a hora de chegada")
    void usaEventTimeNaoProcessingTime() {
        // Todos chegam AGORA, mas foram liquidados em horas distintas do passado.
        // Com processing time os tres cairiam na mesma janela: a de agora.
        publicar("pix-401", "emp-0001", "10.00", "2026-08-23T09:30:00.000Z");
        publicar("pix-402", "emp-0001", "20.00", "2026-08-23T14:30:00.000Z");
        publicar("pix-403", "emp-0001", "30.00", "2026-08-23T15:30:00.000Z");

        aguardarQuantidade(Instant.parse("2026-08-23T09:00:00Z"), 1L);
        aguardarQuantidade(DENTRO_DAS_14, 1L);
        aguardarQuantidade(DENTRO_DAS_15, 1L);

        assertThat(agregador.panorama())
                .as("tres janelas distintas, uma por hora de liquidacao")
                .hasSize(3);
    }

    private void aguardarQuantidade(Instant naJanelaDe, long esperada) {
        Awaitility.await().atMost(PRAZO).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(agregador.da(naJanelaDe).getQuantidade())
                        .isEqualTo(esperada));
    }

    /** Publica JSON cru com os dez campos do contrato e os cinco cabecalhos ce_*. */
    private void publicar(String idTransacaoPix, String idEmpresa, String valor, String liquidadoEm) {
        String json = "{"
                + "\"eventoId\":\"evt-" + idTransacaoPix + "\","
                + "\"liquidadoEm\":\"" + liquidadoEm + "\","
                + "\"idTransacaoPix\":\"" + idTransacaoPix + "\","
                + "\"idEmpresa\":\"" + idEmpresa + "\","
                + "\"valor\":" + valor + ","
                + "\"chavePix\":\"fulano@exemplo.com\","
                + "\"tipoChave\":\"EMAIL\","
                + "\"bancoDestino\":\"999\","
                + "\"endToEndId\":\"E99900000202608231400000000001\","
                + "\"pagadorNome\":\"Empresa Ficticia\""
                + "}";

        ProducerRecord<String, String> registro =
                new ProducerRecord<String, String>(TOPICO, idEmpresa, json);
        registro.headers().add("ce_specversion", "1.0".getBytes(UTF_8));
        registro.headers().add("ce_id", ("evt-" + idTransacaoPix).getBytes(UTF_8));
        registro.headers().add("ce_source", "/pagamentos/servico-pix".getBytes(UTF_8));
        registro.headers().add("ce_type", TOPICO.getBytes(UTF_8));
        registro.headers().add("ce_time", liquidadoEm.getBytes(UTF_8));

        publicador.send(registro);
        publicador.flush();
    }
}
