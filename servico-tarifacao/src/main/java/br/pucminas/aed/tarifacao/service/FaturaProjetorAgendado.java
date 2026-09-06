package br.pucminas.aed.tarifacao.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O gatilho do projetor — e a unica razao de ele existir separado do
 * FaturaProjetor e poder ser DESLIGADO.
 *
 * Nos testes, o avanco e chamado a mao: um agendamento correndo por fora
 * projetaria no meio do cenario e o teste passaria a depender de quem chegou
 * primeiro. Um teste de reconstrucao que precise torcer para o agendador nao
 * acordar nao prova nada.
 *
 * fixedDelay, e nao fixedRate: a contagem so comeca quando a execucao anterior
 * termina, entao nao ha duas projecoes sobrepostas. E o que permite ao
 * FaturaProjetor assumir um escritor por vez.
 */
@Component
@ConditionalOnProperty(name = "tarifacao.projecao.agendada",
                       havingValue = "true", matchIfMissing = true)
public class FaturaProjetorAgendado {

    private final FaturaProjetor projetor;

    public FaturaProjetorAgendado(FaturaProjetor projetor) {
        this.projetor = projetor;
    }

    @Scheduled(fixedDelayString = "${tarifacao.projecao.intervalo-ms:2000}",
               initialDelayString = "${tarifacao.projecao.atraso-inicial-ms:2000}")
    public void projetar() {
        projetor.avancar();
    }
}
