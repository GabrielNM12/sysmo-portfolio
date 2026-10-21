package br.portfolio.recebimento.api;

import br.portfolio.recebimento.api.Mapeadores.RegraDeAgendamento;
import br.portfolio.recebimento.api.Sessoes.Papel;
import br.portfolio.recebimento.api.Sessoes.Sessao;
import br.portfolio.recebimento.banco.Agenda;
import br.portfolio.recebimento.banco.Erp;
import br.portfolio.recebimento.dominio.Confronto;
import br.portfolio.recebimento.dominio.PedidoCompra;
import br.portfolio.recebimento.dominio.ResultadoConfronto;
import br.portfolio.recebimento.nfe.GeradorNFeExemplo;
import br.portfolio.recebimento.nfe.LeitorNFe;
import br.portfolio.recebimento.nfe.NotaFiscal;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Portal do fornecedor")
public class PortalResource {

    @Inject
    Erp erp;

    @Inject
    Agenda agenda;

    @Inject
    Sessoes sessoes;

    @ConfigProperty(name = "portal.tolerancia-preco-perc")
    BigDecimal tolerancia;

    @ConfigProperty(name = "portal.horarios")
    List<LocalTime> horarios;

    @ConfigProperty(name = "portal.antecedencia-minima-dias")
    int antecedencia;

    @ConfigProperty(name = "portal.codigo-acesso-inicial")
    String codigoInicial;

    @ConfigProperty(name = "portal.operador.usuario")
    String operadorUsuario;

    @ConfigProperty(name = "portal.operador.senha")
    String operadorSenha;

    public record Login(String cnpj, String codigo) {
    }

    public record LoginOperador(String usuario, String senha) {
    }

    public record NovoAgendamento(int pedidoId, int docaId, LocalDate data, LocalTime hora, String xml) {
    }

    // ------------------------------------------------------------------ sessão

    @POST
    @Path("/sessao")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> entrar(Login login) {
        String cnpj = login.cnpj() == null ? "" : login.cnpj().replaceAll("\\D", "");
        Erp.Fornecedor f = erp.fornecedorPorCnpj(cnpj).orElseThrow(() -> new NotAuthorizedException("Bearer"));
        String hash = Sessoes.hash(login.codigo() == null ? "" : login.codigo());
        String esperado = agenda.hashDoAcesso(f.id()).orElse(Sessoes.hash(codigoInicial));
        if (!esperado.equals(hash)) {
            throw new NotAuthorizedException("Bearer");
        }
        agenda.registrarAcesso(f.id(), esperado);
        return Map.of("token", sessoes.abrir(Papel.FORNECEDOR, f.id(), f.nome()), "papel", "FORNECEDOR",
                "nome", f.nome(), "cnpj", f.cnpj());
    }

    @POST
    @Path("/sessao/operador")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> entrarOperador(LoginOperador login) {
        if (!operadorUsuario.equals(login.usuario()) || !operadorSenha.equals(login.senha())) {
            throw new NotAuthorizedException("Bearer");
        }
        return Map.of("token", sessoes.abrir(Papel.OPERADOR, 0, "Operador do recebimento"), "papel", "OPERADOR",
                "nome", "Operador do recebimento");
    }

    // ------------------------------------------------------------------ fornecedor

    @GET
    @Path("/pedidos")
    public List<Map<String, Object>> pedidos(@HeaderParam("Authorization") String auth) {
        return erp.pedidosEmAberto(sessoes.exigir(auth, Papel.FORNECEDOR).fornecedorId());
    }

    @GET
    @Path("/pedidos/{id}")
    public Map<String, Object> pedido(@HeaderParam("Authorization") String auth, @PathParam("id") int id) {
        PedidoCompra p = pedidoDoFornecedor(sessoes.exigir(auth, Papel.FORNECEDOR), id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pedido", p);
        m.put("valorTotal", p.valorTotal());
        m.put("exigeDocaRefrigerada", p.temPerecivel());
        m.put("agendamento", agenda.agendamentoAtivoDoPedido(id).orElse(null));
        return m;
    }

    @GET
    @Path("/pedidos/{id}/nfe-exemplo")
    @Produces(MediaType.APPLICATION_XML)
    public String nfeExemplo(@HeaderParam("Authorization") String auth, @PathParam("id") int id,
                             @QueryParam("cenario") @DefaultValue("CORRETA") GeradorNFeExemplo.Cenario cenario) {
        PedidoCompra p = pedidoDoFornecedor(sessoes.exigir(auth, Papel.FORNECEDOR), id);
        return GeradorNFeExemplo.gerar(p, cenario, System.nanoTime());
    }

    @POST
    @Path("/pedidos/{id}/validar")
    @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.TEXT_PLAIN})
    public ResultadoConfronto validar(@HeaderParam("Authorization") String auth, @PathParam("id") int id, String xml) {
        PedidoCompra p = pedidoDoFornecedor(sessoes.exigir(auth, Papel.FORNECEDOR), id);
        NotaFiscal nota = LeitorNFe.ler(xml);
        return new Confronto(tolerancia).confrontar(p, nota, agenda.chaveEmUso(nota.chave()));
    }

    @GET
    @Path("/pedidos/{id}/disponibilidade")
    public Map<String, Object> disponibilidade(@HeaderParam("Authorization") String auth, @PathParam("id") int id,
                                               @QueryParam("data") LocalDate data) {
        PedidoCompra p = pedidoDoFornecedor(sessoes.exigir(auth, Papel.FORNECEDOR), id);
        if (data == null) {
            throw new BadRequestException("informe a data");
        }
        String motivo = motivoDataInvalida(data);
        Set<String> ocupados = new HashSet<>();
        for (Map<String, Object> o : agenda.ocupados(p.empresaId(), data)) {
            ocupados.add(o.get("doca_id") + "@" + o.get("hora"));
        }
        List<Map<String, Object>> docas = new ArrayList<>();
        for (Map<String, Object> d : agenda.docas(p.empresaId())) {
            boolean refrigerada = Boolean.TRUE.equals(d.get("refrigerada"));
            if (p.temPerecivel() && !refrigerada) {
                continue;               // perecível só em doca refrigerada (cadeia de frio)
            }
            List<Map<String, Object>> slots = new ArrayList<>();
            for (LocalTime h : horarios) {
                slots.add(Map.of("hora", h.toString(), "livre", motivo == null && !ocupados.contains(d.get("id") + "@" + h)));
            }
            docas.add(Map.of("id", d.get("id"), "numero", d.get("numero"), "refrigerada", refrigerada, "horarios", slots));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("data", data.toString());
        r.put("motivoIndisponivel", motivo);
        r.put("docas", docas);
        return r;
    }

    @POST
    @Path("/agendamentos")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response agendar(@HeaderParam("Authorization") String auth, NovoAgendamento req) {
        Sessao s = sessoes.exigir(auth, Papel.FORNECEDOR);
        PedidoCompra p = pedidoDoFornecedor(s, req.pedidoId());
        if (req.data() == null || req.hora() == null || req.xml() == null) {
            throw new BadRequestException("pedidoId, docaId, data, hora e xml são obrigatórios");
        }
        String motivo = motivoDataInvalida(req.data());
        if (motivo != null) {
            throw new RegraDeAgendamento(motivo);
        }
        if (!horarios.contains(req.hora())) {
            throw new RegraDeAgendamento("Horário fora das janelas de recebimento: " + horarios);
        }
        Map<String, Object> doca = agenda.doca(req.docaId()).orElseThrow(() -> new RegraDeAgendamento("Doca inexistente"));
        if (((Number) doca.get("empresa_id")).intValue() != p.empresaId()) {
            throw new RegraDeAgendamento("A doca escolhida não é da unidade de destino do pedido");
        }
        if (p.temPerecivel() && !Boolean.TRUE.equals(doca.get("refrigerada"))) {
            throw new RegraDeAgendamento("Pedido com perecíveis exige doca refrigerada");
        }
        NotaFiscal nota = LeitorNFe.ler(req.xml());
        ResultadoConfronto r = new Confronto(tolerancia).confrontar(p, nota, agenda.chaveEmUso(nota.chave()));
        if (r.bloqueada()) {
            return Response.status(422).entity(Map.of("erro", "NF-e bloqueada no confronto com o pedido", "confronto", r)).build();
        }
        long id = agenda.agendar(p, nota, r, req.xml(), req.docaId(), req.data(), req.hora());
        return Response.status(201).entity(Map.of("id", id, "resultado", r.status(), "divergencias", r.divergencias())).build();
    }

    @GET
    @Path("/agendamentos")
    public List<Map<String, Object>> meusAgendamentos(@HeaderParam("Authorization") String auth) {
        return agenda.doFornecedor(sessoes.exigir(auth, Papel.FORNECEDOR).fornecedorId());
    }

    @DELETE
    @Path("/agendamentos/{id}")
    public Response cancelar(@HeaderParam("Authorization") String auth, @PathParam("id") long id) {
        Sessao s = sessoes.exigir(auth, Papel.FORNECEDOR);
        Map<String, Object> a = agenda.agendamento(id).orElseThrow(NotFoundException::new);
        if (((Number) a.get("fornecedor_id")).longValue() != s.fornecedorId()) {
            throw new NotFoundException();
        }
        if (!"AGENDADO".equals(a.get("status"))) {
            throw new RegraDeAgendamento("Só agendamentos ainda não recebidos podem ser cancelados");
        }
        agenda.cancelar(id);
        return Response.noContent().build();
    }

    // ------------------------------------------------------------------ operação do CD / loja

    @GET
    @Path("/operacao/agenda")
    public Map<String, Object> agendaDoDia(@HeaderParam("Authorization") String auth, @QueryParam("empresa") @DefaultValue("1") int empresa,
                                           @QueryParam("data") LocalDate data) {
        sessoes.exigir(auth, Papel.OPERADOR);
        LocalDate dia = data == null ? LocalDate.now() : data;
        return Map.of("empresa", empresa, "data", dia.toString(), "docas", agenda.docas(empresa), "agendamentos", agenda.doDia(empresa, dia));
    }

    @POST
    @Path("/operacao/agendamentos/{id}/recebido")
    public Response receber(@HeaderParam("Authorization") String auth, @PathParam("id") long id) {
        sessoes.exigir(auth, Papel.OPERADOR);
        Map<String, Object> a = agenda.agendamento(id).orElseThrow(NotFoundException::new);
        if (!"AGENDADO".equals(a.get("status"))) {
            throw new RegraDeAgendamento("Agendamento já está " + a.get("status"));
        }
        PedidoCompra p = erp.pedido(((Number) a.get("pedido_compra_id")).intValue()).orElseThrow();
        String xml = erp.xmlDaNota(((Number) a.get("nota_entrada_id")).longValue());
        ResultadoConfronto r = new Confronto(tolerancia).confrontar(p, LeitorNFe.ler(xml), false);
        agenda.receber(id, p, r);
        return Response.noContent().build();
    }

    @POST
    @Path("/operacao/agendamentos/{id}/nao-compareceu")
    public Response naoCompareceu(@HeaderParam("Authorization") String auth, @PathParam("id") long id) {
        sessoes.exigir(auth, Papel.OPERADOR);
        agenda.agendamento(id).orElseThrow(NotFoundException::new);
        agenda.naoCompareceu(id);
        return Response.noContent().build();
    }

    // ------------------------------------------------------------------ regras

    private PedidoCompra pedidoDoFornecedor(Sessao s, int id) {
        PedidoCompra p = erp.pedido(id).orElseThrow(() -> new NotFoundException("Pedido " + id + " não encontrado"));
        if (p.fornecedorId() != s.fornecedorId()) {
            throw new NotFoundException("Pedido " + id + " não encontrado");   // não revela pedido de outro fornecedor
        }
        return p;
    }

    private String motivoDataInvalida(LocalDate data) {
        if (data.isBefore(LocalDate.now().plusDays(antecedencia))) {
            return "Agende com pelo menos " + antecedencia + " dia(s) de antecedência";
        }
        if (data.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return "Não há recebimento aos domingos";
        }
        if (data.isAfter(LocalDate.now().plusDays(30))) {
            return "Agenda aberta só para os próximos 30 dias";
        }
        return null;
    }
}
