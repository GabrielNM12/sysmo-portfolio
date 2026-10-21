package br.portfolio.integrador.etapas;

import br.portfolio.integrador.banco.Documentos;
import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.Etapa;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.util.Map;

/** EXTRAIR LOJA: o pedido identifica a loja pelo CNPJ; o ERP trabalha com o código da empresa. */
@ApplicationScoped
public class ExtrairLoja implements Etapa {

    @Inject
    AgroalDataSource ds;

    @Override
    public String tipo() {
        return "extrair-loja";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        String cnpj = Documentos.digitos(ctx.exigirTexto("pedido.loja.cnpj"));
        if (!Documentos.cnpjValido(cnpj)) {
            throw new ErroDeNegocio("CNPJ da loja inválido: " + cnpj);
        }
        try (Connection c = ds.getConnection()) {
            long empresa = Sql.umLong(c, "select id from erp.empresa where cnpj = ? and tipo = 'LOJA' and ativa", cnpj)
                    .orElseThrow(() -> new ErroDeNegocio("nenhuma loja ativa com CNPJ " + cnpj));
            ctx.definir("empresa_pedido", empresa);
            return "CNPJ " + cnpj + " → empresa " + empresa;
        }
    }
}
