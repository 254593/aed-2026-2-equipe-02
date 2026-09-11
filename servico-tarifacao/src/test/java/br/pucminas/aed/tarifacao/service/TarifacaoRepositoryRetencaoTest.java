package br.pucminas.aed.tarifacao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class TarifacaoRepositoryRetencaoTest {

    @Test
    void expurgoUsaLimiteExclusivoEDevolveQuantidadeRemovida() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Instant limite = Instant.parse("2026-08-11T12:00:00Z");
        String sql = "DELETE FROM evento_processado WHERE processado_em < ?";
        when(jdbc.update(sql, Timestamp.from(limite))).thenReturn(4);

        TarifacaoRepository repositorio = new TarifacaoRepository(jdbc);

        assertThat(repositorio.expurgarEventosProcessadosAntesDe(limite)).isEqualTo(4);
        verify(jdbc).update(sql, Timestamp.from(limite));
    }
}
