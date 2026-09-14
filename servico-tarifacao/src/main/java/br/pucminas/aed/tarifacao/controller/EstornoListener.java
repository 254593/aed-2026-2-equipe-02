package br.pucminas.aed.tarifacao.controller;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import br.pucminas.aed.tarifacao.domain.PixEstornadoEvent;
import br.pucminas.aed.tarifacao.service.TarifacaoService;

/** Consome a compensacao de forma assincrona, sem chamada direta ao produtor. */
@Component
public class EstornoListener {

    private static final Logger log = LoggerFactory.getLogger(EstornoListener.class);

    private final TarifacaoService tarifacaoService;

    public EstornoListener(TarifacaoService tarifacaoService) {
        this.tarifacaoService = tarifacaoService;
    }

    @KafkaListener(topics = "${tarifacao.topico-estorno}",
            groupId = "${spring.kafka.consumer.group-id}-estorno",
            containerFactory = "estornoKafkaListenerContainerFactory")
    public void aoEstornar(ConsumerRecord<String, PixEstornadoEvent> registro,
            Acknowledgment ack) {
        String eventoId = identificarEvento(registro);
        tarifacaoService.processarEstorno(eventoId, registro.value());
        ack.acknowledge();
    }

    /**
     * Mesmo criterio do TarifacaoListener: a identidade vem do ce_id, e o corpo
     * so entra como rede de seguranca — com aviso, para que o produtor fora do
     * contrato apareca no log em vez de passar em silencio.
     */
    private String identificarEvento(ConsumerRecord<String, PixEstornadoEvent> registro) {
        Header cabecalho = registro.headers().lastHeader("ce_id");
        if (cabecalho != null) {
            return new String(cabecalho.value(), StandardCharsets.UTF_8);
        }
        String doCorpo = registro.value().getEventoId();
        log.warn("estorno sem o cabecalho ce_id (particao={} offset={}); usando o eventoId do corpo: {}",
                Integer.valueOf(registro.partition()),
                Long.valueOf(registro.offset()),
                doCorpo);
        return doCorpo;
    }
}