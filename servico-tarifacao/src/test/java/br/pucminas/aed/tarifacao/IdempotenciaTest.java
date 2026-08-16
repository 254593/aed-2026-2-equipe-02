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
 * Prova automatizada da idempotencia do consumidor e das cinco saidas da
 * politica de tarifacao.
 *
 * Roda com Kafka embutido e H2: SEM Docker e SEM o servico-pix. Testar o
 * consumidor sem subir o produtor e justamente o que a separacao em duas
 * aplicacoes independentes permite — os dois lados so se conhecem pelo topico.
 *
 * As mensagens sao publicadas como JSON CRU, e nao como objeto Java. Assim o
 * teste enxerga exatamente o que o broker enxerga, e o contrato exercitado e o
 * do fio — como seria com um produtor escrito em outra linguagem.
 *
 * NENHUM Instant.now() EM LUGAR NENHUM. Cada evento carrega um liquidadoEm fixo,
 * e e dele que sai a competencia. Um teste que dependesse do relogio passaria a
 * falhar na virada de mes e, pior, esconderia justamente o bug que os testes 8
 * e 9 existem para pegar.
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
    private static final String LIQUIDADO_EM = "2026-08-14T13:00:00.000Z";
    private static final String COMPETENCIA = "2026-08";

    /** Competencia seguinte — isolamento por mes. */
    private static final String LIQUIDADO_EM_SETEMBRO = "2026-09-01T08:00:00.000Z";
    private static final String COMPETENCIA_SETEMBRO = "2026-09";

    /** Competencia anterior — vigencia de contrato e troca de plano. */
    private static final String LIQUIDADO_EM_JULHO = "2026-07-15T10:00:00.000Z";
    private static final String COMPETENCIA_JULHO = "2026-07";

    /** Cai na primeira faixa do Plano PJ (abaixo de R$ 500) — tarifa R$ 0,50. */
    private static final String VALOR_PADRAO = "250.00";

    private static final String EMPRESA_PLANO_PJ = "emp-0001";  // 10 isentos, teto 2.000
    private static final String EMPRESA_FRANQUIA_2 = "emp-0002"; // 2 isentos, faixas do Plano PJ
    private static final String EMPRESA_FRANQUIA_0 = "emp-0003"; // 0 isentos, faixa unica 0,99
    private static final String EMPRESA_TROCA_PLANO = "emp-0004"; // 2/R$4,90 ate 07; 10/R$2,50 de 08
    private static final String EMPRESA_CONTRATO_ENCERRADO = "emp-0005"; // vigencia so ate 2026-07
    private static final String EMPRESA_COM_TETO = "emp-0006";   // 0 isentos, R$10,00, teto R$25,00

    /** Cliente sem linha nenhuma em `oferta`: nunca teve contrato. */
    private static final String EMPRESA_SEM_CONTRATO = "emp-9999";

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
        publicar(UUID.randomUUID().toString(), "pix-001", EMPRESA_PLANO_PJ);

        aguardarQuantidadeDeTarifas(EMPRESA_PLANO_PJ, 1);
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_PLANO_PJ, COMPETENCIA))
                .isEqualByComparingTo("0.00");
        assertThat(situacoes(EMPRESA_PLANO_PJ, COMPETENCIA, SituacaoDaTarifaVO.FRANQUIA))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("2 - o MESMO evento entregue tres vezes produz efeito UMA vez")
    void reentregaNaoDuplicaOEfeito() {
        String eventoId = UUID.randomUUID().toString();

        publicar(eventoId, "pix-002", EMPRESA_FRANQUIA_2);
        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 1);

        publicar(eventoId, "pix-002", EMPRESA_FRANQUIA_2); // reentrega: mesmo eventoId
        publicar(eventoId, "pix-002", EMPRESA_FRANQUIA_2); // e mais uma

        confirmarQueNaoMuda(EMPRESA_FRANQUIA_2, 1);
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(1L);
    }

    @Test
    @DisplayName("3 - o Pix seguinte ao fim da franquia e tarifado")
    void pixAcimaDaFranquiaETarifado() {
        publicar(UUID.randomUUID().toString(), "pix-101", EMPRESA_FRANQUIA_2);
        publicar(UUID.randomUUID().toString(), "pix-102", EMPRESA_FRANQUIA_2);
        publicar(UUID.randomUUID().toString(), "pix-103", EMPRESA_FRANQUIA_2);

        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 3);
        // dois isentos e um a R$ 0,50 — o Pix de R$ 250 cai na primeira faixa
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_FRANQUIA_2, COMPETENCIA))
                .isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("4 - campos que o consumidor nao declara sao ignorados")
    void consumidorTolerante() {
        // O JSON publicado tem chavePix, tipoChave, bancoDestino, endToEndId e
        // pagadorNome — nenhum deles existe na classe deste servico. Se o
        // consumidor nao fosse tolerante, a desserializacao falharia e nao
        // haveria linha nenhuma.
        publicar(UUID.randomUUID().toString(), "pix-201", EMPRESA_FRANQUIA_0);

        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_0, 1);
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_FRANQUIA_0, COMPETENCIA))
                .isEqualByComparingTo("0.99");
    }

    @Test
    @DisplayName("5 - mensagem sem ce_id usa o eventoId do corpo e continua idempotente")
    void ceIdAusenteUsaFallbackDoCorpo() {
        String eventoId = UUID.randomUUID().toString();
        String json = montarJson(eventoId, "pix-301", EMPRESA_PLANO_PJ, LIQUIDADO_EM, VALOR_PADRAO);

        enviarSemCeId(json, EMPRESA_PLANO_PJ);
        aguardarQuantidadeDeTarifas(EMPRESA_PLANO_PJ, 1);

        enviarSemCeId(json, EMPRESA_PLANO_PJ); // reentrega, mesmo eventoId no corpo
        confirmarQueNaoMuda(EMPRESA_PLANO_PJ, 1);
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(1L);
    }

    @Test
    @DisplayName("6 - dois eventos com o mesmo idTransacaoPix e eventoId distintos sao dois fatos")
    void deduplicacaoUsaEventoIdNaoIdTransacaoPix() {
        // O mesmo idTransacaoPix pode aparecer em eventos diferentes (PixRealizado hoje,
        // PixDevolvido amanha). A chave de deduplicacao e o eventoId; se fosse o
        // idTransacaoPix, o segundo seria descartado como reentrega do primeiro — erro
        // nomeado na secao 9 do enunciado.
        String idTransacaoPixCompartilhado = "pix-601";

        publicar(UUID.randomUUID().toString(), idTransacaoPixCompartilhado, EMPRESA_PLANO_PJ);
        publicar(UUID.randomUUID().toString(), idTransacaoPixCompartilhado, EMPRESA_PLANO_PJ);

        aguardarQuantidadeDeTarifas(EMPRESA_PLANO_PJ, 2);
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(2L);
    }

    @Test
    @DisplayName("7 - ce_id divergente do corpo nao trava a particao")
    void ceIdDivergenteDoCorpoNaoTravaAParticao() {
        // Um produtor fora do contrato pode publicar ce_id != eventoId do corpo.
        // A identidade que vale e a do envelope; se a tabela `tarifa` gravasse a
        // do corpo, duas mensagens com ce_id distintos e o mesmo eventoId no
        // corpo passariam pela deduplicacao e colidiriam na chave primaria da
        // tarifa — rollback, offset nao confirmado, e a particao inteira travada
        // em retentativa atras de uma mensagem envenenada.
        String eventoIdDoCorpo = UUID.randomUUID().toString();
        String json = montarJson(eventoIdDoCorpo, "pix-701", EMPRESA_PLANO_PJ, LIQUIDADO_EM,
                VALOR_PADRAO);

        enviar(json, UUID.randomUUID().toString(), EMPRESA_PLANO_PJ, LIQUIDADO_EM);
        enviar(json, UUID.randomUUID().toString(), EMPRESA_PLANO_PJ, LIQUIDADO_EM);

        // dois ce_id distintos: dois fatos, duas linhas, nenhuma colisao
        aguardarQuantidadeDeTarifas(EMPRESA_PLANO_PJ, 2);
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(2L);

        // e a particao continua viva: uma mensagem posterior e processada
        publicar(UUID.randomUUID().toString(), "pix-702", EMPRESA_PLANO_PJ);
        aguardarQuantidadeDeTarifas(EMPRESA_PLANO_PJ, 3);
    }

    // ------------------------------------------------------------------
    // vigencia da oferta — ADR-002
    // ------------------------------------------------------------------

    @Test
    @DisplayName("8 - empresa que NUNCA teve contrato nao e cobrada")
    void empresaSemContratoNaoECobrada() {
        // O ADR-002 e explicito: nao ha tabela padrao de fallback, porque cobrar
        // sem contrato vigente e cobranca indevida (CDC, art. 42, par. unico).
        // O Pix vira linha — o fato aconteceu — mas com situacao SEM_CONTRATO e
        // valor zero, e sem consumir franquia nenhuma.
        for (int i = 1; i <= 6; i++) {
            publicar(UUID.randomUUID().toString(), "pix-70" + i, EMPRESA_SEM_CONTRATO);
        }

        aguardarQuantidadeDeTarifas(EMPRESA_SEM_CONTRATO, 6);
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_SEM_CONTRATO, COMPETENCIA))
                .isEqualByComparingTo("0.00");
        assertThat(situacoes(EMPRESA_SEM_CONTRATO, COMPETENCIA, SituacaoDaTarifaVO.SEM_CONTRATO))
                .isEqualTo(6L);
        assertThat(repositorio.contarFranquiaConsumida(EMPRESA_SEM_CONTRATO, COMPETENCIA))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("9 - contrato encerrado: cobre em julho, nao cobra em agosto")
    void contratoEncerradoDeixaDeSerCobrado() {
        // emp-0005 tem vigencia de 2026-01 a 2026-07. O MESMO cliente, o mesmo
        // valor, competencias diferentes: em julho ha contrato e o Pix e isento
        // pela franquia; em agosto nao ha, e sai SEM_CONTRATO.
        publicarEm(UUID.randomUUID().toString(), "pix-801", EMPRESA_CONTRATO_ENCERRADO,
                LIQUIDADO_EM_JULHO, VALOR_PADRAO);
        aguardarQuantidadeDeTarifas(EMPRESA_CONTRATO_ENCERRADO, 1, COMPETENCIA_JULHO);
        assertThat(situacoes(EMPRESA_CONTRATO_ENCERRADO, COMPETENCIA_JULHO,
                SituacaoDaTarifaVO.FRANQUIA)).isEqualTo(1L);

        publicar(UUID.randomUUID().toString(), "pix-802", EMPRESA_CONTRATO_ENCERRADO);
        aguardarQuantidadeDeTarifas(EMPRESA_CONTRATO_ENCERRADO, 1, COMPETENCIA);
        assertThat(situacoes(EMPRESA_CONTRATO_ENCERRADO, COMPETENCIA,
                SituacaoDaTarifaVO.SEM_CONTRATO)).isEqualTo(1L);
    }

    @Test
    @DisplayName("10 - troca de plano: vale a oferta vigente na competencia DO EVENTO")
    void ofertaEBuscadaPelaCompetenciaDoEvento() {
        // emp-0004 trocou de plano: 2 isencoes a R$ 4,90 ate 2026-07, e 10
        // isencoes a R$ 2,50 a partir de 2026-08. Um evento de JULHO
        // reprocessado hoje tem de reencontrar o plano antigo — e nao o vigente
        // agora. E o que torna o fechamento mensal reproduzivel.
        publicarEm(UUID.randomUUID().toString(), "pix-901", EMPRESA_TROCA_PLANO,
                LIQUIDADO_EM_JULHO, VALOR_PADRAO);
        publicarEm(UUID.randomUUID().toString(), "pix-902", EMPRESA_TROCA_PLANO,
                LIQUIDADO_EM_JULHO, VALOR_PADRAO);
        publicarEm(UUID.randomUUID().toString(), "pix-903", EMPRESA_TROCA_PLANO,
                LIQUIDADO_EM_JULHO, VALOR_PADRAO);

        // franquia de 2 no plano antigo: o terceiro custa R$ 4,90, nao R$ 2,50
        aguardarQuantidadeDeTarifas(EMPRESA_TROCA_PLANO, 3, COMPETENCIA_JULHO);
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_TROCA_PLANO, COMPETENCIA_JULHO))
                .isEqualByComparingTo("4.90");

        // ja em agosto vale o plano novo: franquia de 10, e os tres sao isentos
        publicar(UUID.randomUUID().toString(), "pix-904", EMPRESA_TROCA_PLANO);
        publicar(UUID.randomUUID().toString(), "pix-905", EMPRESA_TROCA_PLANO);
        publicar(UUID.randomUUID().toString(), "pix-906", EMPRESA_TROCA_PLANO);

        aguardarQuantidadeDeTarifas(EMPRESA_TROCA_PLANO, 3, COMPETENCIA);
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_TROCA_PLANO, COMPETENCIA))
                .isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------
    // a tabela de faixas — regra-de-tarifacao.md
    // ------------------------------------------------------------------

    @Test
    @DisplayName("11 - acima da franquia, o VALOR do Pix escolhe a faixa da tarifa")
    void tarifaVemDaFaixaDoValor() {
        // Tabela do Plano PJ: <500 -> 0,50 | <1000 -> 1,00 | <5000 -> 5,00 | resto -> 10,00
        publicar(UUID.randomUUID().toString(), "pix-a01", EMPRESA_FRANQUIA_2);
        publicar(UUID.randomUUID().toString(), "pix-a02", EMPRESA_FRANQUIA_2);
        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 2);

        publicarComValor(UUID.randomUUID().toString(), "pix-a03", EMPRESA_FRANQUIA_2, "100.00");
        publicarComValor(UUID.randomUUID().toString(), "pix-a04", EMPRESA_FRANQUIA_2, "700.00");
        publicarComValor(UUID.randomUUID().toString(), "pix-a05", EMPRESA_FRANQUIA_2, "3000.00");
        publicarComValor(UUID.randomUUID().toString(), "pix-a06", EMPRESA_FRANQUIA_2, "90000.00");

        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 6);
        // 0,50 + 1,00 + 5,00 + 10,00 — cada Pix pagou a faixa do proprio valor
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_FRANQUIA_2, COMPETENCIA))
                .isEqualByComparingTo("16.50");
    }

    @Test
    @DisplayName("12 - a fronteira da faixa e EXCLUSIVA: R$ 500,00 paga a faixa de cima")
    void fronteiraDaFaixaEExclusiva() {
        // "Limite inferior inclusivo, superior exclusivo." Um Pix de exatamente
        // R$ 500,00 nao pertence a faixa "< 500": pertence a "500 <= v < 1000",
        // e paga R$ 1,00. Com a fronteira inclusiva pagaria R$ 0,50 — e o valor
        // redondo e justamente o mais comum numa transferencia.
        publicar(UUID.randomUUID().toString(), "pix-b01", EMPRESA_FRANQUIA_2);
        publicar(UUID.randomUUID().toString(), "pix-b02", EMPRESA_FRANQUIA_2);
        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 2);

        publicarComValor(UUID.randomUUID().toString(), "pix-b03", EMPRESA_FRANQUIA_2, "499.99");
        publicarComValor(UUID.randomUUID().toString(), "pix-b04", EMPRESA_FRANQUIA_2, "500.00");

        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 4);
        // 0,50 do 499,99 mais 1,00 do 500,00 — nao 1,00 do total
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_FRANQUIA_2, COMPETENCIA))
                .isEqualByComparingTo("1.50");
    }

    // ------------------------------------------------------------------
    // o teto mensal — regra-de-tarifacao.md, passos 3 e 4
    // ------------------------------------------------------------------

    @Test
    @DisplayName("13 - o estouro do teto e cobrado PARCIALMENTE, ate completar o teto")
    void tetoEhCobradoParcialmente() {
        // emp-0006: sem isencao, R$ 10,00 o Pix, teto de R$ 25,00.
        //   Pix 1 ->  0 + 10 cabe    -> FAIXA         10,00  (acumulado 10)
        //   Pix 2 -> 10 + 10 cabe    -> FAIXA         10,00  (acumulado 20)
        //   Pix 3 -> 20 + 10 estoura -> TETO_PARCIAL   5,00  (acumulado 25)
        //   Pix 4 -> 25 >= 25        -> TETO_ATINGIDO  0,00  (acumulado 25)
        publicar(UUID.randomUUID().toString(), "pix-c01", EMPRESA_COM_TETO);
        publicar(UUID.randomUUID().toString(), "pix-c02", EMPRESA_COM_TETO);
        publicar(UUID.randomUUID().toString(), "pix-c03", EMPRESA_COM_TETO);
        publicar(UUID.randomUUID().toString(), "pix-c04", EMPRESA_COM_TETO);

        aguardarQuantidadeDeTarifas(EMPRESA_COM_TETO, 4);

        // A INVARIANTE: o acumulado para EXATAMENTE no teto, nunca acima.
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_COM_TETO, COMPETENCIA))
                .isEqualByComparingTo("25.00");

        assertThat(situacoes(EMPRESA_COM_TETO, COMPETENCIA, SituacaoDaTarifaVO.FAIXA))
                .isEqualTo(2L);
        // uma linha TETO_PARCIAL por competencia, e so uma: e ela que identifica
        // qual cobranca bateu o teto
        assertThat(situacoes(EMPRESA_COM_TETO, COMPETENCIA, SituacaoDaTarifaVO.TETO_PARCIAL))
                .isEqualTo(1L);
        assertThat(situacoes(EMPRESA_COM_TETO, COMPETENCIA, SituacaoDaTarifaVO.TETO_ATINGIDO))
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // invariantes do acumulado
    // ------------------------------------------------------------------

    @Test
    @DisplayName("14 - so o Pix ISENTO consome franquia; o tarifado nao")
    void apenasOIsentoConsomeFranquia() {
        // Invariante da especificacao: unidadesFranquiaConsumidas <= franquia do
        // plano. emp-0002 tem franquia 2; publicando cinco Pix, dois saem
        // isentos e tres tarifados — e o consumo tem de parar em 2, nao chegar a
        // 5. O relatorio de fechamento le esse acumulado para dizer quantas
        // isencoes o contrato de fato concedeu.
        for (int i = 1; i <= 5; i++) {
            publicar(UUID.randomUUID().toString(), "pix-d0" + i, EMPRESA_FRANQUIA_2);
        }

        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 5);
        assertThat(repositorio.contarFranquiaConsumida(EMPRESA_FRANQUIA_2, COMPETENCIA))
                .isEqualTo(2L);
        assertThat(situacoes(EMPRESA_FRANQUIA_2, COMPETENCIA, SituacaoDaTarifaVO.FRANQUIA))
                .isEqualTo(2L);
        assertThat(situacoes(EMPRESA_FRANQUIA_2, COMPETENCIA, SituacaoDaTarifaVO.FAIXA))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("15 - a competencia e isolada por mes: a franquia reinicia em setembro")
    void competenciaEIsoladaPorMes() {
        publicar(UUID.randomUUID().toString(), "pix-e01", EMPRESA_FRANQUIA_2);
        publicar(UUID.randomUUID().toString(), "pix-e02", EMPRESA_FRANQUIA_2);
        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 2);
        assertThat(repositorio.totalTarifadoNaCompetencia(EMPRESA_FRANQUIA_2, COMPETENCIA))
                .isEqualByComparingTo("0.00");

        publicarEm(UUID.randomUUID().toString(), "pix-e03", EMPRESA_FRANQUIA_2,
                LIQUIDADO_EM_SETEMBRO, VALOR_PADRAO);
        aguardarQuantidadeDeTarifas(EMPRESA_FRANQUIA_2, 1, COMPETENCIA_SETEMBRO);
        assertThat(situacoes(EMPRESA_FRANQUIA_2, COMPETENCIA_SETEMBRO, SituacaoDaTarifaVO.FRANQUIA))
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // apoio
    // ------------------------------------------------------------------

    private void publicar(String eventoId, String idTransacaoPix, String idEmpresa) {
        publicarEm(eventoId, idTransacaoPix, idEmpresa, LIQUIDADO_EM, VALOR_PADRAO);
    }

    private void publicarComValor(String eventoId, String idTransacaoPix, String idEmpresa,
            String valor) {
        publicarEm(eventoId, idTransacaoPix, idEmpresa, LIQUIDADO_EM, valor);
    }

    private void publicarEm(String eventoId, String idTransacaoPix, String idEmpresa,
            String liquidadoEm, String valor) {
        enviar(montarJson(eventoId, idTransacaoPix, idEmpresa, liquidadoEm, valor),
                eventoId, idEmpresa,
                liquidadoEm);
    }

    /**
     * O corpo do evento. Os cinco campos depois de valor sao os que o consumidor
     * NAO declara, e estao aqui de proposito: e o consumidor tolerante do B.3.
     */
    private String montarJson(String eventoId, String idTransacaoPix, String idEmpresa,
            String liquidadoEm, String valor) {
        return "{"
                + "\"eventoId\":\"" + eventoId + "\","
                + "\"liquidadoEm\":\"" + liquidadoEm + "\","
                + "\"idTransacaoPix\":\"" + idTransacaoPix + "\","
                + "\"idEmpresa\":\"" + idEmpresa + "\","
                + "\"valor\":" + valor + ","
                + "\"chavePix\":\"fulano@exemplo.com\","
                + "\"tipoChave\":\"EMAIL\","
                + "\"bancoDestino\":\"999\","
                + "\"endToEndId\":\"E99900000202608141300000000001\","
                + "\"pagadorNome\":\"Empresa Ficticia\""
                + "}";
    }

    /**
     * A chave da mensagem e o idEmpresa — a mesma que o servico-pix usa. Nao e
     * detalhe de teste: e ela que serializa os Pix de uma empresa na mesma
     * particao, e sem isso a contagem de franquia e a do teto ficariam sujeitas
     * a corrida e o teste passaria a falhar de forma intermitente.
     */
    private void enviar(String json, String eventoId, String idEmpresa, String liquidadoEm) {
        ProducerRecord<String, String> registro =
                new ProducerRecord<String, String>(TOPICO, idEmpresa, json);

        registro.headers().add("ce_specversion", "1.0".getBytes(UTF_8));
        registro.headers().add("ce_id", eventoId.getBytes(UTF_8));
        registro.headers().add("ce_source", "/pagamentos/servico-pix".getBytes(UTF_8));
        registro.headers().add("ce_type", TOPICO.getBytes(UTF_8));
        registro.headers().add("ce_time", liquidadoEm.getBytes(UTF_8));

        publicador.send(registro);
        publicador.flush();
    }

    /**
     * Variante sem o cabecalho ce_id — simula um produtor que viole o contrato.
     * O listener deve cair no eventoId do corpo e registrar WARN.
     */
    private void enviarSemCeId(String json, String idEmpresa) {
        ProducerRecord<String, String> registro =
                new ProducerRecord<String, String>(TOPICO, idEmpresa, json);

        registro.headers().add("ce_specversion", "1.0".getBytes(UTF_8));
        registro.headers().add("ce_source", "/pagamentos/servico-pix".getBytes(UTF_8));
        registro.headers().add("ce_type", TOPICO.getBytes(UTF_8));
        registro.headers().add("ce_time", LIQUIDADO_EM.getBytes(UTF_8));
        // ce_id deliberadamente ausente

        publicador.send(registro);
        publicador.flush();
    }

    private long situacoes(String idEmpresa, String competencia, SituacaoDaTarifaVO situacao) {
        return repositorio.contarPorSituacao(idEmpresa, competencia, situacao);
    }

    private void aguardarQuantidadeDeTarifas(String idEmpresa, long esperada) {
        aguardarQuantidadeDeTarifas(idEmpresa, esperada, COMPETENCIA);
    }

    private void aguardarQuantidadeDeTarifas(String idEmpresa, long esperada, String competencia) {
        Awaitility.await()
                .atMost(PRAZO)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(
                        repositorio.contarPixNaCompetencia(idEmpresa, competencia))
                        .isEqualTo(esperada));
    }

    private void confirmarQueNaoMuda(String idEmpresa, long esperada) {
        Awaitility.await()
                .during(JANELA_DE_OBSERVACAO)
                .atMost(JANELA_DE_OBSERVACAO.plusSeconds(5))
                .untilAsserted(() -> assertThat(
                        repositorio.contarPixNaCompetencia(idEmpresa, COMPETENCIA))
                        .isEqualTo(esperada));
    }
}
