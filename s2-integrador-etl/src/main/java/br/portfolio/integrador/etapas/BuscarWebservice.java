package br.portfolio.integrador.etapas;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.Etapa;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/** BUSCAR WEBSERVICE: lê URL e credenciais cadastradas para a empresa (no S1 real: Sgr101 › Integrações › E-commerce). */
@ApplicationScoped
public class BuscarWebservice implements Etapa {

    @Inject
    AgroalDataSource ds;

    @Override
    public String tipo() {
        return "buscar-webservice";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        int empresa = Integer.parseInt(String.valueOf(p.get("empresa")));
        String nome = String.valueOf(p.get("nome"));
        try (Connection c = ds.getConnection();
             PreparedStatement ps = Sql.preparar(c, """
                     select url_base, client_id, client_secret from erp.integracao_webservice
                      where empresa_id = ? and nome = ? and ativo""", empresa, nome);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new ErroDeNegocio("webservice " + nome + " não cadastrado ou inativo para a empresa " + empresa);
            }
            ctx.definirGlobal("ws", Map.of(
                    "url", rs.getString(1).replaceAll("/+$", ""),
                    "clientId", rs.getString(2),
                    "clientSecret", rs.getString(3)));
            return nome + " → " + rs.getString(1);
        }
    }
}
