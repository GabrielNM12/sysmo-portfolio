package br.portfolio.integrador.motor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lê os grupos de processos em YAML e valida tudo na partida: erro de configuração não espera a madrugada. */
@Startup
@ApplicationScoped
public class CarregadorDeGrupos {

    private static final Logger LOG = Logger.getLogger(CarregadorDeGrupos.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private final Map<String, DefinicaoGrupo> grupos = new LinkedHashMap<>();

    @Inject
    public CarregadorDeGrupos(@ConfigProperty(name = "integrador.grupos") List<String> arquivos, RegistroDeEtapas registro) {
        for (String arquivo : arquivos) {
            DefinicaoGrupo grupo = ler(arquivo);
            validar(grupo, registro);
            grupos.put(grupo.nome(), grupo);
            LOG.infof("Grupo de processos %s carregado de %s", grupo.nome(), arquivo);
        }
    }

    public static DefinicaoGrupo ler(String recurso) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(recurso)) {
            if (in == null) {
                throw new IllegalStateException("Arquivo de grupo não encontrado no classpath: " + recurso);
            }
            return YAML.readValue(in, DefinicaoGrupo.class);
        } catch (IOException e) {
            throw new UncheckedIOException("YAML inválido em " + recurso + ": " + e.getMessage(), e);
        }
    }

    public static void validar(DefinicaoGrupo grupo, RegistroDeEtapas registro) {
        List<String> erros = new ArrayList<>();
        if (grupo.nome() == null || grupo.nome().isBlank()) {
            erros.add("grupo sem nome");
        }
        validarEtapas(grupo.etapas(), registro, erros, "");
        if (!erros.isEmpty()) {
            throw new IllegalStateException("Grupo " + grupo.nome() + " inválido: " + String.join("; ", erros));
        }
    }

    private static void validarEtapas(List<DefinicaoEtapa> etapas, RegistroDeEtapas registro, List<String> erros, String prefixo) {
        if (etapas.isEmpty()) {
            erros.add(prefixo + "nenhuma etapa");
        }
        for (DefinicaoEtapa e : etapas) {
            String nome = prefixo + (e.nome() == null ? "(sem nome)" : e.nome());
            if (e.nome() == null) {
                erros.add("etapa sem nome");
            }
            if (e.ehLaco()) {
                if (e.tipo() != null) {
                    erros.add(nome + ": laço não tem tipo");
                }
                validarEtapas(e.etapas(), registro, erros, nome + " > ");
            } else if (e.tipo() == null) {
                erros.add(nome + ": falta o tipo (ou paraCada)");
            } else if (!registro.tipos().contains(e.tipo())) {
                erros.add(nome + ": tipo '" + e.tipo() + "' não existe " + registro.tipos());
            }
        }
    }

    public Optional<DefinicaoGrupo> obter(String nome) {
        return Optional.ofNullable(grupos.get(nome));
    }

    public Collection<DefinicaoGrupo> todos() {
        return grupos.values();
    }
}
