package br.portfolio.integrador.etapas;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.Etapa;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/** CONFIRMAR INTEGRAÇÃO: devolve à plataforma o número do pedido no ERP; sem isso ele seria listado de novo. */
@ApplicationScoped
public class ConfirmarIntegracao implements Etapa {

    @Inject
    ClienteApi api;

    @Override
    public String tipo() {
        return "confirmar-integracao";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        Object numero = ctx.obter("numero_erp");
        api.post(ctx.exigirTexto("ws.url") + "/v1/pedidos/" + ctx.exigirTexto("pedido.id") + "/integracao",
                ctx.exigirTexto("token"), Map.of("numeroErp", String.valueOf(numero)), Void.class);
        return "confirmado na plataforma com o número ERP " + numero;
    }
}
