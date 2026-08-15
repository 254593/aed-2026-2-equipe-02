package br.pucminas.aed.tarifacao.domain;

/**
 * Qual das quatro saidas da politica de tarifacao produziu uma linha de tarifa.
 *
 * POR QUE ISTO EXISTE, EM VEZ DE SO O VALOR
 *
 * Tres das quatro saidas valem 0.00 e significam coisas diferentes: um Pix
 * isento porque coube na franquia, um Pix de empresa sem contrato vigente e um
 * Pix que nao foi cobrado porque o teto mensal ja tinha sido atingido. Guardar
 * apenas o valor tornaria o extrato ambiguo justamente onde a auditoria e a
 * contestacao comercial precisam de clareza — e o ADR-002 elege a auditoria
 * como o quarto criterio do dominio.
 *
 * A distincao tambem e operacional, nao so documental: {@link #consomeFranquia()}
 * decide quais linhas entram na contagem do mes. Um Pix sem contrato nao gasta
 * uma cota de franquia que o cliente nao contratou.
 *
 * Sufixo VO porque e exatamente isso: valor sem identidade propria, imutavel, e
 * que so faz sentido junto da tarifa que o carrega.
 */
public enum SituacaoDaTarifaVO {

    /**
     * Nao havia oferta vigente na competencia do Pix. Nao se cobra: cobrar sem
     * contrato e cobranca indevida, com exposicao a devolucao em dobro (CDC,
     * art. 42, paragrafo unico). Ver ADR-002, secao Decisao.
     */
    SEM_CONTRATO(false),

    /** Coube na franquia mensal do plano. Consome uma cota do mes. */
    ISENTO_FRANQUIA(true),

    /** Acima da franquia: cobrado pela faixa de valor do plano. */
    TARIFADO(true),

    /** O total ja cobrado na competencia atingiu o teto do plano. Nao se cobra. */
    TETO_ATINGIDO(false);

    private final boolean consomeFranquia;

    private SituacaoDaTarifaVO(boolean consomeFranquia) {
        this.consomeFranquia = consomeFranquia;
    }

    /**
     * Se esta linha conta contra a franquia mensal do cliente.
     *
     * TARIFADO consome porque a franquia ja tinha se esgotado quando ele
     * aconteceu — manter a contagem crescendo e o que impede um Pix posterior
     * de ser lido como se ainda houvesse cota livre.
     */
    public boolean consomeFranquia() {
        return consomeFranquia;
    }
}
