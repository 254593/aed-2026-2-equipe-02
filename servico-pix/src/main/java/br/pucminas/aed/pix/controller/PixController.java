package br.pucminas.aed.pix.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.pucminas.aed.pix.domain.PixRealizadoEvent;
import br.pucminas.aed.pix.domain.RealizacaoPixVO;
import br.pucminas.aed.pix.service.PixService;

@RestController
@RequestMapping("/pix")
public class PixController {

    private final PixService pixService;

    public PixController(PixService pixService) {
        this.pixService = pixService;
    }

    @PostMapping("/realizados")
    public ResponseEntity<PixRealizadoEvent> realizar(@RequestBody RealizacaoPixVO realizacao) {
        PixRealizadoEvent evento = pixService.realizar(realizacao);
        return ResponseEntity.accepted().body(evento);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> tratarEntradaInvalida(
            IllegalArgumentException excecao) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("erro", excecao.getMessage()));
    }

    /**
     * Corpo que o Jackson nao consegue ler: campo desconhecido, data fora do
     * ISO-8601, JSON quebrado. Sem este tratador o cliente recebia um 400
     * generico do Spring, sem dizer o que estava errado — e foi assim que um
     * POST com {@code liquidadoEm}, antes de o campo existir, falhava em
     * silencio.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> tratarCorpoIlegivel(
            HttpMessageNotReadableException excecao) {
        Throwable causa = excecao.getMostSpecificCause();
        String motivo = causa.getMessage() == null ? causa.toString() : causa.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("erro", "corpo invalido: " + primeiraLinha(motivo)));
    }

    private static String primeiraLinha(String texto) {
        int quebra = texto.indexOf('\n');
        return quebra < 0 ? texto : texto.substring(0, quebra);
    }
}

