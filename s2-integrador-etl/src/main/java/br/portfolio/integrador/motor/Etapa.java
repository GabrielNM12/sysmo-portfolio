package br.portfolio.integrador.motor;

import java.util.Map;

/**
 * Componente reutilizável de integração. Implementações são beans CDI identificados por {@link #tipo()}.
 * Lance {@link ErroDeNegocio} para falhas que não melhoram com nova tentativa (dado inválido) e
 * {@link ErroTransitorio} para falhas temporárias (timeout, HTTP 503).
 */
public interface Etapa {

    String tipo();

    /** @return mensagem curta para o log da execução (pode ser {@code null}) */
    String executar(Contexto contexto, Map<String, Object> parametros) throws Exception;
}
