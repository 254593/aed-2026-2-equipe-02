package br.pucminas.aed.pix.service;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/** Dono explicito do retorno assincrono de KafkaTemplate.send(). */
@Component
public class ResultadoPublicacaoListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultadoPublicacaoListener.class);

    public void acompanhar(String eventoId,
                           CompletableFuture<SendResult<String, Object>> resultado) {
        resultado.whenComplete((envio, falha) -> {
            if (falha != null) {
                LOGGER.error("Falha ao publicar o evento {}", eventoId, falha);
                return;
            }

            LOGGER.info("Evento {} publicado no topico {}, particao {}, offset {}",
                    eventoId,
                    envio.getRecordMetadata().topic(),
                    envio.getRecordMetadata().partition(),
                    envio.getRecordMetadata().offset());
        });
    }
}

