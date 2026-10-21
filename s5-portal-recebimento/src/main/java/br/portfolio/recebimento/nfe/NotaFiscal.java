package br.portfolio.recebimento.nfe;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Dados da NF-e que importam para o recebimento (subconjunto do layout 4.00). */
public record NotaFiscal(
        String chave,
        int modelo,
        int serie,
        long numero,
        OffsetDateTime emissao,
        String emitenteCnpj,
        String emitenteNome,
        String emitenteUf,
        String destinatarioCnpj,
        String destinatarioUf,
        BigDecimal valorProdutos,
        BigDecimal valorNota,
        String protocoloStatus,          // cStat do protNFe (100 = autorizada); null se o XML não tem protocolo
        List<ItemNota> itens) {

    public record ItemNota(
            int numero,
            String codigoFornecedor,
            String ean,                   // cEAN (pode ser o DUN-14 da caixa) ou "SEM GTIN"
            String eanTributavel,         // cEANTrib (normalmente o EAN da unidade)
            String descricao,
            String ncm,
            String cfop,
            String unidade,
            BigDecimal quantidade,
            BigDecimal valorUnitario,
            BigDecimal valorTotal,
            String pedido,                // xPed: número do pedido de compra do cliente
            Integer itemPedido) {         // nItemPed
    }

    public boolean autorizada() {
        return "100".equals(protocoloStatus);
    }
}
