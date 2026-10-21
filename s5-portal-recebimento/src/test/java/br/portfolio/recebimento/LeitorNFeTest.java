package br.portfolio.recebimento;

import br.portfolio.recebimento.nfe.ChaveAcesso;
import br.portfolio.recebimento.nfe.GeradorNFeExemplo;
import br.portfolio.recebimento.nfe.LeitorNFe;
import br.portfolio.recebimento.nfe.NotaFiscal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeitorNFeTest {

    @Test
    void leNotaGeradaComTodosOsCampos() {
        NotaFiscal n = LeitorNFe.ler(GeradorNFeExemplo.gerar(Fixtures.pedido(), GeradorNFeExemplo.Cenario.CORRETA, 1));
        assertTrue(ChaveAcesso.valida(n.chave()));
        assertEquals(Fixtures.FORNECEDOR, n.emitenteCnpj());
        assertEquals(Fixtures.LOJA, n.destinatarioCnpj());
        assertEquals(55, n.modelo());
        assertTrue(n.autorizada());
        assertEquals(2, n.itens().size());
        NotaFiscal.ItemNota cafe = n.itens().get(0);
        assertEquals("17890000003504", cafe.ean());
        assertEquals("7890000003507", cafe.eanTributavel());
        assertEquals(0, new BigDecimal("10").compareTo(cafe.quantidade()));
        assertEquals("77", cafe.pedido());
        assertEquals(0, new BigDecimal("1560.00").compareTo(n.valorNota()));     // 10×120 + 5×72
    }

    @Test
    void semProtocoloNaoEhAutorizada() {
        NotaFiscal n = LeitorNFe.ler(GeradorNFeExemplo.gerar(Fixtures.pedido(), GeradorNFeExemplo.Cenario.SEM_PROTOCOLO, 1));
        assertFalse(n.autorizada());
    }

    @Test
    void digitoDaChave() {
        String chave = ChaveAcesso.montar(42, 2026, 10, Fixtures.FORNECEDOR, 55, 1, 1234, 87654321);
        assertEquals(44, chave.length());
        assertTrue(ChaveAcesso.valida(chave));
        String adulterada = chave.substring(0, 30) + (chave.charAt(30) == '9' ? '0' : (char) (chave.charAt(30) + 1)) + chave.substring(31);
        assertFalse(ChaveAcesso.valida(adulterada));
        assertEquals(Fixtures.FORNECEDOR, ChaveAcesso.cnpj(chave));
    }

    @Test
    void bloqueiaXxe() {
        String ataque = """
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY segredo SYSTEM "file:///etc/passwd">]>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe><infNFe Id="NFe1">&segredo;</infNFe></NFe></nfeProc>""";
        LeitorNFe.XmlInvalido e = assertThrows(LeitorNFe.XmlInvalido.class, () -> LeitorNFe.ler(ataque));
        assertTrue(e.getMessage().contains("DOCTYPE"));
    }

    @Test
    void rejeitaXmlQueNaoEhNfe() {
        assertThrows(LeitorNFe.XmlInvalido.class, () -> LeitorNFe.ler("<pedido><id>1</id></pedido>"));
        assertThrows(LeitorNFe.XmlInvalido.class, () -> LeitorNFe.ler("isto não é xml"));
    }
}
