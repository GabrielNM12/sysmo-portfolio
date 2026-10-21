package br.portfolio.integrador.etapas;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.Etapa;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/** LOGIN NA API: troca client_id/client_secret por um token Bearer (OAuth2 client credentials). */
@ApplicationScoped
public class AutenticarApi implements Etapa {

    @Inject
    ClienteApi api;

    @Override
    public String tipo() {
        return "autenticar-api";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        Map<?, ?> resp = api.post(ctx.exigirTexto("ws.url") + "/oauth/token", null, Map.of(
                "grant_type", "client_credentials",
                "client_id", ctx.exigirTexto("ws.clientId"),
                "client_secret", ctx.exigirTexto("ws.clientSecret")), Map.class);
        Object token = resp == null ? null : resp.get("access_token");
        if (token == null) {
            throw new ErroDeNegocio("API não devolveu access_token");
        }
        ctx.definirGlobal("token", token.toString());
        return "token obtido (expira em " + resp.get("expires_in") + " s)";
    }
}
