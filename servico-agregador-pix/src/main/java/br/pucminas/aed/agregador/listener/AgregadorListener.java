package br.pucminas.aed.agregador.listener;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import br.pucminas.aed.agregador.domain.AgregacaoPorHoraVO;
import br.pucminas.aed.agregador.domain.PixRealizadoEvent;
import br.pucminas.aed.agregador.service.AgregadorService;

/**
 * Listener que consome eventos de Pix e realiza agregação em memória por hora.
 * 
 * IMPORTANTE: Este é um agregador em memória, adequado para demonstração.
 * Em produção, seria necessário:
 * - Persistência de estado (RocksDB, banco de dados)
 * - Watermark para fechar janelas tardias
 * - Kafka Streams ou Apache Flink para processamento distribuído
 * 
 * A agregação responde à pergunta de negócio: "Quanto foi liquidado em Pix por hora?"
 * Esta métrica é útil para:
 * - Monitorar o fluxo de receita
 * - Alimentar o sistema de faturamento
 * - Análise de sazonalidade de vendas
 */
@Component
public class AgregadorListener {

    private static final Logger log = LoggerFactory.getLogger(AgregadorListener.class);

    /**
     * State do agregador: mantém a agregação mais recente por janela.
     * Chave: representação da janela (ex: "2026-08-23T14:00:00Z")
     * Valor: agregação (valorTotal, quantidade)
     * 
     * Em um cenário real, este estado seria persistido em RocksDB ou banco de dados.
     */
    private AgregacaoPorHoraVO agregacaoAtual = null;

    private final AgregadorService agregadorService;

    public AgregadorListener(AgregadorService agregadorService) {
        this.agregadorService = agregadorService;
    }

    /**
     * Consome eventos do tópico de Pix realizados.
     * 
     * Grupo de consumidores: agregador-pix-por-hora (diferente da etapa 1)
     * Relógio escolhido: event time (campo liquidadoEm)
     * Janela: 1 hora, alinhada por tempo UTC
     * 
     * @param registro o evento de Pix realizado
     */
    @KafkaListener(topics = "${pix.topico}", groupId = "${pix.agregador.group-id}")
    public void processar(ConsumerRecord<String, PixRealizadoEvent> registro) {
        PixRealizadoEvent evento = registro.value();

        if (evento == null || evento.getLiquidadoEm() == null) {
            log.warn("Evento com valor ou liquidadoEm nulo: {}", evento);
            return;
        }

        // Calcula a janela de 1 hora para este evento
        AgregadorService.JanelaDeHora janela = 
                agregadorService.calcularJanela(evento.getLiquidadoEm());

        // Verifica se o evento está em uma janela diferente da anterior
        boolean ehJanelaNova = agregacaoAtual == null ||
                !agregacaoAtual.getInicioJanela().equals(janela.inicio);

        // Se mudou de janela, registra a anterior e inicia uma nova
        if (ehJanelaNova && agregacaoAtual != null) {
            log.info("Janela encerrada: {} | Total: R$ {} | Quantidade: {} Pix",
                    agregacaoAtual.getInicioJanela(),
                    agregacaoAtual.getValorTotal(),
                    agregacaoAtual.getQuantidade());
        }

        // Agrega o novo evento
        if (ehJanelaNova) {
            agregacaoAtual = agregadorService.iniciar(evento);
        } else {
            agregacaoAtual = agregadorService.agregar(agregacaoAtual, evento);
        }

        // Log da agregação atual (mostra andamento)
        log.info("Agregando Pix {} | Janela: {} | "
                + "Valor acumulado: R$ {} | Quantidade: {}",
                evento.getIdTransacaoPix(),
                janela,
                agregacaoAtual.getValorTotal(),
                agregacaoAtual.getQuantidade());
    }
}
