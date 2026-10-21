package br.portfolio.integrador.motor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Catálogo dos componentes de etapa disponíveis, indexado pelo "tipo" usado no YAML. */
@ApplicationScoped
public class RegistroDeEtapas {

    private final Map<String, Etapa> porTipo = new TreeMap<>();

    @Inject
    public RegistroDeEtapas(@Any Instance<Etapa> etapas) {
        this(etapas.stream().toList());
    }

    public RegistroDeEtapas(Collection<? extends Etapa> etapas) {
        for (Etapa e : etapas) {
            Etapa anterior = porTipo.put(e.tipo(), e);
            if (anterior != null) {
                throw new IllegalStateException("Dois componentes com o tipo " + e.tipo());
            }
        }
    }

    public Etapa obter(String tipo) {
        Etapa etapa = porTipo.get(tipo);
        if (etapa == null) {
            throw new IllegalArgumentException("Tipo de etapa desconhecido: " + tipo + ". Disponíveis: " + porTipo.keySet());
        }
        return etapa;
    }

    public Set<String> tipos() {
        return porTipo.keySet();
    }
}
