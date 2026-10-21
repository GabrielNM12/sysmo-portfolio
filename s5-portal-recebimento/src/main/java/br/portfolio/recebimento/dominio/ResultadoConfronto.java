package br.portfolio.recebimento.dominio;

import java.math.BigDecimal;
import java.util.List;

public record ResultadoConfronto(
        Status status,
        String chave,
        long numeroNota,
        BigDecimal valorNota,
        BigDecimal valorPedido,
        List<ItemConferido> itens,
        List<Divergencia> divergencias) {

    public enum Status { APROVADA, APROVADA_COM_RESSALVAS, BLOQUEADA }

    /** Linha da conferência: item da nota ligado (ou não) a um item do pedido, já em unidades. */
    public record ItemConferido(int itemNota, String descricaoNota, Long produtoId, Integer itemPedido,
                                BigDecimal unidadesNota, BigDecimal unidadesPendentes,
                                BigDecimal custoUnitarioNota, BigDecimal custoUnitarioPedido) {
    }

    public boolean bloqueada() {
        return status == Status.BLOQUEADA;
    }
}
