package br.pucminas.aed.agregador.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.pucminas.aed.agregador.domain.AgregacaoPorHoraVO;
import br.pucminas.aed.agregador.domain.PixRealizadoEvent;

/**
 * Serviço que agrega Pix liquidados por hora.
 * 
 * Usa event time (campo liquidadoEm do evento) como relógio, garantindo
 * que a agregação seja reproduzível se o fluxo for reprocessado do início.
 * 
 * A janela é alinhada por tempo UTC (00:00, 01:00, 02:00...), não pela hora
 * em que o agregador foi iniciado.
 */
@Service
public class AgregadorService {

    private static final Logger log = LoggerFactory.getLogger(AgregadorService.class);
    private static final ZoneId UTC = ZoneId.of("UTC");

    /**
     * Calcula o alinhamento de janela de 1 hora a partir de um instant.
     * Exemplo: se liquidadoEm = 2026-08-23T14:35:20Z, o resultado será:
     * - inicioJanela = 2026-08-23T14:00:00Z
     * - fimJanela = 2026-08-23T15:00:00Z (exclusivo)
     * 
     * @param liquidadoEm instante do evento (event time)
     * @return a janela de 1 hora alinhada por tempo UTC
     */
    public JanelaDeHora calcularJanela(Instant liquidadoEm) {
        ZonedDateTime zdt = liquidadoEm.atZone(UTC);
        
        // Zera minutos e segundos para obter o início da hora
        ZonedDateTime inicioZdt = zdt
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        
        Instant inicio = inicioZdt.toInstant();
        Instant fim = inicioZdt.plusHours(1).toInstant();
        
        return new JanelaDeHora(inicio, fim);
    }

    /**
     * Inicia o acumulador com um Pix.
     * Este método é chamado na primeira vez que um evento entra em uma janela.
     * 
     * @param evento primeiro evento da janela
     * @return acumulador com a agregação inicial
     */
    public AgregacaoPorHoraVO iniciar(PixRealizadoEvent evento) {
        JanelaDeHora janela = calcularJanela(evento.getLiquidadoEm());
        
        BigDecimal valor = evento.getValor() != null ? evento.getValor() : BigDecimal.ZERO;
        
        return new AgregacaoPorHoraVO(
                janela.inicio,
                janela.fim,
                valor,
                1  // quantidade: 1 Pix
        );
    }

    /**
     * Agrega um novo evento com o acumulador existente.
     * Soma os valores e incrementa a contagem.
     * 
     * @param acumulado agregação anterior
     * @param novoEvento novo evento a agregar
     * @return agregação atualizada
     */
    public AgregacaoPorHoraVO agregar(AgregacaoPorHoraVO acumulado, PixRealizadoEvent novoEvento) {
        BigDecimal valorNovo = novoEvento.getValor() != null ? novoEvento.getValor() : BigDecimal.ZERO;
        
        BigDecimal valorTotal = acumulado.getValorTotal().add(valorNovo);
        long novaQuantidade = acumulado.getQuantidade() + 1;
        
        return new AgregacaoPorHoraVO(
                acumulado.getInicioJanela(),
                acumulado.getFimJanela(),
                valorTotal,
                novaQuantidade
        );
    }

    /**
     * Representa uma janela de 1 hora alinhada por tempo UTC.
     */
    public static class JanelaDeHora {
        public final Instant inicio;
        public final Instant fim;

        public JanelaDeHora(Instant inicio, Instant fim) {
            this.inicio = inicio;
            this.fim = fim;
        }

        @Override
        public String toString() {
            return "[" + inicio + ", " + fim + ")";
        }
    }
}
