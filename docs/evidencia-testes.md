# Evidencia de testes

Execucao realizada em 14/09/2026, com Java 21 e Maven 3.9.10.

## Resultado

| Servico | Comando | Testes | Falhas | Erros |
|---|---|---:|---:|---:|
| `servico-pix` | `mvn -f servico-pix/pom.xml test` | 16 | 0 | 0 |
| `servico-agregador-pix` | `mvn -f servico-agregador-pix/pom.xml test` | 16 | 0 | 0 |
| `servico-tarifacao` | `mvn -f servico-tarifacao/pom.xml test` | 44 | 0 | 0 |
| **Total** |  | **76** | **0** | **0** |

## Cobertura funcional observada

- Publicacao do Pix com cabecalhos CloudEvents e chave de particao por empresa.
- Agregacao por hora, eventos atrasados e deduplicacao.
- Idempotencia da tarifacao e replay da projecao da fatura.
- Retry limitado, DLQ e desserializacao de carga invalida.
- Publicacao e consumo do evento `PixEstornado`.
- Compensacao append-only, sem `UPDATE` ou `DELETE` da tarifa original.
- Reentrega do mesmo estorno sem duplicar o ajuste.
- Fatura com total tarifado, total estornado e total liquido.

## Comando para reproduzir

```powershell
mvn -f servico-pix/pom.xml test
mvn -f servico-agregador-pix/pom.xml test
mvn -f servico-tarifacao/pom.xml test
```

Os relatorios detalhados ficam em `target/surefire-reports/` durante a execucao, mas nao fazem
parte da evidencia versionada.
