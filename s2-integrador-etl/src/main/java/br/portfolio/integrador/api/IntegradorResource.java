package br.portfolio.integrador.api;

import br.portfolio.integrador.banco.LogEmBanco;
import br.portfolio.integrador.motor.CarregadorDeGrupos;
import br.portfolio.integrador.motor.DefinicaoEtapa;
import br.portfolio.integrador.motor.DefinicaoGrupo;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/integrador")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Integrador")
public class IntegradorResource {

    @Inject
    CarregadorDeGrupos grupos;

    @Inject
    Execucoes execucoes;

    @Inject
    LogEmBanco log;

    @GET
    @Path("/grupos")
    public List<Map<String, Object>> listarGrupos() {
        return grupos.todos().stream().map(IntegradorResource::descrever).toList();
    }

    @POST
    @Path("/grupos/{nome}/executar")
    public Response executar(@PathParam("nome") String nome) {
        DefinicaoGrupo grupo = grupos.obter(nome).orElseThrow(() -> new NotFoundException("Grupo " + nome + " não existe"));
        return execucoes.executar(grupo)
                .map(r -> Response.ok(r).build())
                .orElseGet(() -> Response.status(409).entity(Map.of("erro", "grupo " + nome + " já está em execução")).build());
    }

    @GET
    @Path("/execucoes")
    public List<Map<String, Object>> listarExecucoes(@QueryParam("limite") @DefaultValue("20") int limite) {
        return log.listar(Math.min(limite, 200));
    }

    @GET
    @Path("/execucoes/{id}")
    public Map<String, Object> detalhe(@PathParam("id") long id) {
        return log.detalhe(id).orElseThrow(() -> new NotFoundException("Execução " + id + " não existe"));
    }

    private static Map<String, Object> descrever(DefinicaoGrupo g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nome", g.nome());
        m.put("descricao", g.descricao());
        m.put("agendado", g.agendado());
        m.put("variaveis", g.variaveis());
        m.put("etapas", nomes(g.etapas()));
        return m;
    }

    private static List<Object> nomes(List<DefinicaoEtapa> etapas) {
        List<Object> lista = new ArrayList<>();
        for (DefinicaoEtapa e : etapas) {
            lista.add(e.ehLaco() ? Map.of("paraCada " + e.paraCada(), nomes(e.etapas())) : e.nome() + " (" + e.tipo() + ")");
        }
        return lista;
    }
}
