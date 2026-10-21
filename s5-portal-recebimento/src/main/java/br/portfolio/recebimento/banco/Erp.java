package br.portfolio.recebimento.banco;

import br.portfolio.recebimento.dominio.PedidoCompra;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Leituras no schema do ERP com o usuário portal_s5 (só o que o portal precisa). */
@ApplicationScoped
public class Erp {

    @Inject
    AgroalDataSource ds;

    public record Fornecedor(long id, String cnpj, String nome) {
    }

    public Optional<Fornecedor> fornecedorPorCnpj(String cnpj) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, cpf_cnpj, nome from erp.transacionador where cpf_cnpj = ? and is_fornecedor")) {
            ps.setString(1, cnpj);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Fornecedor(rs.getLong(1), rs.getString(2), rs.getString(3))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Map<String, Object>> pedidosEmAberto(long fornecedorId) {
        String sql = """
                select pc.id, pc.data_emissao, pc.previsao_entrega, pc.status, e.id as empresa_id, e.fantasia as destino, e.tipo,
                       count(i.*) as itens, round(sum(i.quantidade_emb * i.custo_embalagem), 2) as valor,
                       bool_or(s.perecivel) as perecivel
                  from erp.pedido_compra pc
                  join erp.empresa e on e.id = pc.empresa_id
                  join erp.pedido_compra_item i on i.pedido_compra_id = pc.id
                  join erp.produto p on p.id = i.produto_id
                  join erp.secao s on s.id = p.secao_id
                 where pc.fornecedor_id = ? and pc.status in ('ABERTO', 'PARCIAL')
                 group by pc.id, e.id order by pc.previsao_entrega, pc.id""";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, fornecedorId);
            return linhas(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<PedidoCompra> pedido(int id) {
        String cab = """
                select pc.id, pc.empresa_id, e.cnpj, e.fantasia, e.uf, pc.fornecedor_id, t.cpf_cnpj, t.nome,
                       pc.data_emissao, pc.previsao_entrega, pc.status
                  from erp.pedido_compra pc
                  join erp.empresa e on e.id = pc.empresa_id
                  join erp.transacionador t on t.id = pc.fornecedor_id
                 where pc.id = ?""";
        String itens = """
                select i.sequencia, i.produto_id, p.descricao,
                       (select u.ean from erp.produto_embalagem u where u.produto_id = p.id and u.venda and u.ean is not null limit 1),
                       cx.ean, i.fator, i.quantidade_emb, i.quantidade_recebida, i.custo_embalagem, p.ncm, s.perecivel
                  from erp.pedido_compra_item i
                  join erp.produto p on p.id = i.produto_id
                  join erp.secao s on s.id = p.secao_id
                  join erp.produto_embalagem cx on cx.id = i.embalagem_id
                 where i.pedido_compra_id = ? order by i.sequencia""";
        try (Connection c = ds.getConnection()) {
            List<PedidoCompra.Item> lista = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(itens)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lista.add(new PedidoCompra.Item(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                                rs.getBigDecimal(9), rs.getString(10), rs.getBoolean(11)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(cab)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new PedidoCompra(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8),
                            rs.getDate(9).toLocalDate(), rs.getDate(10) == null ? null : rs.getDate(10).toLocalDate(),
                            rs.getString(11), List.copyOf(lista)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public String xmlDaNota(long notaId) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select xml from erp.nota_entrada where id = ?")) {
            ps.setLong(1, notaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Nota " + notaId + " não encontrada");
                }
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Map<String, Object>> linhas(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> lista = new ArrayList<>();
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    if (v instanceof java.sql.Date d) {
                        v = d.toLocalDate().toString();
                    } else if (v instanceof java.sql.Time t) {
                        v = t.toLocalTime().toString();
                    } else if (v instanceof java.sql.Timestamp ts) {
                        v = ts.toInstant().toString();
                    } else if (v instanceof org.postgresql.util.PGobject o) {
                        v = o.getValue();
                    }
                    m.put(rs.getMetaData().getColumnLabel(i), v);
                }
                lista.add(m);
            }
            return lista;
        }
    }
}
