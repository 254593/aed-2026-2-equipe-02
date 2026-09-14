package br.pucminas.aed.tarifacao.controller;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import br.pucminas.aed.tarifacao.domain.PixEstornadoEvent;
import br.pucminas.aed.tarifacao.service.TarifacaoService;

/** Consome a compensacao de forma assincrona, sem chamada direta ao produtor. */
@Component
public class EstornoListener {

    private final TarifacaoService tarifacaoService;

    public EstornoListener(TarifacaoService tarifacaoService) {
        this.tarifacaoService = tarifacaoService;
    }

    @KafkaListener(topics = "${tarifacao.topico-estorno}",
            groupId = "${spring.kafka.consumer.group-id}-estorno",
            containerFactory = "estornoKafkaListenerContainerFactory")
    public void aoEstornar(ConsumerRecord<String, PixEstornadoEvent> registro,
            Acknowledgment ack) {
        Header cabecalho = registro.headers().lastHeader("ce_id");
        String eventoId = cabecalho == null
                ? registro.value().getEventoId()
                : new String(cabecalho.value(), StandardCharsets.UTF_8);
        tarifacaoService.processarEstorno(eventoId, registro.value());
        ack.acknowledge();
    }
}