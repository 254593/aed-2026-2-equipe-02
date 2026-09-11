package br.pucminas.aed.tarifacao.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Executa a politica de retencao da memoria de idempotencia.
 *
 * O prazo precisa superar a retencao do topico de origem. Caso contrario, um
 * replay pode encontrar o ce_id ja removido e repetir o efeito de negocio.
 */
@Component
@ConditionalOnProperty(name = "tarifacao.deduplicacao.expurgo-habilitado",
                       havingValue = "true", matchIfMissing = true)
public class EventoProcessadoExpurgador {

    private static final Logger log = LoggerFactory.getLogger(EventoProcessadoExpurgador.class);

    private final TarifacaoRepository repositorio;
    private final Duration retencao;
    private final Clock relogio;

    @Autowired
    public EventoProcessadoExpurgador(
            TarifacaoRepository repositorio,
            @Value("${tarifacao.deduplicacao.retencao-dias:30}") long retencaoEmDias) {
        this(repositorio, Duration.ofDays(retencaoEmDias), Clock.systemUTC());
    }

    EventoProcessadoExpurgador(
            TarifacaoRepository repositorio,
            Duration retencao,
            Clock relogio) {
        if (retencao.isZero() || retencao.isNegative()) {
            throw new IllegalArgumentException("a retencao da deduplicacao deve ser positiva");
        }
        this.repositorio = repositorio;
        this.retencao = retencao;
        this.relogio = relogio;
    }

    @Scheduled(fixedDelayString = "${tarifacao.deduplicacao.expurgo-intervalo-ms:86400000}",
               initialDelayString = "${tarifacao.deduplicacao.expurgo-atraso-inicial-ms:60000}")
    public void expurgar() {
        expurgarEm(Instant.now(relogio));
    }

    int expurgarEm(Instant agora) {
        Instant limiteExclusivo = agora.minus(retencao);
        int removidos = repositorio.expurgarEventosProcessadosAntesDe(limiteExclusivo);
        log.info("expurgo da deduplicacao removeu {} evento(s) anterior(es) a {}",
                Integer.valueOf(removidos), limiteExclusivo);
        return removidos;
    }
}
