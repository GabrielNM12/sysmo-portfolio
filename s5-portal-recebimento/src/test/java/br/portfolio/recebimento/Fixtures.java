package br.portfolio.recebimento;

import br.portfolio.recebimento.dominio.PedidoCompra;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

final class Fixtures {

    static final String FORNECEDOR = "20007919000162";
    static final String LOJA = "12345678000210";

    private Fixtures() {
    }

    /** Pedido de 10 cx de café (12 un/cx, R$ 120/cx) e 5 cx de iogurte (24 un/cx, R$ 72/cx). */
    static PedidoCompra pedido() {
        return new PedidoCompra(77, 2, LOJA, "Oeste Centro", "SC", 9, FORNECEDOR, "Distribuidora Planalto Ltda",
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(2), "ABERTO", List.of(
                new PedidoCompra.Item(1, 501, "CAFÉ TORRADO 500G", "7890000003507", "17890000003504", new BigDecimal("12"),
                        new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("120.0000"), "09012100", false),
                new PedidoCompra.Item(2, 502, "IOGURTE MORANGO 170G", "7890000003514", "17890000003511", new BigDecimal("24"),
                        new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("72.0000"), "04012010", true)));
    }
}
