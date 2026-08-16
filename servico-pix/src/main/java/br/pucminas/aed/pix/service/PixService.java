package br.pucminas.aed.pix.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import br.pucminas.aed.pix.domain.PixRealizadoEvent;
import br.pucminas.aed.pix.domain.RealizacaoPixVO;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class PixService {

    private final KafkaTemplate<String, Object> clienteDoBroker;
    private final ResultadoPublicacaoListener resultadoPublicacaoListener;
    private final Clock relogio;
    private final String topico;
    private final String origem;
    private final String tipo;

    @Autowired
    public PixService(KafkaTemplate<String, Object> clienteDoBroker,
                      ResultadoPublicacaoListener resultadoPublicacaoListener,
                      @Value("${pix.topico}") String topico,
                      @Value("${pix.cloud-events.source}") String origem,
                      @Value("${pix.cloud-events.type}") String tipo) {
        this(clienteDoBroker, resultadoPublicacaoListener, Clock.systemUTC(), topico, origem, tipo);
    }

    PixService(KafkaTemplate<String, Object> clienteDoBroker,
               ResultadoPublicacaoListener resultadoPublicacaoListener,
               Clock relogio,
               String topico,
               String origem,
               String tipo) {
        this.clienteDoBroker = clienteDoBroker;
        this.resultadoPublicacaoListener = resultadoPublicacaoListener;
        this.relogio = relogio;
        this.topico = topico;
        this.origem = origem;
        this.tipo = tipo;
    }

    public PixRealizadoEvent realizar(RealizacaoPixVO realizacao) {
        validar(realizacao);

        PixRealizadoEvent evento = new PixRealizadoEvent(
                UUID.randomUUID().toString(),
                Instant.now(relogio),
                realizacao.getIdTransacaoPix(),
                realizacao.getIdEmpresa(),
                realizacao.getValor(),
                realizacao.getChavePix(),
                realizacao.getTipoChave(),
                realizacao.getBancoDestino(),
                realizacao.getEndToEndId(),
                realizacao.getPagadorNome());

        ProducerRecord<String, Object> registro =
                new ProducerRecord<String, Object>(topico, evento.getIdEmpresa(), evento);
        adicionarCabecalhos(registro, evento);

        CompletableFuture<SendResult<String, Object>> resultado = clienteDoBroker.send(registro);
        resultadoPublicacaoListener.acompanhar(evento.getEventoId(), resultado);
        return evento;
    }

    private void adicionarCabecalhos(ProducerRecord<String, Object> registro,
                                     PixRealizadoEvent evento) {
        registro.headers().add("ce_specversion", "1.0".getBytes(UTF_8));
        registro.headers().add("ce_id", evento.getEventoId().getBytes(UTF_8));
        registro.headers().add("ce_source", origem.getBytes(UTF_8));
        registro.headers().add("ce_type", tipo.getBytes(UTF_8));
        registro.headers().add("ce_time", evento.getLiquidadoEm().toString().getBytes(UTF_8));
    }

    private void validar(RealizacaoPixVO realizacao) {
        if (realizacao == null) {
            throw new IllegalArgumentException("Os dados do Pix sao obrigatorios");
        }
        if (realizacao.getIdTransacaoPix() == null || realizacao.getIdTransacaoPix().isBlank()) {
            throw new IllegalArgumentException("idTransacaoPix e obrigatorio");
        }
        if (realizacao.getIdEmpresa() == null || realizacao.getIdEmpresa().isBlank()) {
            throw new IllegalArgumentException("idEmpresa e obrigatorio");
        }
        if (realizacao.getValor() == null
                || realizacao.getValor().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("valor deve ser maior que zero");
        }
    }
}

