package br.portfolio.recebimento.api;

import br.portfolio.recebimento.banco.Agenda;
import br.portfolio.recebimento.nfe.LeitorNFe;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/** Erros de entrada viram respostas claras para o fornecedor (e não 500). */
public final class Mapeadores {

    private Mapeadores() {
    }

    @Provider
    public static class XmlInvalido implements ExceptionMapper<LeitorNFe.XmlInvalido> {
        @Override
        public Response toResponse(LeitorNFe.XmlInvalido e) {
            return Response.status(422).entity(Map.of("erro", e.getMessage())).build();
        }
    }

    @Provider
    public static class HorarioOcupado implements ExceptionMapper<Agenda.HorarioOcupado> {
        @Override
        public Response toResponse(Agenda.HorarioOcupado e) {
            return Response.status(409).entity(Map.of("erro", e.getMessage())).build();
        }
    }

    @Provider
    public static class RegraViolada implements ExceptionMapper<RegraDeAgendamento> {
        @Override
        public Response toResponse(RegraDeAgendamento e) {
            return Response.status(422).entity(Map.of("erro", e.getMessage())).build();
        }
    }

    public static class RegraDeAgendamento extends RuntimeException {
        public RegraDeAgendamento(String m) {
            super(m);
        }
    }
}
