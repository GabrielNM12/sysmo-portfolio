package br.portfolio.integrador.motor;

import java.util.List;
import java.util.Map;

/**
 * Uma etapa do grupo de processos, como declarada no YAML.
 * Ou é uma etapa simples ({@code tipo}), ou um laço ({@code paraCada} + {@code etapas}).
 */
public record DefinicaoEtapa(
        String nome,
        String tipo,
        Map<String, Object> parametros,
        Integer retentativas,
        Long intervaloMs,
        String executarSe,
        String paraCada,
        String como,
        List<DefinicaoEtapa> etapas) {

    public DefinicaoEtapa {
        parametros = parametros == null ? Map.of() : Map.copyOf(parametros);
        etapas = etapas == null ? List.of() : List.copyOf(etapas);
    }

    public boolean ehLaco() {
        return paraCada != null;
    }

    public int tentativasMaximas() {
        return 1 + (retentativas == null ? 0 : Math.max(0, retentativas));
    }

    public long intervaloBaseMs() {
        return intervaloMs == null ? 500 : intervaloMs;
    }

    public static DefinicaoEtapa simples(String nome, String tipo) {
        return new DefinicaoEtapa(nome, tipo, null, null, null, null, null, null, null);
    }
}
