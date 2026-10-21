package br.portfolio.integrador.motor;

import java.util.List;
import java.util.Map;

/** Grupo de processos: variáveis globais + sequência de etapas (equivalente ao "grupo de processos" do Sysmo Integrador). */
public record DefinicaoGrupo(
        String nome,
        String descricao,
        boolean agendado,
        Map<String, Object> variaveis,
        List<DefinicaoEtapa> etapas) {

    public DefinicaoGrupo {
        variaveis = variaveis == null ? Map.of() : Map.copyOf(variaveis);
        etapas = etapas == null ? List.of() : List.copyOf(etapas);
    }
}
