package br.pucminas.aed.tarifacao.controller;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;
import br.pucminas.aed.tarifacao.service.TarifacaoService;

/**
 * A porta de entrada do servico. Adaptador, nao regra.
 *
 * Ele faz tres coisas e nenhuma delas e decidir: traduz a mensagem de
 * infraestrutura (ConsumerRecord) em conceitos de dominio, delega ao service e
 * confirma o offset. Toda a regra de tarifacao esta no TarifacaoService.
 *
 * A ORDEM DAS DUAS ULTIMAS LINHAS E O ASSUNTO DA AULA
 *
 *   1. processar(...) roda dentro de uma transacao, no service;
 *   2. so DEPOIS que aquele commit terminou e que o offset e confirmado.
 *
 * Invertendo a ordem, uma falha no commit apos o ack perderia o evento para
 * sempre — o offset teria avancado e o efeito nao teria acontecido. Confirmar
 * depois de processar e o que caracteriza at-least-once, e e por isso que a
 * idempotencia do service e obrigatoria: nesta ordem, o preco de nao perder
 * evento e receber o mesmo evento duas vezes.
 *
 * Nao ha @Transactional aqui. A transacao pertence ao servico de aplicacao; se
 * comecasse no listener, o ack estaria dentro dela e o raciocinio acima cairia.
 */
@Component
public class TarifacaoListener {

    private static final Logger log = LoggerFactory.getLogger(TarifacaoListener.class);

    /** Identidade do fato no envelope CloudEvents. E a chave de deduplicacao. */
    private static final String CABECALHO_ID = "ce_id";

    private final TarifacaoService tarifacaoService;

    public TarifacaoListener(TarifacaoService tarifacaoService) {
        this.tarifacaoService = tarifacaoService;
    }

    @KafkaListener(topics = "${tarifacao.topico}", groupId = "${spring.kafka.consumer.group-id}")
    public void aoRealizarPix(ConsumerRecord<String, PixRealizadoEvent> registro,
                              Acknowledgment ack) {

        String eventoId = identificarEvento(registro);

        tarifacaoService.processar(eventoId, registro.value());

        ack.acknowledge();   // confirma DEPOIS do commit da transacao
    }

    /**
     * A identidade do fato vem do cabecalho ce_id — e o modo binario do
     * CloudEvents existe justamente para que se possa deduplicar e rotear sem
     * desserializar o corpo.
     *
     * O corpo e usado apenas como rede de seguranca, e com aviso no log. Um
     * produtor que publique sem ce_id esta violando o contrato, e isso precisa
     * aparecer; mas deixar o eventoId nulo derrubaria a insercao na chave
     * primaria da tabela de deduplicacao, o offset nunca seria confirmado e a
     * mensagem travaria a particao inteira em retentativa eterna. Entre avisar
     * e parar a fila, avisar e a escolha operacionalmente correta.
     */
    private String identificarEvento(ConsumerRecord<String, PixRealizadoEvent> registro) {
        Header cabecalho = registro.headers().lastHeader(CABECALHO_ID);
        if (cabecalho != null) {
            return new String(cabecalho.value(), StandardCharsets.UTF_8);
        }
        String doCorpo = registro.value().getEventoId();
        log.warn("mensagem sem o cabecalho {} (particao={} offset={}); usando o eventoId do corpo: {}",
                CABECALHO_ID,
                Integer.valueOf(registro.partition()),
                Long.valueOf(registro.offset()),
                doCorpo);
        return doCorpo;
    }
}
