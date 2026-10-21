package br.portfolio.recebimento.dominio;

public record Divergencia(Tipo tipo, Gravidade gravidade, Integer itemNota, Long produtoId, String mensagem) {

    public enum Gravidade { BLOQUEIA, ALERTA }

    public enum Tipo {
        NOTA_NAO_AUTORIZADA, CHAVE_INVALIDA, MODELO_INVALIDO, EMITENTE_DIFERENTE, DESTINATARIO_DIFERENTE,
        NOTA_DUPLICADA, PRODUTO_NAO_PEDIDO, QUANTIDADE_ACIMA, PRECO_ACIMA, TOTAL_INCONSISTENTE,
        QUANTIDADE_ABAIXO, ITEM_NAO_FATURADO, NCM_DIVERGENTE, CFOP_INCOMPATIVEL, PEDIDO_NAO_REFERENCIADO
    }
}
