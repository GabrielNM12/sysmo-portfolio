package br.portfolio.integrador.etapas;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.Etapa;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * GRAVAR PEDIDO: cabeçalho, itens e baixa de estoque numa única transação. A baixa entra no
 * livro-razão (estoque_movimento) e o gatilho do ERP atualiza o saldo; o integrador nunca
 * escreve direto no saldo. A chave única (origem, numero_externo) protege contra duplicidade
 * mesmo com duas execuções simultâneas.
 */
@ApplicationScoped
public class GravarPedido implements Etapa {

    @Inject
    AgroalDataSource ds;

    @Inject
    ObjectMapper json;

    @Override
    public String tipo() {
        return "gravar-pedido";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        Cabecalho cab = (Cabecalho) ctx.obter("cabecalho");
        List<ItemPedido> itens = (List<ItemPedido>) ctx.obter("itens");
        String origem = ctx.exigirTexto("origem");
        String numeroExterno = ctx.exigirTexto("pedido.id");
        long cliente = ((Number) ctx.obter("transacionador_id")).longValue();
        Object endereco = ctx.obter("endereco");

        BigDecimal produtos = itens.stream().map(ItemPedido::valorTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = produtos.add(cab.frete()).subtract(cab.desconto());
        String observacao = juntar(cab.observacao(), (String) ctx.obter("avisos"),
                cab.valorCobrado().signum() > 0 && cab.valorCobrado().compareTo(total) != 0
                        ? "valor cobrado no site " + cab.valorCobrado() + " difere do total " + total : null);

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                long id = Sql.umLong(c, """
                        insert into erp.pedido_venda (empresa_id, transacionador_id, origem, numero_externo, data_pedido,
                               tipo_entrega, valor_produtos, valor_frete, valor_desconto, valor_total, forma_pagamento,
                               endereco_entrega, observacao)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id""",
                        cab.empresaId(), cliente, origem, numeroExterno, Timestamp.valueOf(cab.dataPedido()), cab.tipoEntrega(),
                        produtos, cab.frete(), cab.desconto(), total, cab.formaPagamento(),
                        endereco == null ? null : json.writeValueAsString(endereco), observacao).orElseThrow();

                try (PreparedStatement item = c.prepareStatement("""
                        insert into erp.pedido_venda_item (pedido_venda_id, sequencia, produto_id, quantidade, preco_unitario, valor_total, codigo_externo)
                        values (?, ?, ?, ?, ?, ?, ?)""");
                     PreparedStatement mov = c.prepareStatement("""
                        insert into erp.estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, documento)
                        values (?, ?, now(), 'VENDA_ECOMMERCE', ?, ?)""")) {
                    for (ItemPedido i : itens) {
                        item.setLong(1, id);
                        item.setInt(2, i.sequencia());
                        item.setLong(3, i.produtoId());
                        item.setBigDecimal(4, i.quantidade());
                        item.setBigDecimal(5, i.precoUnitario());
                        item.setBigDecimal(6, i.valorTotal());
                        item.setString(7, i.codigoExterno());
                        item.addBatch();
                        mov.setLong(1, cab.empresaId());
                        mov.setLong(2, i.produtoId());
                        mov.setBigDecimal(3, i.quantidade().negate());
                        mov.setString(4, origem + "-" + numeroExterno);
                        mov.addBatch();
                    }
                    item.executeBatch();
                    mov.executeBatch();
                }
                c.commit();
                ctx.definir("numero_erp", id);
                return "pedido de venda " + id + " · " + itens.size() + " itens · total " + total;
            } catch (SQLException e) {
                c.rollback();
                if ("23505".equals(estado(e))) {           // outra execução gravou no meio do caminho
                    ctx.definir("ja_integrado", true);
                    Sql.umLong(c, "select id from erp.pedido_venda where origem = ? and numero_externo = ?", origem, numeroExterno)
                            .ifPresent(n -> ctx.definir("numero_erp", n));
                    return "já gravado por execução concorrente";
                }
                if (estado(e) != null && estado(e).startsWith("23")) {
                    throw new ErroDeNegocio("ERP recusou o pedido: " + e.getMessage());
                }
                throw e;
            }
        }
    }

    private static String estado(SQLException e) {
        SQLException atual = e;
        while (atual != null) {
            if (atual.getSQLState() != null) {
                return atual.getSQLState();
            }
            atual = atual.getNextException();
        }
        return null;
    }

    private static String juntar(String... partes) {
        StringBuilder sb = new StringBuilder();
        for (String parte : partes) {
            if (parte != null && !parte.isBlank()) {
                sb.append(sb.isEmpty() ? "" : " | ").append(parte);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
