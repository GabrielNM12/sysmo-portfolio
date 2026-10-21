package br.portfolio.integrador.motor;

/** Onde o motor registra cada execução e cada tentativa de etapa (o "log por etapa" do Integrador). */
public interface LogDeExecucao {

    long iniciar(String grupo);

    void etapa(long execucao, String etapa, String item, int tentativa, String status, long duracaoMs, String mensagem);

    void finalizar(long execucao, ResumoExecucao resumo);
}
