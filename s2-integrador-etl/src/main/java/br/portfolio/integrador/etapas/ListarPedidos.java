package br.portfolio.integrador.etapas;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.Etapa;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * LISTAR PEDIDOS: busca só os pedidos com separação finalizada e ainda não integrados.
 * Pedido em separação ainda muda (item em falta, peso real de pesável), então não entra no ERP.
 */
@ApplicationScoped
public class ListarPedidos implements Etapa {

    @Inject
    ClienteApi api;

    @Override
    public String tipo() {
        return "listar-pedidos";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        String status = String.valueOf(p.getOrDefault("status", "SEPARACAO_FINALIZADA"));
        String url = ctx.exigirTexto("ws.url") + "/v1/pedidos?integrado=false&status="
                + URLEncoder.encode(status, StandardCharsets.UTF_8);
        List<?> pedidos = api.get(url, ctx.exigirTexto("token"), List.class);
        ctx.definirGlobal("pedidos", pedidos == null ? List.of() : pedidos);
        return (pedidos == null ? 0 : pedidos.size()) + " pedido(s) com status " + status;
    }
}
