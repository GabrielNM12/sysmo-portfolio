package br.portfolio.integrador;

import br.portfolio.integrador.motor.CarregadorDeGrupos;
import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.DefinicaoEtapa;
import br.portfolio.integrador.motor.DefinicaoGrupo;
import br.portfolio.integrador.motor.Etapa;
import br.portfolio.integrador.motor.RegistroDeEtapas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarregadorDeGruposTest {

    private static Etapa fake(String tipo) {
        return new Etapa() {
            public String tipo() {
                return tipo;
            }

            public String executar(Contexto c, Map<String, Object> p) {
                return null;
            }
        };
    }

    private static final List<String> TIPOS = List.of("buscar-webservice", "autenticar-api", "listar-pedidos",
            "extrair-loja", "extrair-cliente", "extrair-endereco", "verificar-duplicidade", "salvar-cliente",
            "preparar-cabecalho", "preparar-itens", "gravar-pedido", "confirmar-integracao");

    @Test
    void grupoSiteMercadoEhValido() {
        DefinicaoGrupo g = CarregadorDeGrupos.ler("processos/sitemercado-pedidos.yml");
        CarregadorDeGrupos.validar(g, new RegistroDeEtapas(TIPOS.stream().map(CarregadorDeGruposTest::fake).toList()));
        assertEquals("SITEMERCADO_PEDIDOS", g.nome());
        assertEquals(2, g.variaveis().get("empresa_id"));
        DefinicaoEtapa laco = g.etapas().get(3);
        assertTrue(laco.ehLaco());
        assertEquals(9, laco.etapas().size());
        assertEquals(4, g.etapas().get(1).tentativasMaximas());
    }

    @Test
    void tipoInexistenteFalhaNaPartida() {
        DefinicaoGrupo g = new DefinicaoGrupo("X", null, false, Map.of(),
                List.of(DefinicaoEtapa.simples("ETAPA", "nao-existe")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CarregadorDeGrupos.validar(g, new RegistroDeEtapas(List.of(fake("a")))));
        assertTrue(e.getMessage().contains("nao-existe"));
    }
}
