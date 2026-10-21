package br.portfolio.integrador.etapas;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.Etapa;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;

/**
 * VERIFICAR DUPLICIDADE: se o pedido já está no ERP (execução anterior gravou mas não conseguiu
 * confirmar na API), não grava de novo; só refaz a confirmação. Garante efeito "exatamente uma vez".
 */
@ApplicationScoped
public class VerificarDuplicidade implements Etapa {

    @Inject
    AgroalDataSource ds;

    @Override
    public String tipo() {
        return "verificar-duplicidade";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        String origem = ctx.exigirTexto("origem");
        String numero = ctx.exigirTexto("pedido.id");
        try (Connection c = ds.getConnection()) {
            Optional<Long> existente = Sql.umLong(c,
                    "select id from erp.pedido_venda where origem = ? and numero_externo = ?", origem, numero);
            ctx.definir("ja_integrado", existente.isPresent());
            existente.ifPresent(id -> ctx.definir("numero_erp", id));
            return existente.map(id -> "já integrado como pedido " + id).orElse("pedido novo");
        }
    }
}
