package br.portfolio.integrador.etapas;

import java.math.BigDecimal;

public record ItemPedido(int sequencia, long produtoId, BigDecimal quantidade, BigDecimal precoUnitario,
                         BigDecimal valorTotal, String codigoExterno) {
}
