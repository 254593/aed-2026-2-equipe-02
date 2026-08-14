package br.pucminas.aed.tarifacao.service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.pucminas.aed.tarifacao.domain.OfertaVO;
import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;

/**
 * O ponto de decisao do dominio: este Pix e isento ou e tarifado?
 *
 * A regra: o cliente tem uma franquia de Pix gratuitos por competencia (mes).
 * Enquanto o consumo do mes for menor que a franquia, o Pix sai por 0,00; a
 * partir do primeiro excedente, custa o valor do plano.
 *
 * TRES DECISOES QUE VALEM A LEITURA
 *
 * 1. @Transactional esta AQUI, e nao no listener. O registro da chave de
 *    deduplicacao e o efeito de negocio precisam estar na MESMA transacao. Em
 *    transacoes separadas existe uma janela em que o processo morre entre as
 *    duas — e o evento volta a ser processado, que e exatamente o que se
 *    queria evitar. Como o commit termina aqui, o ack la no listener so
 *    acontece depois dele.
 *
 * 2. A competencia sai do ocorridoEm do EVENTO, nunca de now(). Um replay do
 *    topico feito em outubro tem que continuar tarifando contra agosto. Usar o
 *    relogio da maquina faria o reprocessamento produzir um resultado
 *    diferente do original — e o extrato deixaria de ser reproduzivel.
 *
 * 3. A contagem da franquia e um read-then-write, e quem a serializa e a CHAVE
 *    DE PARTICAO, nao um lock no banco. Como o servico-pix publica com
 *    clienteId como chave, todos os Pix de um cliente caem na mesma particao e
 *    sao processados em serie por um unico consumidor do grupo. Se a chave
 *    fosse o pixId, dois Pix do mesmo cliente cairiam em particoes diferentes,
 *    seriam processados em paralelo, os dois leriam "4 usados" e os dois
 *    sairiam isentos: a franquia estouraria.
 *
 *    O custo aceito e o outro lado do mesmo eixo: um cliente de volume muito
 *    alto concentra carga numa particao (hot partition). Se isso aparecer, a
 *    saida e rever a granularidade da chave — nao aumentar particoes, porque
 *    isso rebate o hash e quebra a ordem das chaves ja existentes.
 */
@Service
public class TarifacaoService {

    private static final Logger log = LoggerFactory.getLogger(TarifacaoService.class);

    /** Escala 2 para casar com a coluna NUMERIC(10,2) e sair "0.00" no log. */
    private static final BigDecimal ISENTO = new BigDecimal("0.00");

    private final TarifacaoRepository repositorio;

    public TarifacaoService(TarifacaoRepository repositorio) {
        this.repositorio = repositorio;
    }

    /**
     * @return true se a tarifa foi aplicada; false se o evento era reentrega.
     */
    @Transactional
    public boolean processar(String eventoId, PixRealizadoEvent evento) {

        if (!repositorio.registrarEventoSeNovo(eventoId)) {
            log.info("evento {} JA PROCESSADO, descartando em silencio", eventoId);
            return false;
        }

        String competencia = competenciaDe(evento);
        OfertaVO oferta = repositorio.buscarOferta(evento.getClienteId());
        long jaRealizados = repositorio.contarPixNaCompetencia(evento.getClienteId(), competencia);

        BigDecimal valor = decidirValor(oferta, jaRealizados);

        repositorio.registrarTarifa(evento, competencia, valor);

        log.info("pix tarifado  evento={}  cliente={}  competencia={}  consumo={}/{}  valor={}",
                eventoId, evento.getClienteId(), competencia,
                Long.valueOf(jaRealizados + 1), Integer.valueOf(oferta.getPixGratuitosMes()), valor);
        return true;
    }

    /**
     * A regra em uma linha: dentro da franquia e isento, fora dela custa o
     * valor do plano.
     */
    private BigDecimal decidirValor(OfertaVO oferta, long jaRealizados) {
        if (jaRealizados < oferta.getPixGratuitosMes()) {
            return ISENTO;
        }
        return oferta.getValorTarifa();
    }

    /**
     * Competencia no formato YYYY-MM, em UTC. O fuso e fixado de proposito: se
     * dependesse do fuso da maquina, um Pix da virada do mes cairia em
     * competencias diferentes conforme onde o consumidor estivesse rodando.
     */
    private String competenciaDe(PixRealizadoEvent evento) {
        return YearMonth.from(evento.getOcorridoEm().atZone(ZoneOffset.UTC)).toString();
    }
}
