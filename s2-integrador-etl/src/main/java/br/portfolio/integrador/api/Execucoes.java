package br.portfolio.integrador.api;

import br.portfolio.integrador.motor.CarregadorDeGrupos;
import br.portfolio.integrador.motor.DefinicaoGrupo;
import br.portfolio.integrador.motor.MotorDeProcessos;
import br.portfolio.integrador.motor.ResumoExecucao;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Garante uma execução por grupo de cada vez (agendada ou manual) e dispara os grupos agendados. */
@ApplicationScoped
public class Execucoes {

    private final Map<String, ReentrantLock> travas = new ConcurrentHashMap<>();

    @Inject
    MotorDeProcessos motor;

    @Inject
    CarregadorDeGrupos grupos;

    @ConfigProperty(name = "integrador.agendamento.habilitado", defaultValue = "true")
    boolean agendamentoHabilitado;

    public Optional<ResumoExecucao> executar(DefinicaoGrupo grupo) {
        ReentrantLock trava = travas.computeIfAbsent(grupo.nome(), k -> new ReentrantLock());
        if (!trava.tryLock()) {
            return Optional.empty();
        }
        try {
            return Optional.of(motor.executar(grupo));
        } finally {
            trava.unlock();
        }
    }

    @Scheduled(every = "{integrador.agendamento.intervalo}", delayed = "10s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void agendado() {
        if (!agendamentoHabilitado) {
            return;
        }
        grupos.todos().stream().filter(DefinicaoGrupo::agendado).forEach(this::executar);
    }
}
