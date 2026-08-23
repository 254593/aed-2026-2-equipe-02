package br.pucminas.aed.agregador.controller;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import br.pucminas.aed.agregador.domain.AgregacaoPorHoraVO;
import br.pucminas.aed.agregador.domain.PixRealizadoEvent;
import br.pucminas.aed.agregador.service.AgregadorService;

/**
 * A porta de entrada do agregador. Adaptador, nao regra.
 *
 * Ele traduz a mensagem de infraestrutura em conceitos de dominio, delega ao
 * service e registra o resultado. NAO GUARDA ESTADO: o acumulado das janelas
 * mora no AgregadorService, porque e ele quem decide. Um listener com estado
 * seria regra de negocio no adaptador de entrada — o desvio que a Secao 12 das
 * Notas de Aula nomeia explicitamente.
 *
 * GRUPO PROPRIO: `agregador-pix-por-hora`, diferente do grupo `tarifacao` da
 * etapa 1. Os dois consomem o MESMO topico e nenhum rouba mensagem do outro —
 * cada grupo tem o proprio ponteiro de leitura, que e a propriedade do log que
 * a aula 02 estabeleceu: ler nao consome.
 *
 * Sem @Transactional e sem ack manual: este consumidor nao tem efeito colateral
 * a proteger. Ele soma em memoria, e o log e a fonte da verdade — perder o
 * estado custa um replay, nao um dado.
 */
@Component
public class AgregadorListener {

    private static final Logger log = LoggerFactory.getLogger(AgregadorListener.class);

    private final AgregadorService agregadorService;

    public AgregadorListener(AgregadorService agregadorService) {
        this.agregadorService = agregadorService;
    }

    @KafkaListener(topics = "${pix.topico}", groupId = "${pix.agregador.group-id}")
    public void aoRealizarPix(ConsumerRecord<String, PixRealizadoEvent> registro) {

        PixRealizadoEvent evento = registro.value();
        if (evento == null) {
            log.warn("mensagem sem corpo (particao={} offset={}); ignorada",
                    Integer.valueOf(registro.partition()), Long.valueOf(registro.offset()));
            return;
        }

        AgregacaoPorHoraVO agregacao = agregadorService.registrar(evento);

        // DEDUPLICACAO: se registrar retorna null, e porque este evento ja foi agregado
        if (agregacao == null) {
            log.debug("pix {} ja foi agregado; reentrega ignorada", evento.getEventoId());
            return;
        }

        // Particao e offset no log de proposito: e o que permite conferir na mao,
        // pelo kafka-console-consumer, que a mensagem daquele offset entrou nesta
        // janela — e nao em outra.
        log.info("pix agregado  transacao={}  liquidadoEm={}  ->  {}  (particao={} offset={})",
                evento.getIdTransacaoPix(),
                evento.getLiquidadoEm(),
                agregacao,
                Integer.valueOf(registro.partition()),
                Long.valueOf(registro.offset()));
    }
}
