package br.pucminas.aed.tarifacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import br.pucminas.aed.tarifacao.domain.FaturaDaCompetenciaVO;
import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;
import br.pucminas.aed.tarifacao.domain.SituacaoDaTarifaVO;
import br.pucminas.aed.tarifacao.service.FaturaProjetor;
import br.pucminas.aed.tarifacao.service.TarifacaoRepository;
import br.pucminas.aed.tarifacao.service.TarifacaoService;

/**
 * O event store do CicloDeTarifacao e a projecao derivada dele — ADR-005.
 *
 * OS FATOS ENTRAM PELO SERVICE, E NAO PELO TOPICO. Nao e economia de tempo: o
 * assunto aqui e o que acontece DEPOIS da decisao — a versao atribuida ao fato
 * e a projecao construida a partir dele. O caminho do broker ate o service ja
 * e provado pelo IdempotenciaTest, e repeti-lo aqui so acrescentaria uma espera
 * assincrona entre o teste e aquilo que ele quer observar.
 *
 * O AGENDAMENTO DO PROJETOR FICA DESLIGADO. Com ele ligado, uma projecao
 * disparada por relogio no meio do cenario faria o resultado depender de quem
 * chegou primeiro — e o teste da reconstrucao, que apaga a tabela, e
 * exatamente o que mais sofreria. Aqui o avanco e chamado a mao.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = "pagamentos.pix.realizado.v1")
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:projecao;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "tarifacao.projecao.agendada=false",
        "logging.level.br.pucminas.aed=INFO"
})
class ProjecaoDaFaturaTest {

    /** Instante fixo: a competencia sai daqui, nunca do relogio da maquina. */
    private static final Instant LIQUIDADO_EM = Instant.parse("2026-08-14T13:00:00Z");
    private static final String COMPETENCIA = "2026-08";

    private static final Instant LIQUIDADO_EM_SETEMBRO = Instant.parse("2026-09-02T09:00:00Z");
    private static final String COMPETENCIA_SETEMBRO = "2026-09";

    /** 2 isentos por mes, depois faixa de R$ 0,50 abaixo de R$ 500. */
    private static final String EMPRESA_FRANQUIA_2 = "emp-0002";
    /** 0 isentos, R$ 10,00 por Pix, teto de R$ 25,00. */
    private static final String EMPRESA_COM_TETO = "emp-0006";
    /** Sem linha em `oferta`: nunca teve contrato. */
    private static final String EMPRESA_SEM_CONTRATO = "emp-9999";

    @Autowired
    private TarifacaoService tarifacaoService;

    @Autowired
    private TarifacaoRepository repositorio;

    @Autowired
    private FaturaProjetor projetor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void prepararEstado() {
        jdbc.update("DELETE FROM fatura_competencia");
        repositorio.limparTarifas();
        repositorio.limparEventosProcessados();
    }

    // ------------------------------------------------------------------
    // o event store: ordem e versao por stream
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1 - a versao e sequencial dentro do stream e comeca em 1")
    void versaoSequencialPorStream() {
        processar("pix-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        processar("pix-2", EMPRESA_FRANQUIA_2, "200.00", LIQUIDADO_EM);
        processar("pix-3", EMPRESA_FRANQUIA_2, "300.00", LIQUIDADO_EM);

        assertThat(versoesDe(EMPRESA_FRANQUIA_2, COMPETENCIA)).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("2 - o stream e (empresa, competencia): a numeracao recomeca no mes seguinte")
    void numeracaoIndependentePorStream() {
        processar("pix-ago-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        processar("pix-ago-2", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        processar("pix-set-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM_SETEMBRO);

        assertThat(versoesDe(EMPRESA_FRANQUIA_2, COMPETENCIA)).containsExactly(1L, 2L);
        assertThat(versoesDe(EMPRESA_FRANQUIA_2, COMPETENCIA_SETEMBRO)).containsExactly(1L);
    }

    @Test
    @DisplayName("3 - CONFLITO DE VERSAO: dois fatos na mesma versao do stream sao recusados")
    void conflitoDeVersaoEDetectado() {
        processar("pix-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);

        // Escreve por fora do service, como faria um SEGUNDO escritor no mesmo
        // stream — o cenario que a compensacao vai criar. O indice unico e o
        // que separa "erro visivel" de "acumulado errado em silencio".
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tarifa (evento_id, id_empresa, id_transacao_pix, competencia,"
                        + " situacao, valor, liquidado_em, versao)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), EMPRESA_FRANQUIA_2, "pix-concorrente",
                COMPETENCIA, SituacaoDaTarifaVO.FRANQUIA.name(), new BigDecimal("0.00"),
                Timestamp.from(LIQUIDADO_EM), Long.valueOf(1L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // a projecao: derivada, e o replay que a reconstroi
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4 - a projecao reproduz o fold do log")
    void projecaoReproduzOLog() {
        // franquia 2: os dois primeiros isentos, o terceiro na faixa de 0,50
        processar("pix-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        processar("pix-2", EMPRESA_FRANQUIA_2, "200.00", LIQUIDADO_EM);
        processar("pix-3", EMPRESA_FRANQUIA_2, "300.00", LIQUIDADO_EM);

        projetor.avancar();

        FaturaDaCompetenciaVO fatura = fatura(EMPRESA_FRANQUIA_2);
        assertThat(fatura.getQuantidadeDePix()).isEqualTo(3L);
        assertThat(fatura.getTotalTarifado()).isEqualByComparingTo("0.50");
        assertThat(fatura.getQuantidadeDe(SituacaoDaTarifaVO.FRANQUIA)).isEqualTo(2L);
        assertThat(fatura.getQuantidadeDe(SituacaoDaTarifaVO.FAIXA)).isEqualTo(1L);
        assertThat(fatura.getVersaoProjetada()).isEqualTo(3L);
    }

    @Test
    @DisplayName("5 - RECONSTRUCAO: apagar a projecao inteira e refaze-la pelo log da o mesmo resultado")
    void reconstrucaoPeloReplayDaOMesmoResultado() {
        // as tres saidas do teto, para que a projecao tenha o que distinguir:
        // 10,00 + 10,00 cabem; o terceiro estoura e vira TETO_PARCIAL de 5,00;
        // o quarto sai TETO_ATINGIDO, valor zero.
        processar("pix-1", EMPRESA_COM_TETO, "100.00", LIQUIDADO_EM);
        processar("pix-2", EMPRESA_COM_TETO, "100.00", LIQUIDADO_EM);
        processar("pix-3", EMPRESA_COM_TETO, "100.00", LIQUIDADO_EM);
        processar("pix-4", EMPRESA_COM_TETO, "100.00", LIQUIDADO_EM);
        processar("pix-sem", EMPRESA_SEM_CONTRATO, "100.00", LIQUIDADO_EM);

        projetor.avancar();
        FaturaDaCompetenciaVO antes = fatura(EMPRESA_COM_TETO);
        FaturaDaCompetenciaVO antesSemContrato = fatura(EMPRESA_SEM_CONTRATO);

        // O criterio: apagar tudo e reconstruir SO pelo log.
        projetor.reconstruir();

        FaturaDaCompetenciaVO depois = fatura(EMPRESA_COM_TETO);
        assertThat(depois.getTotalTarifado()).isEqualByComparingTo(antes.getTotalTarifado());
        assertThat(depois.getQuantidadeDePix()).isEqualTo(antes.getQuantidadeDePix());
        assertThat(depois.getPorSituacao()).isEqualTo(antes.getPorSituacao());
        assertThat(depois.getVersaoProjetada()).isEqualTo(antes.getVersaoProjetada());

        assertThat(depois.getTotalTarifado()).isEqualByComparingTo("25.00");
        assertThat(depois.getQuantidadeDe(SituacaoDaTarifaVO.FAIXA)).isEqualTo(2L);
        assertThat(depois.getQuantidadeDe(SituacaoDaTarifaVO.TETO_PARCIAL)).isEqualTo(1L);
        assertThat(depois.getQuantidadeDe(SituacaoDaTarifaVO.TETO_ATINGIDO)).isEqualTo(1L);

        // e o outro stream tambem volta inteiro
        assertThat(fatura(EMPRESA_SEM_CONTRATO).getPorSituacao())
                .isEqualTo(antesSemContrato.getPorSituacao());
    }

    @Test
    @DisplayName("6 - o avanco e incremental: fatos novos somam sobre a linha existente")
    void avancoIncremental() {
        processar("pix-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        projetor.avancar();
        assertThat(fatura(EMPRESA_FRANQUIA_2).getVersaoProjetada()).isEqualTo(1L);

        processar("pix-2", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        processar("pix-3", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        projetor.avancar();

        FaturaDaCompetenciaVO fatura = fatura(EMPRESA_FRANQUIA_2);
        assertThat(fatura.getQuantidadeDePix()).isEqualTo(3L);
        assertThat(fatura.getVersaoProjetada()).isEqualTo(3L);
        assertThat(fatura.getTotalTarifado()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("7 - avancar sem fato novo nao altera a projecao")
    void avancoSemFatoNovoEIdempotente() {
        processar("pix-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        projetor.avancar();
        FaturaDaCompetenciaVO antes = fatura(EMPRESA_FRANQUIA_2);

        assertThat(projetor.avancar()).isZero();

        FaturaDaCompetenciaVO depois = fatura(EMPRESA_FRANQUIA_2);
        assertThat(depois.getQuantidadeDePix()).isEqualTo(antes.getQuantidadeDePix());
        assertThat(depois.getTotalTarifado()).isEqualByComparingTo(antes.getTotalTarifado());
        assertThat(depois.getVersaoProjetada()).isEqualTo(antes.getVersaoProjetada());
    }

    @Test
    @DisplayName("8 - a reentrega nao entra na projecao: um Pix entregue 3x conta 1x")
    void reentregaNaoInflaAProjecao() {
        PixRealizadoEvent evento = evento("evt-fixo", "pix-1", EMPRESA_FRANQUIA_2,
                "100.00", LIQUIDADO_EM);
        tarifacaoService.processar("evt-fixo", evento);
        tarifacaoService.processar("evt-fixo", evento);
        tarifacaoService.processar("evt-fixo", evento);

        projetor.avancar();

        FaturaDaCompetenciaVO fatura = fatura(EMPRESA_FRANQUIA_2);
        assertThat(fatura.getQuantidadeDePix()).isEqualTo(1L);
        assertThat(fatura.getVersaoProjetada()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // a transacao e o schema
    // ------------------------------------------------------------------

    @Test
    @DisplayName("9 - falha ao gravar o fato desfaz TAMBEM a deduplicacao, e o evento pode ser reprocessado")
    void falhaNoInsertDesfazADeduplicacao() {
        // O ADR-005 afirma: na violacao de constraint ao gravar a tarifa, "a
        // transacao inteira e desfeita, inclusive o registro de deduplicacao",
        // e a reentrega reprocessa. Sem isso, o evento ficaria marcado como
        // processado SEM ter produzido efeito — perdido para sempre.
        //
        // A corrida real de versao (dois escritores calculando o mesmo MAX+1)
        // nao se reproduz de forma deterministica numa thread. A propriedade
        // testada e a mesma, provocada pela constraint irma no MESMO INSERT: a
        // chave primaria evento_id. Uma linha pre-existente bloqueia o INSERT
        // da tarifa depois de a deduplicacao ja ter gravado.
        jdbc.update("INSERT INTO tarifa (evento_id, id_empresa, id_transacao_pix, competencia,"
                        + " situacao, valor, liquidado_em, versao) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "evt-bloqueado", EMPRESA_FRANQUIA_2, "pix-antigo", COMPETENCIA,
                SituacaoDaTarifaVO.FRANQUIA.name(), new BigDecimal("0.00"),
                Timestamp.from(LIQUIDADO_EM), Long.valueOf(1L));

        PixRealizadoEvent evento = evento("evt-bloqueado", "pix-novo", EMPRESA_FRANQUIA_2,
                "100.00", LIQUIDADO_EM);

        assertThatThrownBy(() -> tarifacaoService.processar("evt-bloqueado", evento))
                .isInstanceOf(DataIntegrityViolationException.class);

        // a deduplicacao NAO pode ter ficado: senao o evento esta perdido
        assertThat(repositorio.contarEventosProcessados()).isZero();

        // removido o obstaculo, a reentrega processa normalmente
        jdbc.update("DELETE FROM tarifa WHERE evento_id = ?", "evt-bloqueado");
        assertThat(tarifacaoService.processar("evt-bloqueado", evento)).isTrue();
        assertThat(repositorio.contarEventosProcessados()).isEqualTo(1L);
        assertThat(versoesDe(EMPRESA_FRANQUIA_2, COMPETENCIA)).containsExactly(1L);
    }

    @Test
    @DisplayName("10 - o schema.sql pode rodar de novo sobre o banco ja criado")
    void schemaEIdempotente() throws Exception {
        // spring.sql.init.mode=always reexecuta o schema a CADA subida. Tudo
        // nele precisa ser IF NOT EXISTS / ON CONFLICT DO NOTHING — inclusive o
        // indice unico novo. Este teste e o que impede alguem de trocar o
        // CREATE UNIQUE INDEX IF NOT EXISTS por um ADD CONSTRAINT e quebrar a
        // segunda subida.
        processar("pix-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);

        try (java.sql.Connection conexao = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conexao, new ClassPathResource("schema.sql"));
        }

        // e nao apagou nem duplicou nada
        assertThat(versoesDe(EMPRESA_FRANQUIA_2, COMPETENCIA)).containsExactly(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM oferta WHERE id_empresa = 'emp-0001'", Long.class))
                .isEqualTo(1L);
    }

    /**
     * O QUE O TESTE 5 NAO ALCANCA.
     *
     * La, os dois lados da comparacao partem de uma projecao inexistente: o
     * avancar() inicial e o reconstruir() seguinte percorrem o mesmo caminho,
     * sobre a mesma entrada. O caminho de ESCRITA SOBRE LINHA EXISTENTE — o que
     * roda em toda passagem depois da primeira — nao ficava em nenhum dos dois.
     *
     * Aqui a projecao e construida em DUAS passagens, e a comparacao tem dois
     * eixos: contra o replay do zero, e contra o LADO DE ESCRITA, que deriva os
     * mesmos numeros da `tarifa` por conta propria. O segundo eixo e o que
     * importa — comparar a projecao apenas consigo mesma e invariante a
     * qualquer defeito deterministico do projetor, porque os dois lados usariam
     * o mesmo codigo.
     */
    @Test
    @DisplayName("11 - o avanco incremental concorda com a reconstrucao E com o lado de escrita")
    void avancoIncrementalConcordaComAReconstrucao() {
        processar("pix-1", EMPRESA_COM_TETO, "100.00", LIQUIDADO_EM);
        processar("pix-2", EMPRESA_COM_TETO, "100.00", LIQUIDADO_EM);
        projetor.avancar();

        // segunda passagem: agora a linha da projecao JA EXISTE
        processar("pix-3", EMPRESA_COM_TETO, "100.00", LIQUIDADO_EM);
        processar("pix-4", EMPRESA_COM_TETO, "100.00", LIQUIDADO_EM);
        processar("pix-sem", EMPRESA_SEM_CONTRATO, "100.00", LIQUIDADO_EM);
        projetor.avancar();

        FaturaDaCompetenciaVO incremental = fatura(EMPRESA_COM_TETO);

        // eixo 1 — o oraculo independente
        assertThat(incremental.getTotalTarifado()).isEqualByComparingTo(
                repositorio.totalTarifadoNaCompetencia(EMPRESA_COM_TETO, COMPETENCIA));
        assertThat(incremental.getQuantidadeDePix()).isEqualTo(
                repositorio.contarPixNaCompetencia(EMPRESA_COM_TETO, COMPETENCIA));
        for (SituacaoDaTarifaVO situacao : SituacaoDaTarifaVO.values()) {
            assertThat(incremental.getQuantidadeDe(situacao))
                    .as("quantidade de %s", situacao)
                    .isEqualTo(repositorio.contarPorSituacao(
                            EMPRESA_COM_TETO, COMPETENCIA, situacao));
        }

        // as tres saidas do teto: 10,00 + 10,00 cabem, o terceiro vira
        // TETO_PARCIAL de 5,00 e o quarto sai TETO_ATINGIDO
        assertThat(incremental.getTotalTarifado()).isEqualByComparingTo("25.00");
        assertThat(incremental.getVersaoProjetada()).isEqualTo(4L);

        // eixo 2 — o replay do zero chega ao mesmo estado
        projetor.reconstruir();
        FaturaDaCompetenciaVO reconstruida = fatura(EMPRESA_COM_TETO);

        assertThat(reconstruida.getTotalTarifado())
                .isEqualByComparingTo(incremental.getTotalTarifado());
        assertThat(reconstruida.getQuantidadeDePix())
                .isEqualTo(incremental.getQuantidadeDePix());
        assertThat(reconstruida.getPorSituacao()).isEqualTo(incremental.getPorSituacao());
        assertThat(reconstruida.getVersaoProjetada())
                .isEqualTo(incremental.getVersaoProjetada());

        assertThat(fatura(EMPRESA_SEM_CONTRATO).getQuantidadeDePix()).isEqualTo(1L);
    }

    /**
     * A PROJECAO SE CURA, e e isto que substitui a marca d'agua como trava.
     *
     * Uma linha divergente — marca d'agua adiantada por restauracao parcial, ou
     * total escrito a mao — some na passagem seguinte, porque o projetor grava o
     * fold absoluto e nao confia no que estava la. Antes isto era o modo de
     * falha permanente: com `HAVING MAX(versao) > versao_projetada`, uma marca
     * d'agua ADIANTADA tirava o stream da descoberta para sempre.
     */
    @Test
    @DisplayName("12 - projecao divergente do log e corrigida na passagem seguinte")
    void projecaoDivergenteSeCura() {
        processar("pix-1", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        processar("pix-2", EMPRESA_FRANQUIA_2, "100.00", LIQUIDADO_EM);
        projetor.avancar();
        FaturaDaCompetenciaVO correta = fatura(EMPRESA_FRANQUIA_2);

        // corrompe a linha: total errado e marca d'agua ADIANTADA em relacao ao log
        jdbc.update("UPDATE fatura_competencia "
                        + "   SET total_tarifado = 999.00, qtd_pix = 99, versao_projetada = 500 "
                        + " WHERE id_empresa = ? AND competencia = ?",
                EMPRESA_FRANQUIA_2, COMPETENCIA);

        assertThat(projetor.avancar()).isEqualTo(1);

        FaturaDaCompetenciaVO curada = fatura(EMPRESA_FRANQUIA_2);
        assertThat(curada.getTotalTarifado()).isEqualByComparingTo(correta.getTotalTarifado());
        assertThat(curada.getQuantidadeDePix()).isEqualTo(correta.getQuantidadeDePix());
        assertThat(curada.getVersaoProjetada()).isEqualTo(correta.getVersaoProjetada());
    }

    // ------------------------------------------------------------------

    private void processar(String idTransacaoPix, String idEmpresa, String valor,
            Instant liquidadoEm) {
        String eventoId = UUID.randomUUID().toString();
        tarifacaoService.processar(eventoId,
                evento(eventoId, idTransacaoPix, idEmpresa, valor, liquidadoEm));
    }

    private PixRealizadoEvent evento(String eventoId, String idTransacaoPix, String idEmpresa,
            String valor, Instant liquidadoEm) {
        return new PixRealizadoEvent(eventoId, liquidadoEm, idTransacaoPix, idEmpresa,
                new BigDecimal(valor));
    }

    private List<Long> versoesDe(String idEmpresa, String competencia) {
        return jdbc.queryForList(
                "SELECT versao FROM tarifa WHERE id_empresa = ? AND competencia = ? ORDER BY versao",
                Long.class, idEmpresa, competencia);
    }

    private FaturaDaCompetenciaVO fatura(String idEmpresa) {
        return projetor.buscar(idEmpresa, COMPETENCIA)
                .orElseThrow(() -> new AssertionError(
                        "a projecao nao tem linha para " + idEmpresa + " em " + COMPETENCIA));
    }
}
