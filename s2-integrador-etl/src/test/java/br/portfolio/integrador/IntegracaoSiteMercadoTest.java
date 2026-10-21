package br.portfolio.integrador;

import br.portfolio.integrador.motor.ResumoExecucao;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ponta a ponta contra o ERP de laboratório (S1): simulador de e-commerce → integrador → PostgreSQL.
 * Requer o S1 carregado (o CI carrega antes de rodar os testes).
 */
@QuarkusTest
class IntegracaoSiteMercadoTest {

    private static final String EXECUTAR = "/integrador/grupos/SITEMERCADO_PEDIDOS/executar";

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void limpar() {
        given().delete("/sitemercado/_simulador").then().statusCode(204);
    }

    private List<Map<String, Object>> gerar(String cenario, int quantidade) {
        return given().post("/sitemercado/_simulador/pedidos?cenario=" + cenario + "&quantidade=" + quantidade)
                .then().statusCode(201).extract().jsonPath().getList("$");
    }

    private ResumoExecucao executar() {
        return given().post(EXECUTAR).then().statusCode(200).extract().as(ResumoExecucao.class);
    }

    private Map<String, Object> noSimulador(String id) {
        List<Map<String, Object>> todos = given().get("/sitemercado/_simulador/pedidos").then().extract().jsonPath().getList("$");
        return todos.stream().filter(p -> id.equals(p.get("id"))).findFirst().orElseThrow();
    }

    private long contar(String sql, Object... params) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private BigDecimal decimal(String sql, Object... params) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    @Test
    void importaPedidosSeparadosEIgnoraEmSeparacao() throws Exception {
        List<Map<String, Object>> normais = gerar("NORMAL", 2);
        Map<String, Object> pesavel = gerar("PESAVEL", 1).get(0);
        Map<String, Object> retirada = gerar("RETIRADA", 1).get(0);
        Map<String, Object> emSeparacao = gerar("EM_SEPARACAO", 1).get(0);

        ResumoExecucao r = executar();
        assertEquals("OK", r.status());
        assertEquals(4, r.itensOk());

        for (Map<String, Object> p : List.of(normais.get(0), normais.get(1), pesavel, retirada)) {
            assertNotNull(noSimulador((String) p.get("id")).get("numeroErp"), "pedido confirmado na plataforma");
            assertEquals(1, contar("select count(*) from erp.pedido_venda where origem = 'SITEMERCADO' and numero_externo = ?", p.get("id")));
        }
        assertNull(noSimulador((String) emSeparacao.get("id")).get("numeroErp"));
        assertEquals(0, contar("select count(*) from erp.pedido_venda where numero_externo = ?", emSeparacao.get("id")));

        // pesável entra com o peso SEPARADO (1,085 kg), não com o pedido (1 kg)
        assertEquals(0, new BigDecimal("1.085").compareTo(decimal("""
                select i.quantidade from erp.pedido_venda_item i join erp.pedido_venda v on v.id = i.pedido_venda_id
                  join erp.produto p on p.id = i.produto_id
                 where v.numero_externo = ? and p.pesavel""", pesavel.get("id"))));
        // retirada na loja: sem frete e sem endereço
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(
                "select valor_frete from erp.pedido_venda where numero_externo = ?", retirada.get("id"))));
    }

    @Test
    void baixaEstoquePeloLivroRazao() throws Exception {
        Map<String, Object> pedido = gerar("NORMAL", 1).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) pedido.get("itens")).get(0);
        String saldo = """
                select pl.estoque_atual from erp.produto_loja pl
                  join erp.produto_embalagem e on e.produto_id = pl.produto_id and e.ean = ?
                 where pl.empresa_id = 2""";
        BigDecimal antes = decimal(saldo, item.get("ean"));
        assertEquals("OK", executar().status());
        BigDecimal depois = decimal(saldo, item.get("ean"));
        assertEquals(0, antes.subtract(new BigDecimal(item.get("quantidadeSeparada").toString())).compareTo(depois));
    }

    @Test
    void pedidoComProblemaFicaPendenteSemTravarOsOutros() {
        Map<String, Object> bom = gerar("NORMAL", 1).get(0);
        Map<String, Object> eanDesconhecido = gerar("EAN_DESCONHECIDO", 1).get(0);
        Map<String, Object> cpfInvalido = gerar("CPF_INVALIDO", 1).get(0);

        ResumoExecucao r = executar();
        assertEquals("PARCIAL", r.status());
        assertEquals(1, r.itensOk());
        assertEquals(2, r.itensComErro());
        assertNotNull(noSimulador((String) bom.get("id")).get("numeroErp"));
        assertNull(noSimulador((String) eanDesconhecido.get("id")).get("numeroErp"));
        assertNull(noSimulador((String) cpfInvalido.get("id")).get("numeroErp"));

        String detalhe = given().get("/integrador/execucoes/" + r.execucao()).then().statusCode(200).extract().asString();
        assertTrue(detalhe.contains("não cadastrado(s) no ERP"));
        assertTrue(detalhe.contains("CPF do cliente inválido"));
    }

    @Test
    void itemEmFaltaEPrecoPromocionalDoSite() throws Exception {
        Map<String, Object> falta = gerar("ITEM_EM_FALTA", 1).get(0);
        Map<String, Object> promo = gerar("PRECO_PROMOCIONAL", 1).get(0);
        assertEquals("OK", executar().status());
        int itensNoSite = ((List<?>) falta.get("itens")).size();
        assertEquals(itensNoSite - 1, contar("""
                select count(*) from erp.pedido_venda_item i join erp.pedido_venda v on v.id = i.pedido_venda_id
                 where v.numero_externo = ?""", falta.get("id")));
        assertEquals(1, contar("select count(*) from erp.pedido_venda where numero_externo = ? and observacao like '%preço do site diferente%'",
                promo.get("id")));
    }

    @Test
    void instabilidadeDaApiEhSuperadaPorRetentativa() {
        gerar("NORMAL", 1);
        given().post("/sitemercado/_simulador/falhas?quantidade=2").then().statusCode(204);
        ResumoExecucao r = executar();
        assertEquals("OK", r.status());
        assertEquals(1, r.itensOk());
        List<Map<String, Object>> etapas = given().get("/integrador/execucoes/" + r.execucao()).then().extract().jsonPath().getList("etapas");
        assertEquals(2, etapas.stream().filter(e -> "RETENTATIVA".equals(e.get("status"))).count());
    }

    @Test
    void confirmacaoPerdidaNaoDuplicaPedido() throws Exception {
        String id = (String) gerar("NORMAL", 1).get(0).get("id");
        assertEquals(1, executar().itensOk());
        String numeroErp = (String) noSimulador(id).get("numeroErp");

        // a plataforma "perde" a confirmação e devolve o pedido como não integrado
        given().delete("/sitemercado/_simulador/pedidos/" + id + "/integracao").then().statusCode(204);
        ResumoExecucao r = executar();
        assertEquals(0, r.itensOk());
        assertEquals(1, r.itensIgnorados());
        assertEquals(1, contar("select count(*) from erp.pedido_venda where numero_externo = ?", id));
        assertEquals(numeroErp, noSimulador(id).get("numeroErp"));
    }

    @Test
    void apiDeConsulta() {
        given().get("/integrador/grupos").then().statusCode(200).contentType(ContentType.JSON);
        executar();
        given().get("/integrador/execucoes?limite=1").then().statusCode(200);
        given().get("/integrador/execucoes/999999999").then().statusCode(404);
        given().post("/integrador/grupos/NAO_EXISTE/executar").then().statusCode(404);
        given().get("/q/health").then().statusCode(200);
    }
}
