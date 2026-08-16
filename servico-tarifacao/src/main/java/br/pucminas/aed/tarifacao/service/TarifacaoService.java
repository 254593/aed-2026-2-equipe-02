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
 * O ponto de decisao do dominio — a PoliticaDeTarifacao do ADR-002, com o
 * algoritmo detalhado em docs/regra-de-tarifacao.md.
 *
 * Para cada Pix liquidado, decide entre CINCO saidas:
 *
 *   SEM_CONTRATO   nao ha oferta vigente na competencia   -> nao cobra
 *   FRANQUIA       coube na franquia mensal do plano      -> nao cobra
 *   FAIXA          acima da franquia                      -> cobra a faixa
 *   TETO_PARCIAL   a faixa nao cabe no espaco do teto     -> cobra o que cabe
 *   TETO_ATINGIDO  o teto do mes ja estava completo       -> nao cobra
 *
 * Aprova, recusa e limita: e decisao, nao calculo. Quatro delas vem da
 * especificacao da regra; SEM_CONTRATO vem do ADR-002, e cobre o caso que a
 * especificacao nao trata.
 *
 * A DECISAO E SOBRE O ACUMULADO, NAO SOBRE O PAYLOAD. O que decide nao e o
 * evento que chegou, e sim quanta franquia a empresa ja gastou e quanto ja foi
 * cobrado dela na competencia. E isso que torna a tarifacao uma decisao sobre
 * agregado — e o que obriga a serializar o processamento por empresa.
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
 * 2. A competencia sai do liquidadoEm do EVENTO, nunca de now(). Um replay do
 *    topico feito em outubro tem que continuar tarifando contra agosto. E o
 *    mesmo motivo pelo qual a OFERTA tambem e buscada por competencia, e nao
 *    "a vigente hoje": se qualquer um dos dois olhasse o relogio, o
 *    reprocessamento produziria um valor diferente do original e o fechamento
 *    mensal deixaria de ser reproduzivel.
 *
 * 3. Nao ha plano padrao para empresa sem contrato. Cobrar sem contrato
 *    vigente e cobranca indevida, com exposicao a devolucao em dobro (CDC,
 *    art. 42, paragrafo unico) — o ADR-002 declara isso como a regra que
 *    sustenta o recorte. O Pix vira linha mesmo assim, com situacao
 *    SEM_CONTRATO: o fato aconteceu e precisa aparecer na auditoria; o que nao
 *    acontece e a cobranca.
 *
 * 4. A contagem da franquia e um read-then-write, e quem a serializa e a CHAVE
 *    DE PARTICAO, nao um lock no banco. Como o servico-pix publica com
 *    idEmpresa como chave, todos os Pix de uma empresa caem na mesma particao
 *    e sao processados em serie por um unico consumidor do grupo. Se a chave
 *    fosse o idTransacaoPix, dois Pix da mesma empresa cairiam em particoes
 *    diferentes, seriam processados em paralelo, os dois leriam "4 usados" e
 *    os dois sairiam isentos: a franquia estouraria. O mesmo raciocinio vale
 *    para o teto mensal, que tambem le antes de escrever.
 *
 *    O custo aceito e o outro lado do mesmo eixo: uma empresa de volume muito
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

        log.info("pix processado  evento={}  empresa={}  competencia={}  situacao={}  valor={}",
                eventoId, evento.getIdEmpresa(), competencia,
                decisao.getSituacao(), decisao.getValor());
        return true;
    }

    /**
     * A politica, na ordem em que a especificacao da regra manda perguntar.
     *
     *   1. ha oferta vigente na competencia?  nao -> SEM_CONTRATO
     *   2. franquia consumida < franquia do plano?  sim -> FRANQUIA
     *   3. tarifa = faixa(valor do Pix)
     *   4. ja tarifado >= teto?  sim -> TETO_ATINGIDO
     *   5. ja tarifado + tarifa > teto?  sim -> TETO_PARCIAL (o que couber)
     *                                    nao -> FAIXA (integral)
     *
     * A ordem nao e arbitraria. Sem contrato nao se pergunta pela franquia. A
     * FRANQUIA E SEMPRE CONSUMIDA PRIMEIRO, antes de qualquer verificacao de
     * teto: uma empresa de alto volume termina o mes com as 10 de 10 isencoes
     * usadas independentemente do teto, e e isso que mantem o relatorio de
     * fechamento fiel. E o teto so e consultado depois de calcular a tarifa,
     * porque o passo 5 precisa saber quanto caberia cobrar.
     */
    private DecisaoDeTarifacaoVO decidir(PixRealizadoEvent evento, String competencia) {

        Optional<OfertaVO> vigente =
                repositorio.buscarOfertaVigente(evento.getIdEmpresa(), competencia);

        if (vigente.isEmpty()) {
            return DecisaoDeTarifacaoVO.semContrato();
        }
        OfertaVO oferta = vigente.get();

        long consumidas = repositorio.contarFranquiaConsumida(evento.getIdEmpresa(), competencia);
        if (consumidas < oferta.getPixGratuitosMes()) {
            return DecisaoDeTarifacaoVO.isentoPorFranquia();
        }

        BigDecimal tarifa = oferta.tarifaPara(evento.getValor());

        if (!oferta.temTetoMensal()) {
            return DecisaoDeTarifacaoVO.tarifadoPelaFaixa(tarifa);
        }

        return aplicarTeto(oferta, evento.getIdEmpresa(), competencia, tarifa);
    }

    /**
     * O teto limita o GASTO do mes, em reais — nao a quantidade de Pix.
     *
     * O ESTOURO E COBRADO PARCIALMENTE, ate completar exatamente o teto. A
     * alternativa — nao cobrar nada quando a tarifa nao cabe — exigiria um
     * sinalizador de "teto atingido" separado do acumulador, que precisaria ser
     * ressincronizado a cada estorno. Com a cobranca parcial, "teto atingido" e
     * derivavel do proprio acumulado (jaCobrado >= teto) e o estado se recompoe
     * sozinho quando uma compensacao devolve espaco.
     *
     * E e o que sustenta a invariante valorTarifadoNaCompetencia <= teto: sem o
     * parcial, uma tarifa de faixa alta chegando com pouco espaco restante
     * faria o mes fechar acima do valor contratado.
     *
     * Todas as comparacoes por compareTo, nunca equals: BigDecimal distingue
     * 2000 de 2000.00 pela escala, e a igualdade por equals falharia conforme a
     * escala que o banco devolvesse.
     */
    private DecisaoDeTarifacaoVO aplicarTeto(OfertaVO oferta, String idEmpresa,
            String competencia, BigDecimal tarifa) {

        BigDecimal teto = oferta.getTetoMensal();
        BigDecimal jaCobrado = repositorio.totalTarifadoNaCompetencia(idEmpresa, competencia);

        if (jaCobrado.compareTo(teto) >= 0) {
            return DecisaoDeTarifacaoVO.tetoAtingido();
        }

        if (jaCobrado.add(tarifa).compareTo(teto) > 0) {
            return DecisaoDeTarifacaoVO.tetoParcial(teto.subtract(jaCobrado));
        }

        return DecisaoDeTarifacaoVO.tarifadoPelaFaixa(tarifa);
    }

    /**
     * Competencia no formato YYYY-MM, em UTC. O fuso e fixado de proposito: se
     * dependesse do fuso da maquina, um Pix da virada do mes cairia em
     * competencias diferentes conforme onde o consumidor estivesse rodando.
     */
    private String competenciaDe(PixRealizadoEvent evento) {
        return YearMonth.from(evento.getLiquidadoEm().atZone(ZoneOffset.UTC)).toString();
    }
}
