package br.portfolio.recebimento.nfe;

import br.portfolio.recebimento.dominio.PedidoCompra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Gera um XML de NF-e (layout 4.00, sem assinatura digital) a partir de um pedido de compra,
 * com divergências opcionais. Serve para demonstrar o portal sem depender de um emissor real:
 * NÃO é documento fiscal válido.
 */
public final class GeradorNFeExemplo {

    public enum Cenario {
        CORRETA, PRECO_ACIMA, QUANTIDADE_ACIMA, ENTREGA_PARCIAL, PRODUTO_NAO_PEDIDO, OUTRO_DESTINATARIO, NCM_DIVERGENTE, SEM_PROTOCOLO
    }

    private GeradorNFeExemplo() {
    }

    private record Linha(String cProd, String ean, String eanTrib, String descricao, String ncm, BigDecimal caixas,
                         BigDecimal fator, BigDecimal custoCaixa) {
    }

    public static String gerar(PedidoCompra p, Cenario cenario, long semente) {
        Random r = new Random(semente);
        List<Linha> linhas = new ArrayList<>();
        for (PedidoCompra.Item i : p.itens()) {
            BigDecimal caixas = i.quantidadeCaixas().subtract(i.quantidadeRecebidaCaixas());
            if (caixas.signum() > 0) {
                linhas.add(new Linha("F" + i.produtoId(), i.dun14(), i.eanUnidade(), i.descricao(), i.ncm(), caixas, i.fator(), i.custoCaixa()));
            }
        }
        if (linhas.isEmpty()) {
            throw new IllegalStateException("Pedido " + p.id() + " não tem saldo a faturar");
        }
        Linha primeira = linhas.get(0);
        switch (cenario) {
            case PRECO_ACIMA -> linhas.set(0, new Linha(primeira.cProd(), primeira.ean(), primeira.eanTrib(), primeira.descricao(),
                    primeira.ncm(), primeira.caixas(), primeira.fator(), primeira.custoCaixa().multiply(new BigDecimal("1.08"))));
            case QUANTIDADE_ACIMA -> linhas.set(0, new Linha(primeira.cProd(), primeira.ean(), primeira.eanTrib(), primeira.descricao(),
                    primeira.ncm(), primeira.caixas().add(BigDecimal.valueOf(2)), primeira.fator(), primeira.custoCaixa()));
            case ENTREGA_PARCIAL -> {
                if (linhas.size() > 1) {
                    linhas.remove(linhas.size() - 1);
                }
                Linha l = linhas.get(0);
                BigDecimal metade = l.caixas().divide(BigDecimal.valueOf(2), 0, RoundingMode.UP);
                linhas.set(0, new Linha(l.cProd(), l.ean(), l.eanTrib(), l.descricao(), l.ncm(), metade, l.fator(), l.custoCaixa()));
            }
            case PRODUTO_NAO_PEDIDO -> linhas.add(new Linha("F999999", "17890000999995", "7890000999998",
                    "BRINDE PROMOCIONAL DISPLAY", "49111090", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("15.00")));
            case NCM_DIVERGENTE -> linhas.set(0, new Linha(primeira.cProd(), primeira.ean(), primeira.eanTrib(), primeira.descricao(),
                    "21069090", primeira.caixas(), primeira.fator(), primeira.custoCaixa()));
            default -> { }
        }

        OffsetDateTime emissao = OffsetDateTime.now(ZoneOffset.ofHours(-3)).withNano(0);
        long numero = 10_000 + r.nextInt(890_000);
        String chave = ChaveAcesso.montar(42, emissao.getYear(), emissao.getMonthValue(), p.fornecedorCnpj(), 55, 1, numero,
                r.nextInt(100_000_000));
        String destino = cenario == Cenario.OUTRO_DESTINATARIO ? "12345678000672" : p.empresaCnpj();

        StringBuilder det = new StringBuilder();
        BigDecimal total = BigDecimal.ZERO;
        int n = 0;
        for (Linha l : linhas) {
            BigDecimal valor = l.caixas().multiply(l.custoCaixa()).setScale(2, RoundingMode.HALF_UP);
            total = total.add(valor);
            BigDecimal unidades = l.caixas().multiply(l.fator());
            n++;
            det.append("""
                          <det nItem="%d">
                            <prod>
                              <cProd>%s</cProd><cEAN>%s</cEAN><xProd>%s</xProd><NCM>%s</NCM><CFOP>5102</CFOP>
                              <uCom>CX</uCom><qCom>%s</qCom><vUnCom>%s</vUnCom><vProd>%s</vProd>
                              <cEANTrib>%s</cEANTrib><uTrib>UN</uTrib><qTrib>%s</qTrib><vUnTrib>%s</vUnTrib><indTot>1</indTot>
                              <xPed>%d</xPed><nItemPed>%d</nItemPed>
                            </prod>
                            <imposto><ICMS><ICMS00><orig>0</orig><CST>00</CST><modBC>3</modBC><vBC>%s</vBC><pICMS>17.00</pICMS><vICMS>%s</vICMS></ICMS00></ICMS></imposto>
                          </det>
                    """.formatted(n, l.cProd(), l.ean() == null ? "SEM GTIN" : l.ean(), xml(l.descricao()), l.ncm(),
                    q(l.caixas()), d(l.custoCaixa(), 4), d(valor, 2), l.eanTrib() == null ? "SEM GTIN" : l.eanTrib(),
                    q(unidades), d(l.custoCaixa().divide(l.fator(), 6, RoundingMode.HALF_UP), 4), p.id(), n,
                    d(valor, 2), d(valor.multiply(new BigDecimal("0.17")), 2)));
        }
        String protocolo = cenario == Cenario.SEM_PROTOCOLO ? "" : """
                  <protNFe versao="4.00"><infProt><tpAmb>2</tpAmb><chNFe>%s</chNFe><dhRecbto>%s</dhRecbto>
                    <nProt>342%012d</nProt><cStat>100</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo></infProt></protNFe>
                """.formatted(chave, emissao, numero);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- NF-e de EXEMPLO gerada pelo portal de laboratório: não é documento fiscal válido -->
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00">
                  <NFe>
                    <infNFe Id="NFe%s" versao="4.00">
                      <ide><cUF>42</cUF><cNF>%s</cNF><natOp>VENDA DE MERCADORIA</natOp><mod>55</mod><serie>1</serie><nNF>%d</nNF>
                        <dhEmi>%s</dhEmi><tpNF>1</tpNF><idDest>1</idDest><tpAmb>2</tpAmb></ide>
                      <emit><CNPJ>%s</CNPJ><xNome>%s</xNome><enderEmit><xMun>CHAPECO</xMun><UF>SC</UF></enderEmit><CRT>3</CRT></emit>
                      <dest><CNPJ>%s</CNPJ><xNome>%s</xNome><enderDest><xMun>%s</xMun><UF>%s</UF></enderDest></dest>
                %s      <total><ICMSTot><vProd>%s</vProd><vNF>%s</vNF></ICMSTot></total>
                      <infAdic><infCpl>Pedido de compra %d</infCpl></infAdic>
                    </infNFe>
                  </NFe>
                %s</nfeProc>
                """.formatted(chave, chave.substring(35, 43), numero, emissao, p.fornecedorCnpj(), xml(p.fornecedorNome()),
                destino, xml(p.empresaNome()), "SAO MIGUEL DO OESTE", p.empresaUf(), det, d(total, 2), d(total, 2), p.id(), protocolo);
    }

    private static String q(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String d(BigDecimal v, int casas) {
        return v.setScale(casas, RoundingMode.HALF_UP).toPlainString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
