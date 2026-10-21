package br.portfolio.recebimento;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fluxo completo contra o S1: fornecedor agenda com XML, operador recebe, ERP é atualizado. */
@QuarkusTest
class PortalFluxoTest {

    @Inject
    AgroalDataSource ds;

    private String cnpj;
    private int pedido;

    @BeforeEach
    void escolherPedido() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     select t.cpf_cnpj, pc.id from erp.pedido_compra pc join erp.transacionador t on t.id = pc.fornecedor_id
                      where pc.status = 'ABERTO'
                        and not exists (select 1 from portal.agendamento a where a.pedido_compra_id = pc.id and a.status <> 'CANCELADO')
                      order by pc.id limit 1""")) {
            assertTrue(rs.next(), "S1 sem pedido de compra em aberto");
            cnpj = rs.getString(1);
            pedido = rs.getInt(2);
        }
    }

    private String login() {
        return given().contentType(ContentType.JSON).body(Map.of("cnpj", cnpj, "codigo", "1234"))
                .post("/api/sessao").then().statusCode(200).extract().path("token");
    }

    private String xml(String token, String cenario) {
        return given().auth().oauth2(token).get("/api/pedidos/" + pedido + "/nfe-exemplo?cenario=" + cenario)
                .then().statusCode(200).extract().asString();
    }

    private static LocalDate proximoDiaUtil() {
        LocalDate d = LocalDate.now().plusDays(1);
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.plusDays(1) : d;
    }

    @Test
    void fornecedorAgendaEOperadorRecebe() throws Exception {
        String token = login();
        List<Map<String, Object>> pedidos = given().auth().oauth2(token).get("/api/pedidos").then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertTrue(pedidos.stream().anyMatch(p -> ((Number) p.get("id")).intValue() == pedido));

        String nota = xml(token, "CORRETA");
        given().auth().oauth2(token).contentType("application/xml").body(nota).post("/api/pedidos/" + pedido + "/validar")
                .then().statusCode(200).body("status", org.hamcrest.Matchers.startsWith("APROVADA"));

        LocalDate dia = proximoDiaUtil();
        JsonPath disp = given().auth().oauth2(token).get("/api/pedidos/" + pedido + "/disponibilidade?data=" + dia)
                .then().statusCode(200).extract().jsonPath();
        int doca = disp.getInt("docas[0].id");
        String hora = disp.getString("docas[0].horarios.find { it.livre }.hora");

        int id = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("pedidoId", pedido, "docaId", doca, "data", dia.toString(), "hora", hora, "xml", nota))
                .post("/api/agendamentos").then().statusCode(201).extract().path("id");

        // mesmo pedido de novo: recusado; horário agora ocupado
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("pedidoId", pedido, "docaId", doca, "data", dia.toString(), "hora", hora, "xml", xml(token, "CORRETA")))
                .post("/api/agendamentos").then().statusCode(409);
        List<Map<String, Object>> horarios = given().auth().oauth2(token).get("/api/pedidos/" + pedido + "/disponibilidade?data=" + dia)
                .then().extract().jsonPath().getList("docas.find { it.id == " + doca + " }.horarios");
        assertFalse(horarios.stream().filter(h -> h.get("hora").equals(hora)).findFirst().map(h -> (Boolean) h.get("livre")).orElseThrow());

        // nota digitada no ERP
        assertEquals("DIGITADA", valor("select n.status from erp.nota_entrada n join portal.agendamento a on a.nota_entrada_id = n.id where a.id = " + id));

        String operador = given().contentType(ContentType.JSON).body(Map.of("usuario", "operador", "senha", "operador"))
                .post("/api/sessao/operador").then().statusCode(200).extract().path("token");
        given().auth().oauth2(token).get("/api/operacao/agenda").then().statusCode(403);       // fornecedor não vê a agenda do CD
        int empresa = Integer.parseInt(valor("select empresa_id from portal.agendamento where id = " + id));
        given().auth().oauth2(operador).get("/api/operacao/agenda?empresa=" + empresa + "&data=" + dia)
                .then().statusCode(200).body("agendamentos.id", org.hamcrest.Matchers.hasItem(id));
        given().auth().oauth2(operador).post("/api/operacao/agendamentos/" + id + "/recebido").then().statusCode(204);

        assertEquals("CONFERIDA", valor("select n.status from erp.nota_entrada n join portal.agendamento a on a.nota_entrada_id = n.id where a.id = " + id));
        assertEquals("RECEBIDO", valor("select status from erp.pedido_compra where id = " + pedido));
    }

    @Test
    void notaComPrecoAcimaNaoAgenda() {
        String token = login();
        String nota = xml(token, "PRECO_ACIMA");
        given().auth().oauth2(token).contentType("application/xml").body(nota).post("/api/pedidos/" + pedido + "/validar")
                .then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("BLOQUEADA"))
                .body("divergencias.tipo", org.hamcrest.Matchers.hasItem("PRECO_ACIMA"));
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("pedidoId", pedido, "docaId", 1, "data", proximoDiaUtil().toString(), "hora", "08:00", "xml", nota))
                .post("/api/agendamentos").then().statusCode(422);
    }

    @Test
    void segurancaDeAcesso() {
        given().contentType(ContentType.JSON).body(Map.of("cnpj", cnpj, "codigo", "errado")).post("/api/sessao").then().statusCode(401);
        given().get("/api/pedidos").then().statusCode(401);
        String token = login();
        given().auth().oauth2(token).get("/api/pedidos/999999").then().statusCode(404);
        given().auth().oauth2(token).contentType("application/xml").body("<!DOCTYPE x [<!ENTITY a SYSTEM \"file:///etc/passwd\">]><x>&a;</x>")
                .post("/api/pedidos/" + pedido + "/validar").then().statusCode(422);
        given().auth().oauth2(token).get("/api/pedidos/" + pedido + "/disponibilidade?data=" + LocalDate.now())
                .then().statusCode(200).body("motivoIndisponivel", org.hamcrest.Matchers.containsString("antecedência"));
    }

    private String valor(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
