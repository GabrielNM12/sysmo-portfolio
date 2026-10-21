package br.portfolio.integrador.etapas;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Cabecalho(long empresaId, LocalDateTime dataPedido, String tipoEntrega, BigDecimal frete,
                        BigDecimal desconto, String formaPagamento, BigDecimal valorCobrado, String observacao) {
}
