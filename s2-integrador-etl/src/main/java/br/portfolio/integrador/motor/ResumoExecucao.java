package br.portfolio.integrador.motor;

public record ResumoExecucao(
        long execucao,
        String grupo,
        String status,          // OK | PARCIAL (algum item com erro) | ERRO (grupo interrompido)
        int itensOk,
        int itensComErro,
        int itensIgnorados,     // já integrados anteriormente
        long duracaoMs,
        String mensagem) {
}
