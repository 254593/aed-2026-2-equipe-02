package br.pucminas.aed.agregador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class AgregadorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgregadorApplication.class, args);
    }
}
