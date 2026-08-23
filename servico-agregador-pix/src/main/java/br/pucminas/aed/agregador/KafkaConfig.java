package br.pucminas.aed.agregador;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import br.pucminas.aed.agregador.domain.PixRealizadoEvent;

/**
 * O consumo do agregador.
 *
 * A DESSERIALIZACAO E O PONTO DELICADO, e ja quebrou uma vez. O servico-pix
 * publica com setAddTypeInfo(false): a mensagem NAO leva o cabecalho
 * __TypeId__ com o nome da classe Java, e isso e deliberado — mandar o nome de
 * uma classe do produtor dentro do contrato acoplaria os dois lados. A
 * consequencia e que o consumidor precisa dizer sozinho qual classe usar, pelo
 * spring.json.value.default.type no application.yml. Sem isso o
 * JsonDeserializer estoura com "No type information in headers and no default
 * type provided", a mensagem e reentregue para sempre e a particao trava.
 *
 * O ErrorHandlingDeserializer existe por causa desse mesmo modo de falha:
 * SerializationException acontece ANTES de o listener ser chamado, e o
 * DefaultErrorHandler sozinho nao consegue trata-la — ele recusa com "cannot
 * process 'SerializationException's directly". Envolvendo o deserializador, a
 * falha vira um valor nulo que o listener descarta com log, em vez de um loop
 * infinito. Uma carga malformada custa um registro, nao a particao inteira.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, PixRealizadoEvent> consumerFactory(KafkaProperties propriedades) {
        return new DefaultKafkaConsumerFactory<String, PixRealizadoEvent>(
                propriedades.buildConsumerProperties(null));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PixRealizadoEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, PixRealizadoEvent> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, PixRealizadoEvent> fabrica =
                new ConcurrentKafkaListenerContainerFactory<String, PixRealizadoEvent>();
        fabrica.setConsumerFactory(consumerFactory);
        fabrica.setCommonErrorHandler(tratadorDeErro());
        return fabrica;
    }

    /**
     * Retry bloqueante, repetindo em posicao — nunca por topico de retentativa.
     * Aqui a ordem nao muda o resultado, porque a soma e comutativa e a janela
     * vem do evento; mas manter o mesmo padrao dos outros consumidores evita
     * que alguem replique um retry reordenador onde ele importaria.
     */
    private CommonErrorHandler tratadorDeErro() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
    }
}
