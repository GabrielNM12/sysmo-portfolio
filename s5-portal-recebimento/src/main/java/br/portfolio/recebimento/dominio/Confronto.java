package br.portfolio.recebimento.dominio;

import br.portfolio.recebimento.dominio.Divergencia.Gravidade;
import br.portfolio.recebimento.dominio.Divergencia.Tipo;
import br.portfolio.recebimento.dominio.ResultadoConfronto.ItemConferido;
import br.portfolio.recebimento.nfe.ChaveAcesso;
import br.portfolio.recebimento.nfe.NotaFiscal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Confronto pedido de compra × NF-e, feito ANTES de o caminhão chegar.
 * Caixa e unidade são normalizadas: o fornecedor fatura em CX (DUN-14) ou UN (EAN-13),
 * o pedido guarda caixas com fator; a comparação é sempre em unidades e custo por unidade.
 */
public final class Confronto {

    private final BigDecimal toleranciaPreco;   // fração: 0,005 = 0,5%

    public Confronto(BigDecimal toleranciaPrecoPercentual) {
        this.toleranciaPreco = toleranciaPrecoPercentual.movePointLeft(2);
    }

    public ResultadoConfronto confrontar(PedidoCompra pedido, NotaFiscal nota, boolean chaveJaUtilizada) {
        List<Divergencia> d = new ArrayList<>();
        cabecalho(pedido, nota, chaveJaUtilizada, d);

        Map<String, PedidoCompra.Item> porDun = new HashMap<>();
        Map<String, PedidoCompra.Item> porEan = new HashMap<>();
        for (PedidoCompra.Item i : pedido.itens()) {
            if (i.dun14() != null) {
                porDun.put(i.dun14(), i);
            }
            if (i.eanUnidade() != null) {
                porEan.put(i.eanUnidade(), i);
            }
        }

        Map<Long, BigDecimal> unidadesPorProduto = new HashMap<>();
        Map<Long, BigDecimal> valorPorProduto = new HashMap<>();
        List<ItemConferido> conferidos = new ArrayList<>();
        Set<Long> faturados = new HashSet<>();

        for (NotaFiscal.ItemNota n : nota.itens()) {
            PedidoCompra.Item p = null;
            BigDecimal fatorNota = BigDecimal.ONE;
            if (porDun.containsKey(n.ean())) {
                p = porDun.get(n.ean());
                fatorNota = p.fator();                    // faturado em caixa
            } else if (porEan.containsKey(n.ean())) {
                p = porEan.get(n.ean());
            } else if (porEan.containsKey(n.eanTributavel())) {
                p = porEan.get(n.eanTributavel());
                fatorNota = n.unidade() != null && n.unidade().startsWith("C") ? p.fator() : BigDecimal.ONE;
            }
            if (p == null) {
                d.add(new Divergencia(Tipo.PRODUTO_NAO_PEDIDO, Gravidade.BLOQUEIA, n.numero(), null,
                        "Item " + n.numero() + " (" + n.descricao() + ", GTIN " + n.ean() + ") não consta no pedido " + pedido.id()));
                conferidos.add(new ItemConferido(n.numero(), n.descricao(), null, null, n.quantidade(), null, n.valorUnitario(), null));
                continue;
            }
            BigDecimal unidades = n.quantidade().multiply(fatorNota);
            unidadesPorProduto.merge(p.produtoId(), unidades, BigDecimal::add);
            valorPorProduto.merge(p.produtoId(), n.valorTotal(), BigDecimal::add);
            faturados.add(p.produtoId());
            BigDecimal custoNota = n.valorUnitario().divide(fatorNota, 6, RoundingMode.HALF_UP);
            conferidos.add(new ItemConferido(n.numero(), n.descricao(), p.produtoId(), p.sequencia(), unidades,
                    p.unidadesPendentes(), custoNota, p.custoUnitario()));

            if (p.ncm() != null && n.ncm() != null && !p.ncm().equals(n.ncm())) {
                d.add(new Divergencia(Tipo.NCM_DIVERGENTE, Gravidade.ALERTA, n.numero(), p.produtoId(),
                        "NCM da nota " + n.ncm() + " difere do cadastro " + p.ncm() + ": revisar tributação antes de escriturar"));
            }
            cfop(nota, n, p, d);
        }

        for (PedidoCompra.Item p : pedido.itens()) {
            BigDecimal unidades = unidadesPorProduto.get(p.produtoId());
            if (unidades == null) {
                if (p.unidadesPendentes().signum() > 0) {
                    d.add(new Divergencia(Tipo.ITEM_NAO_FATURADO, Gravidade.ALERTA, null, p.produtoId(),
                            p.descricao() + ": " + caixas(p.unidadesPendentes(), p.fator()) + " pedidas e não faturadas (entrega parcial)"));
                }
                continue;
            }
            int cmp = unidades.compareTo(p.unidadesPendentes());
            if (cmp > 0) {
                d.add(new Divergencia(Tipo.QUANTIDADE_ACIMA, Gravidade.BLOQUEIA, null, p.produtoId(),
                        p.descricao() + ": nota traz " + num(unidades) + " un, pedido tem " + num(p.unidadesPendentes()) + " un pendentes"));
            } else if (cmp < 0) {
                d.add(new Divergencia(Tipo.QUANTIDADE_ABAIXO, Gravidade.ALERTA, null, p.produtoId(),
                        p.descricao() + ": nota traz " + num(unidades) + " de " + num(p.unidadesPendentes()) + " un (saldo fica em aberto)"));
            }
            BigDecimal custoMedioNota = valorPorProduto.get(p.produtoId()).divide(unidades, 6, RoundingMode.HALF_UP);
            BigDecimal limite = p.custoUnitario().multiply(BigDecimal.ONE.add(toleranciaPreco));
            if (custoMedioNota.compareTo(limite) > 0) {
                BigDecimal perc = custoMedioNota.divide(p.custoUnitario(), 6, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE).movePointRight(2).setScale(1, RoundingMode.HALF_UP);
                BigDecimal excesso = custoMedioNota.subtract(p.custoUnitario()).multiply(unidades).setScale(2, RoundingMode.HALF_UP);
                d.add(new Divergencia(Tipo.PRECO_ACIMA, Gravidade.BLOQUEIA, null, p.produtoId(),
                        p.descricao() + ": custo R$ " + moeda(custoMedioNota) + "/un contra R$ " + moeda(p.custoUnitario())
                                + " negociado (+" + perc.toPlainString().replace('.', ',') + "%, R$ " + moeda(excesso) + " a mais na nota)"));
            }
        }

        BigDecimal somaItens = nota.itens().stream().map(NotaFiscal.ItemNota::valorTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (somaItens.subtract(nota.valorProdutos()).abs().compareTo(new BigDecimal("0.05")) > 0) {
            d.add(new Divergencia(Tipo.TOTAL_INCONSISTENTE, Gravidade.ALERTA, null, null,
                    "Soma dos itens R$ " + moeda(somaItens) + " difere do total de produtos R$ " + moeda(nota.valorProdutos())));
        }

        ResultadoConfronto.Status status = d.stream().anyMatch(x -> x.gravidade() == Gravidade.BLOQUEIA)
                ? ResultadoConfronto.Status.BLOQUEADA
                : d.isEmpty() ? ResultadoConfronto.Status.APROVADA : ResultadoConfronto.Status.APROVADA_COM_RESSALVAS;
        return new ResultadoConfronto(status, nota.chave(), nota.numero(), nota.valorNota(), pedido.valorTotal(),
                List.copyOf(conferidos), List.copyOf(d));
    }

    private static void cabecalho(PedidoCompra pedido, NotaFiscal nota, boolean duplicada, List<Divergencia> d) {
        if (!ChaveAcesso.valida(nota.chave())) {
            d.add(new Divergencia(Tipo.CHAVE_INVALIDA, Gravidade.BLOQUEIA, null, null, "Chave de acesso com dígito verificador inválido"));
        } else if (!ChaveAcesso.cnpj(nota.chave()).equals(nota.emitenteCnpj())) {
            d.add(new Divergencia(Tipo.CHAVE_INVALIDA, Gravidade.BLOQUEIA, null, null, "CNPJ da chave não é o do emitente"));
        }
        if (nota.modelo() != 55) {
            d.add(new Divergencia(Tipo.MODELO_INVALIDO, Gravidade.BLOQUEIA, null, null, "Entrada de mercadoria exige NF-e modelo 55 (veio " + nota.modelo() + ")"));
        }
        if (!nota.autorizada()) {
            d.add(new Divergencia(Tipo.NOTA_NAO_AUTORIZADA, Gravidade.BLOQUEIA, null, null,
                    nota.protocoloStatus() == null ? "XML sem protocolo de autorização da SEFAZ" : "NF-e não autorizada (cStat " + nota.protocoloStatus() + ")"));
        }
        if (!pedido.fornecedorCnpj().equals(nota.emitenteCnpj())) {
            d.add(new Divergencia(Tipo.EMITENTE_DIFERENTE, Gravidade.BLOQUEIA, null, null,
                    "Emitente " + nota.emitenteCnpj() + " não é o fornecedor do pedido (" + pedido.fornecedorCnpj() + ")"));
        }
        if (!pedido.empresaCnpj().equals(nota.destinatarioCnpj())) {
            d.add(new Divergencia(Tipo.DESTINATARIO_DIFERENTE, Gravidade.BLOQUEIA, null, null,
                    "Nota destinada ao CNPJ " + nota.destinatarioCnpj() + ", mas o pedido é da unidade " + pedido.empresaNome()
                            + " (" + pedido.empresaCnpj() + ")"));
        }
        if (duplicada) {
            d.add(new Divergencia(Tipo.NOTA_DUPLICADA, Gravidade.BLOQUEIA, null, null, "Esta NF-e já foi apresentada em outro agendamento ou já está no ERP"));
        }
        boolean referencia = nota.itens().stream().anyMatch(i -> String.valueOf(pedido.id()).equals(i.pedido()));
        if (!referencia) {
            d.add(new Divergencia(Tipo.PEDIDO_NAO_REFERENCIADO, Gravidade.ALERTA, null, null,
                    "A nota não informa o pedido " + pedido.id() + " (campo xPed): o recebimento manual fica mais lento"));
        }
    }

    private static void cfop(NotaFiscal nota, NotaFiscal.ItemNota n, PedidoCompra.Item p, List<Divergencia> d) {
        if (n.cfop() == null || nota.emitenteUf() == null || nota.destinatarioUf() == null) {
            return;
        }
        boolean mesmaUf = nota.emitenteUf().equals(nota.destinatarioUf());
        char esperado = mesmaUf ? '5' : '6';
        if (n.cfop().charAt(0) != esperado) {
            d.add(new Divergencia(Tipo.CFOP_INCOMPATIVEL, Gravidade.ALERTA, n.numero(), p.produtoId(),
                    "CFOP " + n.cfop() + " em operação " + (mesmaUf ? "interna" : "interestadual") + " (esperado " + esperado + "xxx)"));
        }
    }

    private static String caixas(BigDecimal unidades, BigDecimal fator) {
        return num(unidades.divide(fator, 3, RoundingMode.HALF_UP)) + " cx";
    }

    private static String num(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String moeda(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
