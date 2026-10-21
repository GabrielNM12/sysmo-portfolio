package br.portfolio.integrador.motor;

/** Falha temporária (sistema externo fora do ar, limite de requisições): o motor tenta de novo. */
public class ErroTransitorio extends RuntimeException {
    public ErroTransitorio(String mensagem) {
        super(mensagem);
    }

    public ErroTransitorio(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
