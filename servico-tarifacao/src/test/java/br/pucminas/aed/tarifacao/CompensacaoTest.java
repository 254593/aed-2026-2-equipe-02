package br.pucminas.aed.tarifacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import br.pucminas.aed.tarifacao.domain.FaturaDaCompetenciaVO;
import br.pucminas.aed.tarifacao.domain.PixEstornadoEvent;
import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;
import br.pucminas.aed.tarifacao.service.FaturaProjetor;
import br.pucminas.aed.tarifacao.service.TarifacaoService;

@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = {
        "pagamentos.pix.realizado.v1", "pagamentos.pix.estornado.v1"
})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:compensacao;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "tarifacao.projecao.agendada=false"
})
class CompensacaoTest {

    private static final Instant LIQUIDADO_EM = Instant.parse("2026-08-14T13:00:00Z");

    @Autowired
    private TarifacaoService tarifacaoService;

    @Autowired
    private FaturaProjetor projetor;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void limparEstado() {
        jdbc.update("DELETE FROM fatura_competencia");
        jdbc.update("DELETE FROM estorno");
        jdbc.update("DELETE FROM tarifa");
        jdbc.update("DELETE FROM evento_processado");
    }

    @Test
    void estornoEUmFatoNovoEReentregaNaoDuplicaAjuste() {
        PixRealizadoEvent pix = new PixRealizadoEvent(
                "pix-evento-001", LIQUIDADO_EM, "pix-001", "emp-0003",
                new BigDecimal("700.00"));
        assertThat(tarifacaoService.processar("pix-evento-001", pix)).isTrue();

        PixEstornadoEvent estorno = new PixEstornadoEvent(
                "estorno-evento-001", "pix-evento-001", LIQUIDADO_EM,
                "pix-001", "emp-0003", new BigDecimal("0.99"),
                "contrato recusado na conciliacao");
        assertThat(tarifacaoService.processarEstorno("estorno-evento-001", estorno)).isTrue();
        assertThat(tarifacaoService.processarEstorno("estorno-evento-001", estorno)).isFalse();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tarifa", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM estorno", Long.class)).isEqualTo(1L);

        assertThat(projetor.avancar()).isEqualTo(1);
        FaturaDaCompetenciaVO fatura = projetor.buscar("emp-0003", "2026-08").orElseThrow();
        assertThat(fatura.getTotalTarifado()).isEqualByComparingTo("0.99");
        assertThat(fatura.getTotalEstornado()).isEqualByComparingTo("0.99");
        assertThat(fatura.getTotalLiquido()).isEqualByComparingTo("0.00");
    }
}
