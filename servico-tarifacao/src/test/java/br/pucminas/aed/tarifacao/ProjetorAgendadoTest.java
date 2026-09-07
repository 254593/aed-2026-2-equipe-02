package br.pucminas.aed.tarifacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;
import br.pucminas.aed.tarifacao.service.FaturaProjetor;
import br.pucminas.aed.tarifacao.service.TarifacaoService;

/**
 * O projetor AGENDADO, ligado — o unico teste em que ele roda por conta propria.
 *
 * O ProjecaoDaFaturaTest desliga o agendamento para controlar o momento de
 * cada avanco. Mas alguem precisa provar que, ligado, ele projeta: um
 * @EnableScheduling esquecido ou um @ConditionalOnProperty invertido nao
 * derruba nenhum outro teste — e, em producao, a fatura simplesmente nunca
 * aparece, sem erro nenhum. E a mesma classe de falha silenciosa que este
 * projeto ja combate no agregador.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = "pagamentos.pix.realizado.v1")
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:agendado;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "tarifacao.projecao.agendada=true",
        "tarifacao.projecao.intervalo-ms=200",
        "tarifacao.projecao.atraso-inicial-ms=100",
        "logging.level.br.pucminas.aed=INFO"
})
class ProjetorAgendadoTest {

    private static final Instant LIQUIDADO_EM = Instant.parse("2026-08-14T13:00:00Z");
    private static final String COMPETENCIA = "2026-08";
    private static final String EMPRESA = "emp-0002";

    @Autowired
    private TarifacaoService tarifacaoService;

    @Autowired
    private FaturaProjetor projetor;

    @Test
    @DisplayName("o projetor agendado incorpora o fato sem ninguem chamar avancar()")
    void projetaSozinho() {
        String eventoId = UUID.randomUUID().toString();
        tarifacaoService.processar(eventoId, new PixRealizadoEvent(
                eventoId, LIQUIDADO_EM, "pix-1", EMPRESA, new BigDecimal("100.00")));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(projetor.buscar(EMPRESA, COMPETENCIA))
                        .isPresent()
                        .get()
                        .satisfies(fatura -> {
                            assertThat(fatura.getQuantidadeDePix()).isEqualTo(1L);
                            assertThat(fatura.getVersaoProjetada()).isEqualTo(1L);
                        }));
    }
}
