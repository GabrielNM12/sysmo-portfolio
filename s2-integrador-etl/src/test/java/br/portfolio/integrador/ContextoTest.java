package br.portfolio.integrador;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextoTest {

    private final Map<String, Object> pedido = Map.of("id", "SM-1", "cliente", Map.of("cpf", "123"),
            "itens", List.of(Map.of("ean", "789"), Map.of("ean", "790")));

    @Test
    void navegaPorMapasEListasComEscopoDeItem() {
        Contexto global = new Contexto(Map.of("empresa_id", 2));
        Contexto item = global.paraItem("pedido", pedido);
        assertEquals("123", item.texto("pedido.cliente.cpf"));
        assertEquals("790", item.texto("pedido.itens.1.ean"));
        assertEquals(2, item.obter("empresa_id"));          // cai no escopo global
        assertNull(item.obter("pedido.itens.9.ean"));
        assertNull(global.obter("pedido"));                  // o item não vaza para o global
    }

    @Test
    void definirDentroDoItemNaoAfetaOutrosItens() {
        Contexto global = new Contexto(Map.of());
        Contexto a = global.paraItem("pedido", pedido);
        Contexto b = global.paraItem("pedido", pedido);
        a.definir("ja_integrado", true);
        a.definirGlobal("token", "abc");
        assertNull(b.obter("ja_integrado"));
        assertEquals("abc", b.obter("token"));
    }

    @Test
    void resolveExpressoesEPreservaTipo() {
        Contexto c = new Contexto(Map.of("empresa_id", 2, "ws", Map.of("url", "http://x")));
        assertEquals(2, c.resolver("${empresa_id}"));
        assertEquals("http://x/v1?loja=2", c.resolver("${ws.url}/v1?loja=${empresa_id}"));
        assertEquals("sem expressão", c.resolver("sem expressão"));
    }

    @Test
    void avaliaCondicoes() {
        Contexto c = new Contexto(Map.of("sim", true, "nao", false));
        assertTrue(c.avaliar("sim"));
        assertFalse(c.avaliar("!sim"));
        assertTrue(c.avaliar("!nao"));
        assertTrue(c.avaliar("!inexistente"));
    }

    @Test
    void campoObrigatorio() {
        ErroDeNegocio e = assertThrows(ErroDeNegocio.class, () -> new Contexto(Map.of()).exigirTexto("pedido.id"));
        assertTrue(e.getMessage().contains("pedido.id"));
    }
}
