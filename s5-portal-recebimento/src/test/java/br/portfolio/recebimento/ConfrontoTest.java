package br.portfolio.recebimento;

import br.portfolio.recebimento.dominio.Confronto;
import br.portfolio.recebimento.dominio.Divergencia.Tipo;
import br.portfolio.recebimento.dominio.ResultadoConfronto;
import br.portfolio.recebimento.dominio.ResultadoConfronto.Status;
import br.portfolio.recebimento.nfe.GeradorNFeExemplo;
import br.portfolio.recebimento.nfe.GeradorNFeExemplo.Cenario;
import br.portfolio.recebimento.nfe.LeitorNFe;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfrontoTest {

    private final Confronto confronto = new Confronto(new BigDecimal("0.5"));

    private ResultadoConfronto conferir(Cenario cenario, boolean duplicada) {
        return confronto.confrontar(Fixtures.pedido(), LeitorNFe.ler(GeradorNFeExemplo.gerar(Fixtures.pedido(), cenario, 7)), duplicada);
    }

    private static Set<Tipo> tipos(ResultadoConfronto r) {
        return r.divergencias().stream().map(d -> d.tipo()).collect(Collectors.toSet());
    }

    @Test
    void notaCorretaEhAprovadaEmUnidades() {
        ResultadoConfronto r = conferir(Cenario.CORRETA, false);
        assertEquals(Status.APROVADA, r.status(), r.divergencias().toString());
        var cafe = r.itens().get(0);
        assertEquals(0, new BigDecimal("120").compareTo(cafe.unidadesNota()));          // 10 cx × 12
        assertEquals(0, new BigDecimal("10").compareTo(cafe.custoUnitarioNota()));       // R$ 120 / 12
    }

    @Test
    void precoAcimaDaToleranciaBloqueia() {
        ResultadoConfronto r = conferir(Cenario.PRECO_ACIMA, false);
        assertEquals(Status.BLOQUEADA, r.status());
        assertEquals(Set.of(Tipo.PRECO_ACIMA), tipos(r));
        assertTrue(r.divergencias().get(0).mensagem().contains("+8,0%"), r.divergencias().get(0).mensagem());
    }

    @Test
    void quantidadeAcimaBloqueia() {
        assertEquals(Set.of(Tipo.QUANTIDADE_ACIMA), tipos(conferir(Cenario.QUANTIDADE_ACIMA, false)));
    }

    @Test
    void entregaParcialPassaComRessalvas() {
        ResultadoConfronto r = conferir(Cenario.ENTREGA_PARCIAL, false);
        assertEquals(Status.APROVADA_COM_RESSALVAS, r.status());
        assertEquals(Set.of(Tipo.QUANTIDADE_ABAIXO, Tipo.ITEM_NAO_FATURADO), tipos(r));
    }

    @Test
    void produtoNaoPedidoBloqueia() {
        assertEquals(Set.of(Tipo.PRODUTO_NAO_PEDIDO), tipos(conferir(Cenario.PRODUTO_NAO_PEDIDO, false)));
    }

    @Test
    void outroDestinatarioDuplicadaESemProtocoloBloqueiam() {
        assertTrue(tipos(conferir(Cenario.OUTRO_DESTINATARIO, false)).contains(Tipo.DESTINATARIO_DIFERENTE));
        assertTrue(tipos(conferir(Cenario.CORRETA, true)).contains(Tipo.NOTA_DUPLICADA));
        assertTrue(tipos(conferir(Cenario.SEM_PROTOCOLO, false)).contains(Tipo.NOTA_NAO_AUTORIZADA));
    }

    @Test
    void ncmDivergenteEhAlertaFiscal() {
        ResultadoConfronto r = conferir(Cenario.NCM_DIVERGENTE, false);
        assertEquals(Status.APROVADA_COM_RESSALVAS, r.status());
        assertEquals(Set.of(Tipo.NCM_DIVERGENTE), tipos(r));
    }
}
