package br.pucminas.aed.tarifacao;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;

/**
 * O que acontece quando uma mensagem NAO pode ser processada.
 *
 * Ate esta configuracao existir, o consumidor usava o JsonDeserializer cru e
 * nenhum tratador de erro. Uma unica mensagem que nao fosse JSON valido
 * estourava DENTRO do poll(), antes de qualquer listener, e o container
 * repetia o poll para sempre no mesmo offset: a particao inteira parava
 * atras da mensagem envenenada (todas as empresas que a dividem), e o log
 * crescia na velocidade do loop — medido: ~3 GB por minuto. O agregador ja
 * tinha corrigido exatamente isso na etapa 3; aqui, que e quem cobra, faltava.
 *
 * TRES PECAS, E A ORDEM IMPORTA:
 *
 *   1. ErrorHandlingDeserializer (application.yml) envolve o JsonDeserializer.
 *      A carga malformada deixa de estourar no poll(): vira um registro com
 *      a excecao num cabecalho, e o container a entrega ao tratador de erro
 *      como DeserializationException — que e classificada como NAO
 *      RETENTAVEL, porque repetir nao conserta bytes.
 *
 *   2. DefaultErrorHandler com backoff exponencial. Para as demais falhas
 *      (banco fora, oferta mal cadastrada) ele repete EM POSICAO, sem tirar o
 *      registro da fila — e o "retry bloqueante" que docs/regra-de-tarifacao.md
 *      exige como a quinta condicao do determinismo da fatura: retentativa
 *      por topico de retentativa e vedada porque reintroduz a mensagem fora
 *      de ordem. O custo, aceito e documentado la, e head-of-line blocking
 *      enquanto as tentativas duram.
 *
 *   3. DeadLetterPublishingRecoverer. Esgotadas as tentativas (ou de imediato,
 *      no caso da desserializacao), o registro vai para
 *      `pagamentos.pix.realizado.v1.dlq`, na MESMA particao de origem, com os
 *      cabecalhos ce_* originais e os cabecalhos kafka_dlt-* que dizem qual
 *      excecao, em qual topico, particao e offset. So depois o offset do
 *      registro recuperado e confirmado (commitRecovered) e a particao segue.
 *      E a DLQ cujo risco residual a regra ja aceitava — "quando reprocessada,
 *      reentra fora de posicao"; ate esta classe ela era prometida e nao
 *      existia, e "esgotar as tentativas" descartava o evento em silencio.
 *
 * O produtor da DLQ serializa por TIPO: quando a falha e de desserializacao a
 * carga que vai para a DLQ sao os bytes ORIGINAIS (nao ha objeto); quando a
 * falha e de negocio, e o PixRealizadoEvent ja desserializado, de volta a
 * JSON. Chave idem: String quando leu, byte[] quando nem a chave leu.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** Sufixo do topico de mensagens mortas: pagamentos.pix.realizado.v1.dlq */
    public static final String SUFIXO_DLQ = ".dlq";

    private final String topico;

    public KafkaConfig(@Value("${tarifacao.topico}") String topico) {
        this.topico = topico;
    }

    /**
     * Mesmo numero de particoes do topico de origem, para o registro morto
     * cair na particao de mesmo numero: quem for reprocessar a DLQ enxerga
     * a ordem relativa que ele tinha.
     */
    @Bean
    public NewTopic topicoDlq() {
        return TopicBuilder.name(topico + SUFIXO_DLQ)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public ProducerFactory<Object, Object> producerFactoryDlq(KafkaProperties propriedades) {
        Map<String, Object> config = new HashMap<String, Object>(
                propriedades.buildProducerProperties(null));

        Map<Class<?>, Serializer<?>> chaves = new HashMap<Class<?>, Serializer<?>>();
        chaves.put(byte[].class, new ByteArraySerializer());
        chaves.put(String.class, new StringSerializer());

        JsonSerializer<PixRealizadoEvent> json = new JsonSerializer<PixRealizadoEvent>(objectMapperDaDlq());
        json.setAddTypeInfo(false);   // mesmo formato do fio: sem cabecalho __TypeId__
        Map<Class<?>, Serializer<?>> valores = new HashMap<Class<?>, Serializer<?>>();
        valores.put(byte[].class, new ByteArraySerializer());
        valores.put(PixRealizadoEvent.class, json);

        return new DefaultKafkaProducerFactory<Object, Object>(config,
                new DelegatingByTypeSerializer(chaves, true),
                new DelegatingByTypeSerializer(valores, true));
    }

    @Bean
    public KafkaTemplate<Object, Object> templateDlq(ProducerFactory<Object, Object> fabrica) {
        return new KafkaTemplate<Object, Object>(fabrica);
    }

    @Bean
    public CommonErrorHandler tratadorDeErro(
            KafkaTemplate<Object, Object> templateDlq,
            @Value("${tarifacao.retentativa.maximo:5}") int maximoDeRetentativas,
            @Value("${tarifacao.retentativa.intervalo-inicial-ms:1000}") long intervaloInicialMs,
            @Value("${tarifacao.retentativa.intervalo-maximo-ms:16000}") long intervaloMaximoMs) {

        DeadLetterPublishingRecoverer paraDlq = new DeadLetterPublishingRecoverer(templateDlq,
                (registro, excecao) -> new TopicPartition(registro.topic() + SUFIXO_DLQ,
                        registro.partition()));

        ExponentialBackOffWithMaxRetries backoff =
                new ExponentialBackOffWithMaxRetries(maximoDeRetentativas);
        backoff.setInitialInterval(intervaloInicialMs);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(intervaloMaximoMs);

        DefaultErrorHandler tratador = new DefaultErrorHandler(paraDlq, backoff);
        // ack-mode e manual: sem isto o offset do registro morto nao seria
        // confirmado e a particao voltaria a ele no proximo poll.
        tratador.setCommitRecovered(true);
        tratador.setRetryListeners((registro, excecao, tentativa) ->
                log.warn("falha ao processar (particao={} offset={}) tentativa {}: {}",
                        Integer.valueOf(registro.partition()),
                        Long.valueOf(registro.offset()),
                        Integer.valueOf(tentativa),
                        excecao.getMessage()));
        return tratador;
    }

    private static ObjectMapper objectMapperDaDlq() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);   // ISO-8601, nunca epoch
        return mapper;
    }
}
