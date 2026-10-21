package br.portfolio.recebimento.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

public record PedidoCompra(
        int id,
        int empresaId,
        String empresaCnpj,
        String empresaNome,
        String empresaUf,
        long fornecedorId,
        String fornecedorCnpj,
        String fornecedorNome,
        LocalDate emissao,
        LocalDate previsaoEntrega,
        String status,
        List<Item> itens) {

    public record Item(
            int sequencia,
            long produtoId,
            String descricao,
            String eanUnidade,
            String dun14,
            BigDecimal fator,                // unidades por caixa
            BigDecimal quantidadeCaixas,
            BigDecimal quantidadeRecebidaCaixas,
            BigDecimal custoCaixa,
            String ncm,
            boolean perecivel) {

        public BigDecimal unidadesPendentes() {
            return quantidadeCaixas.subtract(quantidadeRecebidaCaixas).multiply(fator);
        }

        public BigDecimal custoUnitario() {
            return custoCaixa.divide(fator, 6, RoundingMode.HALF_UP);
        }
    }

    public boolean temPerecivel() {
        return itens.stream().anyMatch(Item::perecivel);
    }

    public BigDecimal valorTotal() {
        return itens.stream().map(i -> i.quantidadeCaixas().multiply(i.custoCaixa()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
