package br.portfolio.integrador.etapas;

import br.portfolio.integrador.banco.Documentos;
import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.Etapa;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PREPARAR ITENS: localiza cada produto no ERP (EAN para embalados, PLU de balança para pesáveis),
 * usa a quantidade SEPARADA (peso real, item em falta) e compara o preço do site com o do ERP.
 * Se algum produto não existir no ERP, o pedido inteiro fica pendente: faturar pedido incompleto é pior.
 */
@ApplicationScoped
public class PrepararItens implements Etapa {

    @Inject
    AgroalDataSource ds;

    @Override
    public String tipo() {
        return "preparar-itens";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) throws Exception {
        Object bruto = ctx.obter("pedido.itens");
        if (!(bruto instanceof List<?> itensSite) || itensSite.isEmpty()) {
            throw new ErroDeNegocio("pedido sem itens");
        }
        long empresa = ((Number) ctx.obter("empresa_pedido")).longValue();
        List<ItemPedido> itens = new ArrayList<>();
        List<String> naoEncontrados = new ArrayList<>();
        int emFalta = 0;
        int precoDivergente = 0;

        try (Connection c = ds.getConnection()) {
            int seq = 0;
            for (Object o : itensSite) {
                Map<?, ?> it = (Map<?, ?>) o;
                BigDecimal separada = Sql.decimal(it.get("quantidadeSeparada") != null ? it.get("quantidadeSeparada") : it.get("quantidadePedida"));
                if (separada == null || separada.signum() <= 0) {
                    emFalta++;          // item que faltou na separação não é faturado
                    continue;
                }
                Optional<Long> produto = localizar(c, it);
                if (produto.isEmpty()) {
                    naoEncontrados.add(identificacao(it));
                    continue;
                }
                boolean ativoNaLoja = Sql.umLong(c, "select 1 from erp.produto_loja where empresa_id = ? and produto_id = ? and ativo",
                        empresa, produto.get()).isPresent();
                if (!ativoNaLoja) {
                    naoEncontrados.add(identificacao(it) + " (inativo na loja)");
                    continue;
                }
                BigDecimal preco = Sql.decimal(it.get("precoUnitario")).setScale(2, RoundingMode.HALF_UP);
                BigDecimal precoErp = Sql.umDecimal(c, "select preco from erp.fn_preco_vigente(?::smallint, ?::int)", empresa, produto.get())
                        .orElse(preco);
                if (precoErp.subtract(preco).abs().compareTo(new BigDecimal("0.01")) > 0) {
                    precoDivergente++;
                }
                BigDecimal qtd = separada.setScale(3, RoundingMode.HALF_UP);
                itens.add(new ItemPedido(++seq, produto.get(), qtd, preco,
                        qtd.multiply(preco).setScale(2, RoundingMode.HALF_UP), String.valueOf(it.get("sku"))));
            }
        }
        if (!naoEncontrados.isEmpty()) {
            throw new ErroDeNegocio("produto(s) não cadastrado(s) no ERP: " + String.join(", ", naoEncontrados));
        }
        if (itens.isEmpty()) {
            throw new ErroDeNegocio("nenhum item separado: pedido não deve ser faturado");
        }
        ctx.definir("itens", itens);
        ctx.definir("avisos", precoDivergente == 0 ? null : precoDivergente + " item(ns) com preço do site diferente do ERP");
        return itens.size() + " item(ns)" + (emFalta > 0 ? ", " + emFalta + " em falta na separação" : "")
                + (precoDivergente > 0 ? ", " + precoDivergente + " com preço diferente do ERP" : "");
    }

    private Optional<Long> localizar(Connection c, Map<?, ?> it) throws Exception {
        Object plu = it.get("codigoInterno");
        if (plu != null && plu.toString().matches("\\d{1,6}")) {
            return Sql.umLong(c, "select id from erp.produto where codigo_balanca = ? and ativo", Integer.parseInt(plu.toString()));
        }
        Object ean = it.get("ean");
        if (ean == null || !Documentos.gtinValido(ean.toString())) {
            return Optional.empty();
        }
        return Sql.umLong(c, """
                select e.produto_id from erp.produto_embalagem e join erp.produto p on p.id = e.produto_id
                 where e.ean = ? and e.venda and p.ativo""", ean.toString());
    }

    private static String identificacao(Map<?, ?> it) {
        Object ean = it.get("ean");
        return (ean != null ? "EAN " + ean : "PLU " + it.get("codigoInterno")) + " " + it.get("descricao");
    }
}
