package br.pucminas.aed.tarifacao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Servico de tarifacao de Pix da Equipe 02.
 *
 * Consome o fato "Pix realizado" do topico e decide, para cada Pix, se ele
 * cabe na franquia mensal da empresa ou se deve ser tarifado.
 *
 * Nao expoe HTTP: um consumidor de eventos nao precisa de porta. Ele acorda
 * quando chega evento, e so.
 */
@SpringBootApplication
public class TarifacaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TarifacaoApplication.class, args);
    }
}
