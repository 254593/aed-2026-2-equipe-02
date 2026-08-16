package br.pucminas.aed.tarifacao.domain;

/**
 * O MOTIVO que produziu uma linha de tarifa — qual saida da politica decidiu
 * aquele Pix.
 *
 * "O motivo nao e log: e dado do dominio." (regra-de-tarifacao.md). E ele que
 * identifica qual cobranca bateu o teto — a unica linha TETO_PARCIAL da
 * competencia —, permite responder "por que esta empresa pagou este valor" sem
 * recalcular nada, e distingue no fechamento os Pix gratuitos por beneficio
 * contratual dos gratuitos por limite atingido. Sao duas gratuidades com
 * significados comerciais opostos.
 *
 * Quatro destes cinco motivos estao na especificacao da regra. O quinto,
 * SEM_CONTRATO, vem do ADR-002 e cobre o caso que a especificacao nao trata:
 * nao haver oferta vigente na competencia.
 *
 * Sufixo VO porque e exatamente isso: valor sem identidade propria, imutavel, e
 * que so faz sentido junto da tarifa que o carrega.
 */
public enum SituacaoDaTarifaVO {

    /**
     * Nao havia oferta vigente na competencia do Pix. Nao se cobra: cobrar sem
     * contrato e cobranca indevida, com exposicao a devolucao em dobro (CDC,
     * art. 42, paragrafo unico). Ver ADR-002, secao Decisao.
     *
     * Nao confundir com o contrato inativo que o Faturamento recusa mais tarde:
     * aquele e detectado contra a fonte autoritativa, DEPOIS de tarifar, e
     * produz compensacao. Este e a ausencia de contrato na propria replica
     * local, percebida antes de decidir.
     */
    SEM_CONTRATO(false),

    /** Isento por haver unidade de franquia disponivel. */
    FRANQUIA(true),

    /** Tarifado pelo valor integral da faixa. */
    FAIXA(false),

    /** Tarifado por valor reduzido, ate completar exatamente o teto. */
    TETO_PARCIAL(false),

    /** Nao cobrado: o teto da competencia ja estava completo. */
    TETO_ATINGIDO(false);

    private final boolean consomeFranquia;

    private SituacaoDaTarifaVO(boolean consomeFranquia) {
        this.consomeFranquia = consomeFranquia;
    }

    /**
     * Se esta linha conta contra a franquia mensal da empresa.
     *
     * SO O PIX ISENTO CONSOME. Um Pix tarifado nao gasta unidade de franquia —
     * ele existe justamente porque nao havia mais nenhuma. Manter a contagem
     * crescendo depois do limite violaria a invariante da especificacao
     * (unidadesFranquiaConsumidas <= franquia do plano) e corromperia o
     * relatorio de fechamento, que le esse acumulado para dizer quantas
     * isencoes o contrato de fato concedeu.
     *
     * A decisao nao muda por isso: uma vez atingida a franquia, o contador para
     * no limite, e o limite nunca e menor que ele mesmo.
     */
    public boolean consomeFranquia() {
        return consomeFranquia;
    }
}
