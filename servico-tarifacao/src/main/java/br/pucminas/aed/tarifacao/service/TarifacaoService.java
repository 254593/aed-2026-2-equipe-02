package br.pucminas.aed.tarifacao.service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.pucminas.aed.tarifacao.domain.DecisaoDeTarifacaoVO;
import br.pucminas.aed.tarifacao.domain.OfertaVO;
import br.pucminas.aed.tarifacao.domain.PixRealizadoEvent;

/**
 * O ponto de decisao do dominio — a PoliticaDeTarifacao do ADR-002.
 *
 * Para cada Pix liquidado, decide entre QUATRO saidas:
 *
 *   SEM_CONTRATO     nao ha oferta vigente na competencia -> nao cobra
 *   ISENTO_FRANQUIA  coube na franquia mensal do plano    -> nao cobra
 *   TARIFADO         acima da franquia                    -> cobra pela faixa
 *   TETO_ATINGIDO    o teto de gasto do mes ja foi atingido -> nao cobra
 *
 * Aprova, recusa e limita: e decisao, nao calculo. As tres primeiras vem da
 * secao Decisao do ADR; a quarta e o "limita".
 *
 * QUATRO DECISOES QUE VALEM A LEITURA
 *
 * 1. @Transactional esta AQUI, e nao no listener. O registro da chave de
 *    deduplicacao e o efeito de negocio precisam estar na MESMA transacao. Em
 *    transacoes separadas existe uma janela em que o processo morre entre as
 *    duas — e o evento volta a ser processado, que e exatamente o que se
 *    queria evitar. Como o commit termina aqui, o ack la no listener so
 *    acontece depois dele.
 *
 * 2. A competencia sai do ocorridoEm do EVENTO, nunca de now(). Um replay do
 *    topico feito em outubro tem que continuar tarifando contra agosto. E o
 *    mesmo motivo pelo qual a OFERTA tambem e buscada por competencia, e nao
 *    "a vigente hoje": se qualquer um dos dois olhasse o relogio, o
 *    reprocessamento produziria um valor diferente do original e o fechamento
 *    mensal deixaria de ser reproduzivel.
 *
 * 3. Nao ha plano padrao para cliente sem contrato. Cobrar sem contrato
 *    vigente e cobranca indevida, com exposicao a devolucao em dobro (CDC,
 *    art. 42, paragrafo unico) — o ADR-002 declara isso como a regra que
 *    sustenta o recorte. O Pix vira linha mesmo assim, com situacao
 *    SEM_CONTRATO: o fato aconteceu e precisa aparecer na auditoria; o que nao
 *    acontece e a cobranca.
 *
 * 4. A contagem da franquia e um read-then-write, e quem a serializa e a CHAVE
 *    DE PARTICAO, nao um lock no banco. Como o servico-pix publica com
 *    clienteId como chave, todos os Pix de um cliente caem na mesma particao e
 *    sao processados em serie por um unico consumidor do grupo. Se a chave
 *    fosse o pixId, dois Pix do mesmo cliente cairiam em particoes diferentes,
 *    seriam processados em paralelo, os dois leriam "4 usados" e os dois
 *    sairiam isentos: a franquia estouraria. O mesmo raciocinio vale para o
 *    teto mensal, que tambem le antes de escrever.
 *
 *    O custo aceito e o outro lado do mesmo eixo: um cliente de volume muito
 *    alto concentra carga numa particao (hot partition). Se isso aparecer, a
 *    saida e rever a granularidade da chave — nao aumentar particoes, porque
 *    isso rebate o hash e quebra a ordem das chaves ja existentes.
 */
@Service
public class TarifacaoService {

    private static final Logger log = LoggerFactory.getLogger(TarifacaoService.class);

    private final TarifacaoRepository repositorio;

    public TarifacaoService(TarifacaoRepository repositorio) {
        this.repositorio = repositorio;
    }

    /**
     * @return true se a tarifa foi registrada; false se o evento era reentrega.
     */
    @Transactional
    public boolean processar(String eventoId, PixRealizadoEvent evento) {

        if (!repositorio.registrarEventoSeNovo(eventoId)) {
            log.info("evento {} JA PROCESSADO, descartando em silencio", eventoId);
            return false;
        }

        String competencia = competenciaDe(evento);
        DecisaoDeTarifacaoVO decisao = decidir(evento, competencia);

        repositorio.registrarTarifa(evento, competencia, decisao);

        log.info("pix processado  evento={}  cliente={}  competencia={}  situacao={}  valor={}",
                eventoId, evento.getClienteId(), competencia,
                decisao.getSituacao(), decisao.getValor());
        return true;
    }

    /**
     * A politica, na ordem em que as perguntas precisam ser feitas.
     *
     * A ordem importa e nao e arbitraria: sem contrato nao se pergunta pela
     * franquia, e o teto so faz sentido depois de saber que haveria cobranca.
     * Inverter a segunda e a terceira faria um Pix isento "atingir o teto" e
     * sair com a situacao errada no extrato.
     */
    private DecisaoDeTarifacaoVO decidir(PixRealizadoEvent evento, String competencia) {

        Optional<OfertaVO> vigente =
                repositorio.buscarOfertaVigente(evento.getClienteId(), competencia);

        if (vigente.isEmpty()) {
            return DecisaoDeTarifacaoVO.semContrato();
        }
        OfertaVO oferta = vigente.get();

        long consumidos = repositorio.contarFranquiaConsumida(evento.getClienteId(), competencia);
        if (consumidos < oferta.getPixGratuitosMes()) {
            return DecisaoDeTarifacaoVO.isentoPorFranquia();
        }

        if (tetoJaAtingido(oferta, evento.getClienteId(), competencia)) {
            return DecisaoDeTarifacaoVO.tetoAtingido();
        }

        return DecisaoDeTarifacaoVO.tarifado(oferta.tarifaPara(evento.getValor()));
    }

    /**
     * O teto limita o GASTO do mes, em reais — nao a quantidade de Pix. Compara
     * o que ja foi cobrado na competencia com o teto do plano.
     *
     * A comparacao e >= : atingir o teto ja basta para parar de cobrar. E
     * compareTo, nao equals, porque BigDecimal distingue 1.98 de 1.980 pela
     * escala e a igualdade por equals falharia conforme a escala devolvida pelo
     * banco.
     */
    private boolean tetoJaAtingido(OfertaVO oferta, String clienteId, String competencia) {
        if (!oferta.temTetoMensal()) {
            return false;
        }
        BigDecimal jaCobrado = repositorio.totalTarifadoNaCompetencia(clienteId, competencia);
        return jaCobrado.compareTo(oferta.getTetoMensal()) >= 0;
    }

    /**
     * Competencia no formato YYYY-MM, em UTC. O fuso e fixado de proposito: se
     * dependesse do fuso da maquina, um Pix da virada do mes cairia em
     * competencias diferentes conforme onde o consumidor estivesse rodando.
     */
    private String competenciaDe(PixRealizadoEvent evento) {
        return YearMonth.from(evento.getOcorridoEm().atZone(ZoneOffset.UTC)).toString();
    }
}
