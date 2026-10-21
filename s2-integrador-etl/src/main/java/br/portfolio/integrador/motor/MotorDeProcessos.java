package br.portfolio.integrador.motor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Executa um grupo de processos: etapas em sequência, laços por item, condições,
 * retentativa com espera exponencial para falhas transitórias e log de cada tentativa.
 */
@ApplicationScoped
public class MotorDeProcessos {

    private static final Logger LOG = Logger.getLogger(MotorDeProcessos.class);

    private final RegistroDeEtapas registro;
    private final LogDeExecucao log;
    private final Espera espera;

    /** Abstração do sleep para os testes não esperarem de verdade. */
    @FunctionalInterface
    public interface Espera {
        void dormir(long ms) throws InterruptedException;
    }

    @Inject
    public MotorDeProcessos(RegistroDeEtapas registro, LogDeExecucao log) {
        this(registro, log, Thread::sleep);
    }

    public MotorDeProcessos(RegistroDeEtapas registro, LogDeExecucao log, Espera espera) {
        this.registro = registro;
        this.log = log;
        this.espera = espera;
    }

    private static final class Contadores {
        int ok;
        int erro;
        int ignorados;
    }

    public ResumoExecucao executar(DefinicaoGrupo grupo) {
        long inicio = System.currentTimeMillis();
        long execucao = log.iniciar(grupo.nome());
        Contadores c = new Contadores();
        String status = "OK";
        String mensagem = null;
        try {
            executarEtapas(execucao, grupo.etapas(), new Contexto(grupo.variaveis()), null, c);
            if (c.erro > 0) {
                status = "PARCIAL";
                mensagem = c.erro + " item(ns) com erro: ver o log das etapas";
            }
        } catch (FalhaDeEtapa f) {
            status = "ERRO";
            mensagem = f.getMessage();
        }
        ResumoExecucao resumo = new ResumoExecucao(execucao, grupo.nome(), status, c.ok, c.erro, c.ignorados,
                System.currentTimeMillis() - inicio, mensagem);
        log.finalizar(execucao, resumo);
        LOG.infof("Grupo %s terminou %s: %d ok, %d com erro, %d já integrados", grupo.nome(), status, c.ok, c.erro, c.ignorados);
        return resumo;
    }

    private void executarEtapas(long execucao, List<DefinicaoEtapa> etapas, Contexto ctx, String item, Contadores c) {
        for (DefinicaoEtapa etapa : etapas) {
            if (etapa.ehLaco()) {
                executarLaco(execucao, etapa, ctx, c);
            } else if (etapa.executarSe() != null && !ctx.avaliar(etapa.executarSe())) {
                log.etapa(execucao, etapa.nome(), item, 0, "IGNORADA", 0, "condição '" + etapa.executarSe() + "' falsa");
            } else {
                executarComRetentativa(execucao, etapa, ctx, item);
            }
        }
    }

    private void executarLaco(long execucao, DefinicaoEtapa laco, Contexto ctx, Contadores c) {
        Object valor = ctx.obter(laco.paraCada());
        if (valor == null) {
            valor = List.of();
        }
        if (!(valor instanceof List<?> itens)) {
            throw new FalhaDeEtapa(laco.nome(), "'" + laco.paraCada() + "' não é uma lista", null);
        }
        String nome = laco.como() == null ? "item" : laco.como();
        log.etapa(execucao, laco.nome(), null, 1, "OK", 0, itens.size() + " item(ns) para processar");
        for (int i = 0; i < itens.size(); i++) {
            Object atual = itens.get(i);
            Contexto ctxItem = ctx.paraItem(nome, atual);
            String rotulo = rotulo(nome, atual, i);
            try {
                executarEtapas(execucao, laco.etapas(), ctxItem, rotulo, c);
                if (Boolean.TRUE.equals(ctxItem.obter("ja_integrado"))) {
                    c.ignorados++;
                } else {
                    c.ok++;
                }
            } catch (FalhaDeEtapa f) {
                c.erro++;    // o item fica pendente; os próximos continuam
            }
        }
    }

    private static String rotulo(String nome, Object item, int indice) {
        if (item instanceof Map<?, ?> m && m.get("id") != null) {
            return nome + " " + m.get("id");
        }
        return nome + " #" + (indice + 1);
    }

    private void executarComRetentativa(long execucao, DefinicaoEtapa def, Contexto ctx, String item) {
        Etapa etapa = registro.obter(def.tipo());
        int maximo = def.tentativasMaximas();
        for (int tentativa = 1; ; tentativa++) {
            long inicio = System.currentTimeMillis();
            try {
                String msg = etapa.executar(ctx, ctx.resolverParametros(def.parametros()));
                log.etapa(execucao, def.nome(), item, tentativa, "OK", System.currentTimeMillis() - inicio, msg);
                return;
            } catch (Exception e) {
                long duracao = System.currentTimeMillis() - inicio;
                String msg = descrever(e);
                if (transitorio(e) && tentativa < maximo) {
                    long aguardar = def.intervaloBaseMs() * (1L << (tentativa - 1));   // 1x, 2x, 4x...
                    log.etapa(execucao, def.nome(), item, tentativa, "RETENTATIVA", duracao, msg + " (nova tentativa em " + aguardar + " ms)");
                    try {
                        espera.dormir(aguardar);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new FalhaDeEtapa(def.nome(), "interrompido", ie);
                    }
                    continue;
                }
                log.etapa(execucao, def.nome(), item, tentativa, "ERRO", duracao, msg);
                if (!(e instanceof ErroDeNegocio)) {
                    LOG.warnf(e, "Etapa %s falhou (%s)", def.nome(), item);
                }
                throw new FalhaDeEtapa(def.nome(), msg, e);
            }
        }
    }

    static boolean transitorio(Throwable e) {
        if (e instanceof ErroTransitorio || e instanceof IOException) {
            return true;
        }
        if (e instanceof SQLException s && s.getSQLState() != null) {
            String estado = s.getSQLState();
            return estado.startsWith("08") || estado.equals("40001") || estado.equals("40P01");   // conexão, serialização, deadlock
        }
        return false;
    }

    private static String descrever(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }
}
