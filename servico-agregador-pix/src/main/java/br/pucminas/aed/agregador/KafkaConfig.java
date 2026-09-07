package br.pucminas.aed.agregador;

import java.util.Collection;
import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
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

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private final String topico;

    public KafkaConfig(@Value("${pix.topico}") String topico) {
        this.topico = topico;
    }

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
        fabrica.getContainerProperties()
                .setConsumerRebalanceListener(guardaDeInstanciaUnica());
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

    /**
     * A GUARDA QUE TRANSFORMA UM ACIDENTE NUMA DECISAO OBSERVAVEL.
     *
     * O ADR-003 decide que este servico roda em UMA instancia, e a razao esta
     * na chave de particao: o topico e chaveado por idEmpresa, e a janela de
     * hora nao tem chave nenhuma — ela atravessa, por construcao, as tres
     * particoes. Com uma instancia, ela recebe as tres e enxerga a hora
     * inteira. Com duas ou tres, cada uma soma uma PARCIAL da mesma janela.
     *
     * O que torna isso perigoso e que nada quebra. Nao ha excecao, nao ha erro
     * de desserializacao, nao ha aviso do broker: cada tela mostra um numero
     * perfeitamente coerente consigo mesmo, e o total esta errado. A correcao
     * do relatorio passa a depender de QUANTAS instancias estao no ar, que e
     * uma decisao de infraestrutura e nao de negocio — um --scale de quem nao
     * leu o ADR muda o faturamento relatado sem mudar uma linha de codigo.
     *
     * Dai a guarda. Uma agregacao global que silenciosamente vira parcial e
     * indistinguivel de um defeito enquanto ninguem a mede; medida, vira uma
     * decisao que se monitora e se revisa. E o mesmo criterio que se aplica a
     * uma estrategia de descarte: perder dado e legitimo, perder dado sem
     * contador nao e.
     *
     * O aviso NAO impede a violacao — nao ha como impedi-la aqui, porque quem
     * atribui particao e o coordenador do grupo, nao o processo. Ele a torna
     * visivel no instante em que acontece, que e o que faltava.
     */
    private ConsumerAwareRebalanceListener guardaDeInstanciaUnica() {
        return new ConsumerAwareRebalanceListener() {

            @Override
            public void onPartitionsAssigned(Consumer<?, ?> consumidor,
                                             Collection<TopicPartition> atribuidas) {
                // O estado das janelas mora em memoria e o log e a fonte da
                // verdade — entao TODA subida reconstroi pelo log, desde o
                // inicio. Sem este seek, `auto-offset-reset: earliest` so
                // valeria para um grupo SEM offset confirmado: um reinicio comum
                // retomava do ultimo offset com o mapa vazio e devolvia totais
                // parciais sem nenhum sinal (medido: uma janela de 24 Pix
                // reaparecia com 1). A deduplicacao por eventoId torna o replay
                // seguro tambem num rebalanceamento sem reinicio.
                if (!atribuidas.isEmpty()) {
                    consumidor.seekToBeginning(atribuidas);
                    log.info("reconstruindo as janelas pelo log desde o inicio de {} particao(oes)",
                            Integer.valueOf(atribuidas.size()));
                }

                List<PartitionInfo> doTopico = consumidor.partitionsFor(topico);
                if (doTopico == null) {
                    return;
                }

                int total = doTopico.size();
                int minhas = atribuidas.size();

                if (minhas < total) {
                    log.warn("AGREGACAO PARCIAL: recebi {} de {} particoes de {}. "
                            + "As janelas deste processo somam apenas a fatia dele, e o total por "
                            + "hora esta ERRADO em todas as instancias. O ADR-003 decide instancia "
                            + "unica para este servico; suba so uma, ou implemente a agregacao em "
                            + "dois estagios que o ADR descreve.",
                            Integer.valueOf(minhas), Integer.valueOf(total), topico);
                    return;
                }

                log.info("agregacao completa: {} de {} particoes de {}",
                        Integer.valueOf(minhas), Integer.valueOf(total), topico);
            }
        };
    }
}
