package br.portfolio.recebimento.nfe;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê o XML da NF-e (nfeProc ou NFe sem protocolo). O parser bloqueia DOCTYPE e entidades
 * externas: XML vindo de fornecedor é entrada não confiável (ataque XXE).
 */
public final class LeitorNFe {

    public static final String NS = "http://www.portalfiscal.inf.br/nfe";

    public static class XmlInvalido extends RuntimeException {
        public XmlInvalido(String mensagem) {
            super(mensagem);
        }
    }

    private LeitorNFe() {
    }

    public static NotaFiscal ler(String xml) {
        Document doc = parse(xml);
        Element inf = primeiro(doc.getDocumentElement(), "infNFe");
        if (inf == null) {
            throw new XmlInvalido("XML não é uma NF-e (infNFe não encontrado)");
        }
        String id = inf.getAttribute("Id");
        String chave = id.startsWith("NFe") ? id.substring(3) : id;

        Element ide = primeiro(inf, "ide");
        Element emit = primeiro(inf, "emit");
        Element dest = primeiro(inf, "dest");
        Element tot = primeiro(inf, "ICMSTot");
        if (ide == null || emit == null || dest == null || tot == null) {
            throw new XmlInvalido("NF-e incompleta: faltam ide, emit, dest ou ICMSTot");
        }
        Element infProt = primeiro(doc.getDocumentElement(), "infProt");

        List<NotaFiscal.ItemNota> itens = new ArrayList<>();
        NodeList dets = inf.getElementsByTagNameNS(NS, "det");
        for (int i = 0; i < dets.getLength(); i++) {
            Element det = (Element) dets.item(i);
            Element prod = primeiro(det, "prod");
            String nItemPed = texto(prod, "nItemPed");
            itens.add(new NotaFiscal.ItemNota(
                    Integer.parseInt(det.getAttribute("nItem")),
                    texto(prod, "cProd"), texto(prod, "cEAN"), texto(prod, "cEANTrib"), texto(prod, "xProd"),
                    texto(prod, "NCM"), texto(prod, "CFOP"), texto(prod, "uCom"),
                    decimal(prod, "qCom"), decimal(prod, "vUnCom"), decimal(prod, "vProd"),
                    texto(prod, "xPed"), nItemPed == null ? null : Integer.valueOf(nItemPed)));
        }
        if (itens.isEmpty()) {
            throw new XmlInvalido("NF-e sem itens");
        }
        try {
            return new NotaFiscal(chave, Integer.parseInt(texto(ide, "mod")), Integer.parseInt(texto(ide, "serie")),
                    Long.parseLong(texto(ide, "nNF")), OffsetDateTime.parse(texto(ide, "dhEmi")),
                    texto(emit, "CNPJ"), texto(emit, "xNome"), texto(primeiro(emit, "enderEmit"), "UF"),
                    texto(dest, "CNPJ"), texto(primeiro(dest, "enderDest"), "UF"),
                    decimal(tot, "vProd"), decimal(tot, "vNF"),
                    infProt == null ? null : texto(infProt, "cStat"), List.copyOf(itens));
        } catch (RuntimeException e) {
            throw new XmlInvalido("campo obrigatório ausente ou inválido na NF-e: " + e.getMessage());
        }
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new XmlInvalido("XML inválido: " + e.getMessage());
        }
    }

    private static Element primeiro(Element pai, String nome) {
        if (pai == null) {
            return null;
        }
        NodeList l = pai.getElementsByTagNameNS(NS, nome);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    private static String texto(Element pai, String nome) {
        Element e = primeiro(pai, nome);
        return e == null ? null : e.getTextContent().trim();
    }

    private static BigDecimal decimal(Element pai, String nome) {
        String t = texto(pai, nome);
        if (t == null) {
            throw new XmlInvalido("campo " + nome + " ausente");
        }
        return new BigDecimal(t);
    }
}
