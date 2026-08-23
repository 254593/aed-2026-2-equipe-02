package br.pucminas.aed.agregador.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import br.pucminas.aed.agregador.domain.PixRealizadoEvent;

/**
 * Configuração do Kafka para o agregador.
 * 
 * Define:
 * - Consumer com group.id diferente (agregador-pix-por-hora)
 * - Desserialização JSON
 * - Confirmação manual de offset (para garantir processamento)
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, PixRealizadoEvent> consumerFactory(KafkaProperties properties) {
        var configs = properties.buildConsumerProperties(null);
        return new DefaultKafkaConsumerFactory<>(configs);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PixRealizadoEvent>
            kafkaListenerContainerFactory(ConsumerFactory<String, PixRealizadoEvent> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PixRealizadoEvent>();
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.DefaultErrorHandler());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}
