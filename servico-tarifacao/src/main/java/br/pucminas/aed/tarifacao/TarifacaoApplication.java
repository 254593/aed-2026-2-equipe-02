package br.pucminas.aed.tarifacao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Servico de tarifacao de Pix da Equipe 02.
 *
 * Consome o fato "Pix realizado" do topico e decide, para cada Pix, se ele
 * cabe na franquia mensal da empresa ou se deve ser tarifado.
 *
 * Nao expoe HTTP: um consumidor de eventos nao precisa de porta. Ele acorda
 * quando chega evento, e so.
 *
 * @EnableScheduling existe para o projetor da fatura (ADR-005), que roda fora
 * da transacao de decisao. A projecao e assincrona de proposito: fosse escrita
 * no mesmo commit da tarifa, ela deixaria de ser derivada e passaria a ser
 * mantida pelo caminho de escrita.
 */
@SpringBootApplication
@EnableScheduling
public class TarifacaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TarifacaoApplication.class, args);
    }
}
