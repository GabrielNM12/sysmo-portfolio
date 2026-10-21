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
 * SALVAR CLIENTE: cria ou atualiza o transacionador pelo CPF (cliente e fornecedor são a mesma
 * entidade no ERP) e acrescenta o endereço de entrega se ainda não existir.
 */
@ApplicationScoped
public class SalvarCliente implements Etapa {

    @Inject
    AgroalDataSource ds;

    @Override
    public String tipo() {
        return "salvar-cliente";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        Map<String, Object> cli = (Map<String, Object>) ctx.obter("cliente");
        Map<String, Object> end = (Map<String, Object>) ctx.obter("endereco");
        String cpf = (String) cli.get("cpf");
        String acao;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                Optional<Long> existente = Sql.umLong(c, "select id from erp.transacionador where cpf_cnpj = ? for update", cpf);
                long id;
                if (existente.isPresent()) {
                    id = existente.get();
                    try (var ps = Sql.preparar(c, """
                            update erp.transacionador
                               set is_cliente = true,
                                   email = coalesce(email, ?),
                                   telefone = coalesce(nullif(telefone, ''), ?)
                             where id = ?""", cli.get("email"), cli.get("telefone"), id)) {
                        ps.executeUpdate();
                    }
                    acao = "cliente existente " + id;
                } else {
                    id = Sql.umLong(c, """
                            insert into erp.transacionador (tipo_pessoa, cpf_cnpj, nome, is_cliente, email, telefone, aceita_marketing)
                            values ('F', ?, ?, true, ?, ?, ?) returning id""",
                            cpf, cli.get("nome"), cli.get("email"), cli.get("telefone"), cli.get("aceitaMarketing")).orElseThrow();
                    acao = "cliente novo " + id;
                }
                if (end != null) {
                    boolean temEndereco = Sql.umLong(c, """
                            select 1 from erp.transacionador_endereco
                             where transacionador_id = ? and cep = ? and numero = ?""", id, end.get("cep"), end.get("numero")).isPresent();
                    if (!temEndereco) {
                        try (var ps = Sql.preparar(c, """
                                insert into erp.transacionador_endereco
                                       (transacionador_id, tipo, cep, logradouro, numero, complemento, bairro, cidade, uf)
                                values (?, 'ENTREGA', ?, ?, ?, ?, ?, ?, ?)""",
                                id, end.get("cep"), end.get("logradouro"), end.get("numero"), end.get("complemento"),
                                end.get("bairro"), end.get("cidade"), end.get("uf"))) {
                            ps.executeUpdate();
                        }
                        acao += " + endereço de entrega";
                    }
                }
                c.commit();
                ctx.definir("transacionador_id", id);
                return acao;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }
}
