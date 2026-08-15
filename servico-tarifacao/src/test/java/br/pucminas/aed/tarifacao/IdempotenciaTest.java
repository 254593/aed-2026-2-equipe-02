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

import br.pucminas.aed.tarifacao.domain.SituacaoDaTarifaVO;
import br.pucminas.aed.tarifacao.service.TarifacaoRepository;

/**
 * Prova automatizada da idempotencia do consumidor e das quatro saidas da
 * politica de tarifacao do ADR-002.
 *
 * Roda com Kafka embutido e H2: SEM Docker e SEM o servico-pix. Testar o
 * consumidor sem subir o produtor e justamente o que a separacao em duas
 * aplicacoes independentes permite — os dois lados so se conhecem pelo topico.
 *
 * As mensagens sao publicadas como JSON CRU, e nao como objeto Java. Assim o
 * teste enxerga exatamente o que o broker enxerga, e o contrato exercitado e o
 * do fio — como seria com um produtor escrito em outra linguagem.
 *
 * NENHUM Instant.now() EM LUGAR NENHUM. Cada evento carrega um ocorridoEm fixo,
 * e e dele que sai a competencia. Um teste que dependesse do relogio passaria a
 * falhar na virada de mes — e, pior, esconderia justamente o bug que os testes
 * 10 e 11 existem para pegar.
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

    /** Competencia seguinte — isolamento por mes. */
    private static final String OCORRIDO_EM_SETEMBRO = "2026-09-01T08:00:00.000Z";
    private static final String COMPETENCIA_SETEMBRO = "2026-09";

    /** Competencia anterior — vigencia de contrato e troca de plano. */
    private static final String OCORRIDO_EM_JULHO = "2026-07-15T10:00:00.000Z";
    private static final String COMPETENCIA_JULHO = "2026-07";

    /** Valor padrao do Pix nos testes que nao estao exercitando faixa. */
    private static final String VALOR_PADRAO = "250.00";

    private static final String CLIENTE_FRANQUIA_5 = "cli-0001";  // 5 gratis, faixas 1,90/3,50/7,00
    private static final String CLIENTE_FRANQUIA_2 = "cli-0002";  // 2 gratis, faixa unica 3,50
    private static final String CLIENTE_FRANQUIA_0 = "cli-0003";  // 0 gratis, faixa unica 0,99
    private static final String CLIENTE_TROCA_PLANO = "cli-0004"; // 2 gratis ate 07; 10 a partir de 08
    private static final String CLIENTE_CONTRATO_ENCERRADO = "cli-0005"; // vigencia so ate 2026-07
    private static final String CLIENTE_COM_TETO = "cli-0006";    // 0 gratis, 0,99, teto 1,98

    /** Cliente sem linha nenhuma em `oferta`: nunca teve contrato. */
    private static final String CLIENTE_SEM_CONTRATO = "cli-9999";

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

    // ------------------------------------------------------------------
    // idempotencia — o que a aula 02 cobra
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1 - Pix dentro da franquia e registrado como isento")
    void pixDentroDaFranquiaSaiIsento() {
        publicar(UUID.randomUUID().toString(), "pix-001", CLIENTE_FRANQUIA_5);

        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_5, 1);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_FRANQUIA_5, COMPETENCIA))
                .isEqualByComparingTo("0.00");
        assertThat(situacoes(CLIENTE_FRANQUIA_5, COMPETENCIA, SituacaoDaTarifaVO.ISENTO_FRANQUIA))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("2 - o MESMO evento entregue tres vezes produz efeito UMA vez")
    void reentregaNaoDuplicaOEfeito() {
        String eventoId = UUID.randomUUID().toString();

        publicar(eventoId, "pix-002", CLIENTE_FRANQUIA_2);
        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_2, 1);

        publicar(eventoId, "pix-002", CLIENTE_FRANQUIA_2); // reentrega: mesmo eventoId
        publicar(eventoId, "pix-002", CLIENTE_FRANQUIA_2); // e mais uma

        confirmarQueNaoMuda(CLIENTE_FRANQUIA_2, 1);
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(1L);
    }

    @Test
    @DisplayName("3 - o Pix seguinte ao fim da franquia e tarifado")
    void pixAcimaDaFranquiaETarifado() {
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
        // O JSON publicado tem chavePix, tipoChave, bancoDestino, endToEndId e
        // pagadorNome — nenhum deles existe na classe deste servico. Se o
        // consumidor nao fosse tolerante, a desserializacao falharia e nao
        // haveria linha nenhuma.
        publicar(UUID.randomUUID().toString(), "pix-201", CLIENTE_FRANQUIA_0);

        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_0, 1);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_FRANQUIA_0, COMPETENCIA))
                .isEqualByComparingTo("0.99");
    }

    @Test
    @DisplayName("5 - mensagem sem ce_id usa o eventoId do corpo e continua idempotente")
    void ceIdAusenteUsaFallbackDoCorpo() {
        String eventoId = UUID.randomUUID().toString();
        String json = montarJson(eventoId, "pix-301", CLIENTE_FRANQUIA_5, OCORRIDO_EM, VALOR_PADRAO);

        enviarSemCeId(json, CLIENTE_FRANQUIA_5);
        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_5, 1);

        enviarSemCeId(json, CLIENTE_FRANQUIA_5); // reentrega, mesmo eventoId no corpo
        confirmarQueNaoMuda(CLIENTE_FRANQUIA_5, 1);
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(1L);
    }

    @Test
    @DisplayName("6 - dois eventos com o mesmo pixId e eventoId distintos sao dois fatos")
    void deduplicacaoUsaEventoIdNaoPixId() {
        // O mesmo pixId pode aparecer em eventos diferentes (PixRealizado hoje,
        // PixDevolvido amanha). A chave de deduplicacao e o eventoId; se fosse o
        // pixId, o segundo seria descartado como reentrega do primeiro — erro
        // nomeado na secao 9 do enunciado.
        String pixIdCompartilhado = "pix-601";

        publicar(UUID.randomUUID().toString(), pixIdCompartilhado, CLIENTE_FRANQUIA_5);
        publicar(UUID.randomUUID().toString(), pixIdCompartilhado, CLIENTE_FRANQUIA_5);

        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_5, 2);
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // as quatro saidas da politica — ADR-002, secao Decisao
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7 - cliente que NUNCA teve contrato nao e cobrado")
    void clienteSemContratoNaoECobrado() {
        // O ADR-002 e explicito: nao ha tabela padrao de fallback, porque cobrar
        // sem contrato vigente e cobranca indevida (CDC, art. 42, par. unico).
        // O Pix vira linha — o fato aconteceu — mas com situacao SEM_CONTRATO e
        // valor zero, e sem consumir franquia nenhuma.
        for (int i = 1; i <= 6; i++) {
            publicar(UUID.randomUUID().toString(), "pix-70" + i, CLIENTE_SEM_CONTRATO);
        }

        aguardarQuantidadeDeTarifas(CLIENTE_SEM_CONTRATO, 6);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_SEM_CONTRATO, COMPETENCIA))
                .isEqualByComparingTo("0.00");
        assertThat(situacoes(CLIENTE_SEM_CONTRATO, COMPETENCIA, SituacaoDaTarifaVO.SEM_CONTRATO))
                .isEqualTo(6L);
        assertThat(repositorio.contarFranquiaConsumida(CLIENTE_SEM_CONTRATO, COMPETENCIA))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("8 - contrato encerrado: cobre em julho, nao cobra em agosto")
    void contratoEncerradoDeixaDeSerCobrado() {
        // cli-0005 tem vigencia de 2026-01 a 2026-07. O MESMO cliente, o mesmo
        // valor, competencias diferentes: em julho ha contrato e o Pix e isento
        // pela franquia; em agosto nao ha, e sai SEM_CONTRATO.
        publicarEm(UUID.randomUUID().toString(), "pix-801", CLIENTE_CONTRATO_ENCERRADO,
                OCORRIDO_EM_JULHO, VALOR_PADRAO);
        aguardarQuantidadeDeTarifas(CLIENTE_CONTRATO_ENCERRADO, 1, COMPETENCIA_JULHO);
        assertThat(situacoes(CLIENTE_CONTRATO_ENCERRADO, COMPETENCIA_JULHO,
                SituacaoDaTarifaVO.ISENTO_FRANQUIA)).isEqualTo(1L);

        publicar(UUID.randomUUID().toString(), "pix-802", CLIENTE_CONTRATO_ENCERRADO);
        aguardarQuantidadeDeTarifas(CLIENTE_CONTRATO_ENCERRADO, 1, COMPETENCIA);
        assertThat(situacoes(CLIENTE_CONTRATO_ENCERRADO, COMPETENCIA,
                SituacaoDaTarifaVO.SEM_CONTRATO)).isEqualTo(1L);
    }

    @Test
    @DisplayName("9 - troca de plano: vale a oferta vigente na competencia DO EVENTO")
    void ofertaEBuscadaPelaCompetenciaDoEvento() {
        // cli-0004 trocou de plano: 2 isencoes a R$ 4,90 ate 2026-07, e 10
        // isencoes a R$ 2,50 a partir de 2026-08. Um evento de JULHO
        // reprocessado hoje tem de reencontrar o plano antigo — e nao o vigente
        // agora. E o que torna o fechamento mensal reproduzivel.
        publicarEm(UUID.randomUUID().toString(), "pix-901", CLIENTE_TROCA_PLANO,
                OCORRIDO_EM_JULHO, VALOR_PADRAO);
        publicarEm(UUID.randomUUID().toString(), "pix-902", CLIENTE_TROCA_PLANO,
                OCORRIDO_EM_JULHO, VALOR_PADRAO);
        publicarEm(UUID.randomUUID().toString(), "pix-903", CLIENTE_TROCA_PLANO,
                OCORRIDO_EM_JULHO, VALOR_PADRAO);

        // franquia de 2 no plano antigo: o terceiro custa R$ 4,90, nao R$ 2,50
        aguardarQuantidadeDeTarifas(CLIENTE_TROCA_PLANO, 3, COMPETENCIA_JULHO);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_TROCA_PLANO, COMPETENCIA_JULHO))
                .isEqualByComparingTo("4.90");

        // ja em agosto vale o plano novo: franquia de 10, e o terceiro Pix e isento
        publicar(UUID.randomUUID().toString(), "pix-904", CLIENTE_TROCA_PLANO);
        publicar(UUID.randomUUID().toString(), "pix-905", CLIENTE_TROCA_PLANO);
        publicar(UUID.randomUUID().toString(), "pix-906", CLIENTE_TROCA_PLANO);

        aguardarQuantidadeDeTarifas(CLIENTE_TROCA_PLANO, 3, COMPETENCIA);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_TROCA_PLANO, COMPETENCIA))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("10 - acima da franquia, o VALOR do Pix escolhe a faixa da tarifa")
    void tarifaVemDaFaixaDoValor() {
        // cli-0001: ate R$ 500 custa 1,90; ate R$ 5.000 custa 3,50; acima, 7,00.
        for (int i = 1; i <= 5; i++) {
            publicar(UUID.randomUUID().toString(), "pix-a0" + i, CLIENTE_FRANQUIA_5);
        }
        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_5, 5);

        publicarComValor(UUID.randomUUID().toString(), "pix-a06", CLIENTE_FRANQUIA_5, "100.00");
        publicarComValor(UUID.randomUUID().toString(), "pix-a07", CLIENTE_FRANQUIA_5, "3000.00");
        publicarComValor(UUID.randomUUID().toString(), "pix-a08", CLIENTE_FRANQUIA_5, "90000.00");

        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_5, 8);
        // 1,90 + 3,50 + 7,00 — cada Pix pagou a faixa do proprio valor
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_FRANQUIA_5, COMPETENCIA))
                .isEqualByComparingTo("12.40");
    }

    @Test
    @DisplayName("11 - atingido o teto mensal, os Pix seguintes deixam de ser cobrados")
    void tetoMensalInterrompeACobranca() {
        // cli-0006: sem isencao, R$ 0,99 o Pix, teto de R$ 1,98 no mes. Os dois
        // primeiros sao cobrados e fecham o teto; o terceiro sai zerado.
        publicar(UUID.randomUUID().toString(), "pix-b01", CLIENTE_COM_TETO);
        publicar(UUID.randomUUID().toString(), "pix-b02", CLIENTE_COM_TETO);
        publicar(UUID.randomUUID().toString(), "pix-b03", CLIENTE_COM_TETO);

        aguardarQuantidadeDeTarifas(CLIENTE_COM_TETO, 3);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_COM_TETO, COMPETENCIA))
                .isEqualByComparingTo("1.98");
        assertThat(situacoes(CLIENTE_COM_TETO, COMPETENCIA, SituacaoDaTarifaVO.TARIFADO))
                .isEqualTo(2L);
        assertThat(situacoes(CLIENTE_COM_TETO, COMPETENCIA, SituacaoDaTarifaVO.TETO_ATINGIDO))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("12 - a competencia e isolada por mes: a franquia reinicia em setembro")
    void competenciaEIsoladaPorMes() {
        publicar(UUID.randomUUID().toString(), "pix-c01", CLIENTE_FRANQUIA_2);
        publicar(UUID.randomUUID().toString(), "pix-c02", CLIENTE_FRANQUIA_2);
        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_2, 2);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_FRANQUIA_2, COMPETENCIA))
                .isEqualByComparingTo("0.00");

        publicarEm(UUID.randomUUID().toString(), "pix-c03", CLIENTE_FRANQUIA_2,
                OCORRIDO_EM_SETEMBRO, VALOR_PADRAO);
        aguardarQuantidadeDeTarifas(CLIENTE_FRANQUIA_2, 1, COMPETENCIA_SETEMBRO);
        assertThat(repositorio.totalTarifadoNaCompetencia(CLIENTE_FRANQUIA_2, COMPETENCIA_SETEMBRO))
                .isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------
    // apoio
    // ------------------------------------------------------------------

    private void publicar(String eventoId, String pixId, String clienteId) {
        publicarEm(eventoId, pixId, clienteId, OCORRIDO_EM, VALOR_PADRAO);
    }

    private void publicarComValor(String eventoId, String pixId, String clienteId, String valor) {
        publicarEm(eventoId, pixId, clienteId, OCORRIDO_EM, valor);
    }

    private void publicarEm(String eventoId, String pixId, String clienteId,
            String ocorridoEm, String valor) {
        enviar(montarJson(eventoId, pixId, clienteId, ocorridoEm, valor), eventoId, clienteId,
                ocorridoEm);
    }

    /**
     * O corpo do evento. Os cinco campos depois de valor sao os que o consumidor
     * NAO declara, e estao aqui de proposito: e o consumidor tolerante do B.3.
     */
    private String montarJson(String eventoId, String pixId, String clienteId,
            String ocorridoEm, String valor) {
        return "{"
                + "\"eventoId\":\"" + eventoId + "\","
                + "\"ocorridoEm\":\"" + ocorridoEm + "\","
                + "\"pixId\":\"" + pixId + "\","
                + "\"clienteId\":\"" + clienteId + "\","
                + "\"valor\":" + valor + ","
                + "\"chavePix\":\"fulano@exemplo.com\","
                + "\"tipoChave\":\"EMAIL\","
                + "\"bancoDestino\":\"999\","
                + "\"endToEndId\":\"E99900000202608141300000000001\","
                + "\"pagadorNome\":\"Cliente Ficticio\""
                + "}";
    }

    /**
     * A chave da mensagem e o clienteId — a mesma que o servico-pix usa. Nao e
     * detalhe de teste: e ela que serializa os Pix de um cliente na mesma
     * particao, e sem isso a contagem de franquia e a do teto ficariam sujeitas
     * a corrida e o teste passaria a falhar de forma intermitente.
     */
    private void enviar(String json, String eventoId, String clienteId, String ocorridoEm) {
        ProducerRecord<String, String> registro =
                new ProducerRecord<String, String>(TOPICO, clienteId, json);

        registro.headers().add("ce_specversion", "1.0".getBytes(UTF_8));
        registro.headers().add("ce_id", eventoId.getBytes(UTF_8));
        registro.headers().add("ce_source", "/pagamentos/servico-pix".getBytes(UTF_8));
        registro.headers().add("ce_type", TOPICO.getBytes(UTF_8));
        registro.headers().add("ce_time", ocorridoEm.getBytes(UTF_8));

        publicador.send(registro);
        publicador.flush();
    }

    /**
     * Variante sem o cabecalho ce_id — simula um produtor que viole o contrato.
     * O listener deve cair no eventoId do corpo e registrar WARN.
     */
    private void enviarSemCeId(String json, String clienteId) {
        ProducerRecord<String, String> registro =
                new ProducerRecord<String, String>(TOPICO, clienteId, json);

        registro.headers().add("ce_specversion", "1.0".getBytes(UTF_8));
        registro.headers().add("ce_source", "/pagamentos/servico-pix".getBytes(UTF_8));
        registro.headers().add("ce_type", TOPICO.getBytes(UTF_8));
        registro.headers().add("ce_time", OCORRIDO_EM.getBytes(UTF_8));
        // ce_id deliberadamente ausente

        publicador.send(registro);
        publicador.flush();
    }

    private long situacoes(String clienteId, String competencia, SituacaoDaTarifaVO situacao) {
        return repositorio.contarPorSituacao(clienteId, competencia, situacao);
    }

    private void aguardarQuantidadeDeTarifas(String clienteId, long esperada) {
        aguardarQuantidadeDeTarifas(clienteId, esperada, COMPETENCIA);
    }

    private void aguardarQuantidadeDeTarifas(String clienteId, long esperada, String competencia) {
        Awaitility.await()
                .atMost(PRAZO)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(
                        repositorio.contarPixNaCompetencia(clienteId, competencia))
                        .isEqualTo(esperada));
    }

    private void confirmarQueNaoMuda(String clienteId, long esperada) {
        Awaitility.await()
                .during(JANELA_DE_OBSERVACAO)
                .atMost(JANELA_DE_OBSERVACAO.plusSeconds(5))
                .untilAsserted(() -> assertThat(
                        repositorio.contarPixNaCompetencia(clienteId, COMPETENCIA))
                        .isEqualTo(esperada));
    }
}
