package br.pucminas.aed.tarifacao;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

import br.pucminas.aed.tarifacao.service.TarifacaoRepository;

/**
 * Prova automatizada da idempotencia do consumidor.
 *
 * Roda com Kafka embutido e H2: SEM Docker e SEM o servico-pix. Testar o
 * consumidor sem subir o produtor e justamente o que a separacao em duas
 * aplicacoes independentes permite — os dois lados so se conhecem pelo topico.
 *
 * As mensagens sao publicadas como JSON CRU, e nao como objeto Java. Assim o
 * teste enxerga exatamente o que o broker enxerga, e o contrato exercitado e o
 * do fio — como seria com um produtor escrito em outra linguagem.
 *
 * TUDO ACONTECE NA MESMA COMPETENCIA (2026-08), fixada no ocorridoEm de cada
 * evento. Nao ha Instant.now() em lugar nenhum: um teste que dependesse do
 * relogio passaria a falhar na virada de mes.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = "pagamentos.pix.realizado.v1")
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:tarifacao;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "logging.level.br.pucminas.aed=INFO"
})
class IdempotenciaTest {

    static final String TOPICO = "pagamentos.pix.realizado.v1";

    /** Instante fixo: define a competencia 2026-08 sem depender do relogio. */
    private static final String OCORRIDO_EM = "2026-08-14T13:00:00.000Z";
    private static final String COMPETENCIA = "2026-08";

    private static final String CLIENTE_FRANQUIA_5 = "cli-0001";   // 5 gratis, R$ 1,90
    private static final String CLIENTE_FRANQUIA_2 = "cli-0002";   // 2 gratis, R$ 3,50
    private static final String CLIENTE_FRANQUIA_0 = "cli-0003";   // 0 gratis, R$ 0,99

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final Duration JANELA_DE_OBSERVACAO = Duration.ofSeconds(3);

    @Autowired
    private TarifacaoRepository repositorio;

    @Value("${spring.embedded.kafka.brokers}")
    private String servidores;

    private KafkaTemplate<String, String> publicador;

    @BeforeEach
    void prepararEstado() {
        repositorio.limparTarifas();
        repositorio.limparEventosProcessados();

        Map<String, Object> config = new HashMap<String, Object>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        ProducerFactory<String, String> fabrica = new DefaultKafkaProducerFactory<String, String>(config);
        this.publicador = new KafkaTemplate<String, String>(fabrica);
    }

    @Test
    @DisplayName("1 - Pix dentro da franquia e registrado como isento")
    void pixDentroDaFranquiaSaiIsento() {
        publicar(UUID.randomUUID().toString(), "pix-001", CLIENTE_FRANQUIA_5);

        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_5, 1);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_FRANQUIA_5, COMPETENCIA))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("2 - o MESMO evento entregue tres vezes tarifa uma so vez")
    void reentregaNaoDuplicaOEfeito() {
        String eventoId = UUID.randomUUID().toString();

        publicar(eventoId, "pix-002", CLIENTE_FRANQUIA_2);
        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_2, 1);

        publicar(eventoId, "pix-002", CLIENTE_FRANQUIA_2);   // reentrega: mesmo eventoId
        publicar(eventoId, "pix-002", CLIENTE_FRANQUIA_2);   // e mais uma

        confirmarQueNaoMuda(CLIENTE_FRANQUIA_2, 1);
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(1L);
    }

    @Test
    @DisplayName("3 - o Pix seguinte ao fim da franquia e tarifado")
    void pixAcimaDaFranquiaEhTarifado() {
        // franquia de 2: os dois primeiros saem por 0,00 e o terceiro custa 3,50
        publicar(UUID.randomUUID().toString(), "pix-101", CLIENTE_FRANQUIA_2);
        publicar(UUID.randomUUID().toString(), "pix-102", CLIENTE_FRANQUIA_2);
        publicar(UUID.randomUUID().toString(), "pix-103", CLIENTE_FRANQUIA_2);

        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_2, 3);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_FRANQUIA_2, COMPETENCIA))
                .isEqualByComparingTo("3.50");
    }

    @Test
    @DisplayName("4 - campos que o consumidor nao declara sao ignorados")
    void consumidorTolerante() {
        // O JSON publicado carrega chavePix, tipoChave, bancoDestino, endToEndId e
        // pagadorNome — nenhum deles existe em PixRealizadoEvent. Aqui vai ainda um
        // campo que nem o contrato de hoje preve, simulando o produtor evoluindo
        // primeiro. Se o consumidor nao fosse tolerante, a desserializacao falharia
        // e nenhuma tarifa seria registrada.
        String eventoId = UUID.randomUUID().toString();
        String json = montarJson(eventoId, "pix-201", CLIENTE_FRANQUIA_0,
                ",\"campoQueAindaNaoExiste\":\"valor futuro\"");
        enviar(json, eventoId, CLIENTE_FRANQUIA_0);

        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_0, 1);
        // franquia zero: o primeiro Pix ja e tarifado
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_FRANQUIA_0, COMPETENCIA))
                .isEqualByComparingTo("0.99");
    }

    // ------------------------------------------------------------------
    // apoio
    // ------------------------------------------------------------------

    private void publicar(String eventoId, String pixId, String clienteId) {
        enviar(montarJson(eventoId, pixId, clienteId, ""), eventoId, clienteId);
    }

    /**
     * O corpo do evento. Os cinco campos depois de valor sao os que o
     * consumidor NAO declara, e estao aqui de proposito.
     */
    private String montarJson(String eventoId, String pixId, String clienteId, String extra) {
        return "{"
                + "\"eventoId\":\"" + eventoId + "\","
                + "\"ocorridoEm\":\"" + OCORRIDO_EM + "\","
                + "\"pixId\":\"" + pixId + "\","
                + "\"clienteId\":\"" + clienteId + "\","
                + "\"valor\":250.00,"
                + "\"chavePix\":\"fulano@exemplo.com\","
                + "\"tipoChave\":\"EMAIL\","
                + "\"bancoDestino\":\"999\","
                + "\"endToEndId\":\"E99900000202608141300abcdef0001\","
                + "\"pagadorNome\":\"Cliente Ficticio\""
                + extra
                + "}";
    }

    /**
     * Publica com o mesmo envelope binario do servico-pix: os quatro atributos
     * exigidos pelo CloudEvents 1.0, mais o time.
     *
     * A CHAVE DA MENSAGEM E O clienteId — nao o pixId nem o eventoId. E ela que
     * garante que todos os Pix de um cliente caiam na mesma particao e sejam
     * contados em serie. Com outra chave, o teste 3 falharia de forma
     * intermitente.
     */
    private void enviar(String json, String eventoId, String clienteId) {
        ProducerRecord<String, String> registro =
                new ProducerRecord<String, String>(TOPICO, clienteId, json);

        registro.headers().add("ce_specversion", "1.0".getBytes(UTF_8));
        registro.headers().add("ce_id", eventoId.getBytes(UTF_8));
        registro.headers().add("ce_source", "/pagamentos/servico-pix".getBytes(UTF_8));
        registro.headers().add("ce_type", TOPICO.getBytes(UTF_8));
        registro.headers().add("ce_time", OCORRIDO_EM.getBytes(UTF_8));

        publicador.send(registro);
        publicador.flush();
    }

    private void aguardarQuantidadeDeTarifas(String clienteId, long esperada) {
        Awaitility.await()
                .atMost(PRAZO)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(
                        repositorio.contarPixNaCompetencia(clienteId, COMPETENCIA))
                        .isEqualTo(esperada));
    }

    /** Segura uma janela de observacao para provar que nada mais foi aplicado. */
    private void confirmarQueNaoMuda(String clienteId, long esperada) {
        Awaitility.await()
                .during(JANELA_DE_OBSERVACAO)
                .atMost(JANELA_DE_OBSERVACAO.plusSeconds(5))
                .untilAsserted(() -> assertThat(
                        repositorio.contarPixNaCompetencia(clienteId, COMPETENCIA))
                        .isEqualTo(esperada));
    }
}
