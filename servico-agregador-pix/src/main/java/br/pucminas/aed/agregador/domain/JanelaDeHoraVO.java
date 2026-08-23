package br.pucminas.aed.agregador.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Uma janela de uma hora, ALINHADA POR TEMPO — 14:00, 15:00, 16:00 — e nunca
 * pela hora em que o processo subiu.
 *
 * O alinhamento e o que torna a agregacao reproduzivel: um evento liquidado as
 * 14:35 cai na janela [14:00, 15:00) hoje, amanha, e num replay feito daqui a
 * seis meses. Se a janela comecasse quando o agregador subiu, o mesmo evento
 * cairia em janelas diferentes a cada execucao, e a pergunta "quanto foi
 * liquidado das 14 as 15" deixaria de ter uma resposta so.
 *
 * Fim EXCLUSIVO: um Pix de exatamente 15:00:00 pertence a janela das 15h, nao a
 * das 14h. Sem isso, o instante da virada contaria duas vezes.
 *
 * UTC fixo, e nao o fuso da maquina: o mesmo evento tem de cair na mesma janela
 * independentemente de onde o agregador roda.
 */
public final class JanelaDeHoraVO {

    private final Instant inicio;
    private final Instant fim;

    private JanelaDeHoraVO(Instant inicio, Instant fim) {
        this.inicio = inicio;
        this.fim = fim;
    }

    /** A janela de uma hora que contem este instante. */
    public static JanelaDeHoraVO de(Instant instante) {
        Objects.requireNonNull(instante, "instante e obrigatorio");
        Instant inicio = instante.atZone(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .toInstant();
        return new JanelaDeHoraVO(inicio, inicio.plus(1, ChronoUnit.HOURS));
    }

    public Instant getInicio() {
        return inicio;
    }

    public Instant getFim() {
        return fim;
    }

    /** Fim exclusivo: [inicio, fim). */
    public boolean contem(Instant instante) {
        return !instante.isBefore(inicio) && instante.isBefore(fim);
    }

    @Override
    public boolean equals(Object outro) {
        if (this == outro) {
            return true;
        }
        if (!(outro instanceof JanelaDeHoraVO)) {
            return false;
        }
        return inicio.equals(((JanelaDeHoraVO) outro).inicio);
    }

    @Override
    public int hashCode() {
        return inicio.hashCode();
    }

    @Override
    public String toString() {
        return "[" + inicio + ", " + fim + ")";
    }
}
