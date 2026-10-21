package br.portfolio.integrador.simulador;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.sql.SQLException;
import java.util.Map;

/** API simulada da plataforma de e-commerce (o "lado de lá" da integração). */
@Path("/sitemercado")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Simulador de e-commerce")
public class SimuladorResource {

    @Inject
    SimuladorSiteMercado sim;

    @ConfigProperty(name = "simulador.habilitado")
    boolean habilitado;

    @ConfigProperty(name = "simulador.client-id")
    String clientId;

    @ConfigProperty(name = "simulador.client-secret")
    String clientSecret;

    private Response bloqueio(String authorization, boolean exigeToken) {
        if (!habilitado) {
            return Response.status(404).build();
        }
        if (sim.deveFalhar()) {
            return Response.status(503).entity(Map.of("erro", "instabilidade simulada")).build();
        }
        if (exigeToken && !sim.tokenValido(authorization)) {
            return Response.status(401).entity(Map.of("erro", "token inválido")).build();
        }
        return null;
    }

    @POST
    @Path("/oauth/token")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response token(Map<String, String> corpo) {
        Response r = bloqueio(null, false);
        if (r != null) {
            return r;
        }
        if (!clientId.equals(corpo.get("client_id")) || !clientSecret.equals(corpo.get("client_secret"))) {
            return Response.status(401).entity(Map.of("erro", "credenciais inválidas")).build();
        }
        return Response.ok(Map.of("access_token", sim.emitirToken(), "token_type", "Bearer", "expires_in", 3600)).build();
    }

    @GET
    @Path("/v1/pedidos")
    public Response pedidos(@HeaderParam("Authorization") String auth, @QueryParam("status") String status,
                            @QueryParam("integrado") Boolean integrado) {
        Response r = bloqueio(auth, true);
        return r != null ? r : Response.ok(sim.listar(status, integrado)).build();
    }

    @POST
    @Path("/v1/pedidos/{id}/integracao")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response confirmar(@HeaderParam("Authorization") String auth, @PathParam("id") String id, Map<String, String> corpo) {
        Response r = bloqueio(auth, true);
        if (r != null) {
            return r;
        }
        return sim.confirmar(id, corpo.get("numeroErp")) ? Response.noContent().build() : Response.status(404).build();
    }

    // ---- controle do simulador (demonstração e testes) ----

    @POST
    @Path("/_simulador/pedidos")
    public Response gerar(@QueryParam("quantidade") @DefaultValue("3") int quantidade,
                          @QueryParam("cenario") @DefaultValue("NORMAL") SimuladorSiteMercado.Cenario cenario,
                          @QueryParam("empresa") @DefaultValue("2") int empresa) throws SQLException {
        if (!habilitado) {
            return Response.status(404).build();
        }
        return Response.status(201).entity(sim.gerar(quantidade, cenario, empresa)).build();
    }

    @POST
    @Path("/_simulador/falhas")
    public Response falhas(@QueryParam("quantidade") @DefaultValue("1") int quantidade) {
        sim.injetarFalhas(quantidade);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/_simulador/pedidos/{id}/integracao")
    public Response desconfirmar(@PathParam("id") String id) {
        return sim.desconfirmar(id) ? Response.noContent().build() : Response.status(404).build();
    }

    @GET
    @Path("/_simulador/pedidos")
    public Response estado() {
        return Response.ok(sim.todos()).build();
    }

    @DELETE
    @Path("/_simulador")
    public Response limpar() {
        sim.limpar();
        return Response.noContent().build();
    }
}
