package br.portfolio.integrador;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.DefinicaoEtapa;
import br.portfolio.integrador.motor.DefinicaoGrupo;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.ErroTransitorio;
import br.portfolio.integrador.motor.Etapa;
import br.portfolio.integrador.motor.LogDeExecucao;
import br.portfolio.integrador.motor.MotorDeProcessos;
import br.portfolio.integrador.motor.RegistroDeEtapas;
import br.portfolio.integrador.motor.ResumoExecucao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotorDeProcessosTest {

    /** Log em memória: cada linha "etapa|item|tentativa|status". */
    static class LogEmMemoria implements LogDeExecucao {
        final List<String> linhas = new ArrayList<>();
        ResumoExecucao resumo;

        public long iniciar(String grupo) {
            return 1;
        }

        public void etapa(long e, String etapa, String item, int tentativa, String status, long ms, String msg) {
            linhas.add(etapa + "|" + item + "|" + tentativa + "|" + status);
        }

        public void finalizar(long e, ResumoExecucao r) {
            resumo = r;
        }
    }

    @FunctionalInterface
    interface Corpo {
        String executar(Contexto c, Map<String, Object> p) throws Exception;
    }

    record EtapaFake(String tipo, Corpo corpo) implements Etapa {
        @Override
        public String executar(Contexto c, Map<String, Object> p) throws Exception {
            return corpo.executar(c, p);
        }
    }

    private final List<Long> esperas = new ArrayList<>();
    private final LogEmMemoria log = new LogEmMemoria();

    private MotorDeProcessos motor(Etapa... etapas) {
        return new MotorDeProcessos(new RegistroDeEtapas(List.of(etapas)), log, esperas::add);
    }

    private static DefinicaoEtapa etapa(String nome, String tipo, Integer retentativas, String executarSe) {
        return new DefinicaoEtapa(nome, tipo, null, retentativas, 100L, executarSe, null, null, null);
    }

    @Test
    void retentaFalhaTransitoriaComEsperaExponencial() {
        AtomicInteger chamadas = new AtomicInteger();
        Etapa instavel = new EtapaFake("api", (c, p) -> {
            if (chamadas.incrementAndGet() < 3) {
                throw new ErroTransitorio("HTTP 503");
            }
            return "ok";
        });
        ResumoExecucao r = motor(instavel).executar(new DefinicaoGrupo("G", null, false, Map.of(),
                List.of(etapa("LOGIN", "api", 3, null))));
        assertEquals("OK", r.status());
        assertEquals(List.of("LOGIN|null|1|RETENTATIVA", "LOGIN|null|2|RETENTATIVA", "LOGIN|null|3|OK"), log.linhas);
        assertEquals(List.of(100L, 200L), esperas);
    }

    @Test
    void erroDeNegocioNaoRetentaEInterrompeOGrupo() {
        Etapa invalida = new EtapaFake("x", (c, p) -> {
            throw new ErroDeNegocio("webservice não cadastrado");
        });
        ResumoExecucao r = motor(invalida).executar(new DefinicaoGrupo("G", null, false, Map.of(),
                List.of(etapa("BUSCAR", "x", 5, null))));
        assertEquals("ERRO", r.status());
        assertTrue(r.mensagem().contains("webservice não cadastrado"));
        assertEquals(List.of("BUSCAR|null|1|ERRO"), log.linhas);
        assertTrue(esperas.isEmpty());
    }

    @Test
    void itemComErroNaoImpedeOsOutrosECondicaoPulaEtapa() {
        Etapa validar = new EtapaFake("validar", (c, p) -> {
            if ("B".equals(c.texto("pedido.id"))) {
                throw new ErroDeNegocio("CPF inválido");
            }
            c.definir("ja_integrado", "C".equals(c.texto("pedido.id")));
            return null;
        });
        List<String> gravados = new ArrayList<>();
        Etapa gravar = new EtapaFake("gravar", (c, p) -> {
            gravados.add(c.texto("pedido.id"));
            return null;
        });
        DefinicaoEtapa laco = new DefinicaoEtapa("PEDIDOS", null, null, null, null, null, "pedidos", "pedido",
                List.of(etapa("VALIDAR", "validar", null, null), etapa("GRAVAR", "gravar", null, "!ja_integrado")));
        ResumoExecucao r = motor(validar, gravar).executar(new DefinicaoGrupo("G", null, false,
                Map.of("pedidos", List.of(Map.of("id", "A"), Map.of("id", "B"), Map.of("id", "C"))), List.of(laco)));

        assertEquals("PARCIAL", r.status());
        assertEquals(1, r.itensOk());
        assertEquals(1, r.itensComErro());
        assertEquals(1, r.itensIgnorados());
        assertEquals(List.of("A"), gravados);
        assertTrue(log.linhas.contains("GRAVAR|pedido C|0|IGNORADA"));
        assertTrue(log.linhas.contains("VALIDAR|pedido B|1|ERRO"));
    }

    @Test
    void parametrosSaoResolvidosNoContexto() {
        List<Object> recebidos = new ArrayList<>();
        Etapa e = new EtapaFake("eco", (c, p) -> {
            recebidos.add(p.get("empresa"));
            return null;
        });
        DefinicaoEtapa def = new DefinicaoEtapa("ECO", "eco", Map.of("empresa", "${empresa_id}"), null, null, null, null, null, null);
        motor(e).executar(new DefinicaoGrupo("G", null, false, Map.of("empresa_id", 7), List.of(def)));
        assertEquals(List.of(7), recebidos);
    }
}
