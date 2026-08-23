package br.pucminas.aed.agregador.service;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import br.pucminas.aed.agregador.domain.AgregacaoPorHoraVO;
import br.pucminas.aed.agregador.domain.JanelaDeHoraVO;
import br.pucminas.aed.agregador.domain.PixRealizadoEvent;

/**
 * Responde "quanto foi liquidado em Pix por hora?" — agregando por EVENT TIME.
 *
 * TRES DECISOES QUE VALEM A LEITURA
 *
 * 1. O RELOGIO E O liquidadoEm DO EVENTO, nao a hora em que a mensagem chegou.
 *    Um Pix liquidado as 14:35 conta na janela das 14h mesmo que so chegue aqui
 *    as 15:50. E o que torna a agregacao reproduzivel: reprocessar o topico
 *    inteiro amanha produz exatamente os mesmos numeros, porque o instante que
 *    decide viaja dentro do evento e nao muda. Com processing time o resultado
 *    dependeria de quando o consumidor rodou, e a mesma pergunta teria
 *    respostas diferentes a cada execucao.
 *
 * 2. UM MAPA DE JANELAS, e nao uma janela corrente. Guardar so a ultima janela
 *    parece suficiente enquanto os eventos chegam em ordem — e nao chegam. O
 *    topico tem tres particoes, varias empresas publicam ao mesmo tempo, e
 *    eventos de horas diferentes se intercalam o tempo todo. Com uma janela so,
 *    cada troca de hora descartaria o acumulado e um retardatario reiniciaria a
 *    contagem do zero. O mapa e o que permite ao retardatario SOMAR na janela
 *    dele, que e o comportamento que o docs/entregas/aula-03.md descreve.
 *
 * 3. ConcurrentHashMap.compute E ATOMICO POR CHAVE. O container Kafka pode
 *    subir mais de uma thread de consumo, e ler-somar-gravar em tres passos
 *    perderia atualizacoes. compute faz os tres sob o lock do bucket.
 *
 * O estado e em memoria, e some no reinicio. E aceitavel porque o log e a fonte
 * da verdade: basta reprocessar o topico do inicio para reconstruir tudo — e,
 * por ser event time, reconstruir da o mesmo resultado. Persistir o estado seria
 * uma otimizacao de tempo de recuperacao, nao de correcao.
 */
@Service
public class AgregadorService {

    private final Map<Instant, AgregacaoPorHoraVO> janelas = new ConcurrentHashMap<Instant, AgregacaoPorHoraVO>();

    /**
     * Contabiliza um Pix na janela do proprio liquidadoEm.
     *
     * @return a agregacao da janela DEPOIS de somar este Pix.
     */
    public AgregacaoPorHoraVO registrar(PixRealizadoEvent evento) {
        JanelaDeHoraVO janela = JanelaDeHoraVO.de(evento.getLiquidadoEm());

        return janelas.compute(janela.getInicio(), (chave, atual) -> {
            AgregacaoPorHoraVO base = atual == null ? AgregacaoPorHoraVO.vazia(janela) : atual;
            return base.somar(evento.getValor());
        });
    }

    /** Todas as janelas conhecidas, da mais antiga para a mais recente. */
    public Map<Instant, AgregacaoPorHoraVO> panorama() {
        return Collections.unmodifiableMap(new TreeMap<Instant, AgregacaoPorHoraVO>(janelas));
    }

    /** A agregacao de uma janela, ou a janela vazia se nada caiu nela ainda. */
    public AgregacaoPorHoraVO da(Instant instante) {
        JanelaDeHoraVO janela = JanelaDeHoraVO.de(instante);
        AgregacaoPorHoraVO atual = janelas.get(janela.getInicio());
        return atual == null ? AgregacaoPorHoraVO.vazia(janela) : atual;
    }

    /** Usado pelos testes para partir de um estado limpo. */
    public void limpar() {
        janelas.clear();
    }
}
