package br.portfolio.integrador.motor;

/** Dado inválido ou regra de negócio violada: tentar de novo não resolve, o item vai para pendência. */
public class ErroDeNegocio extends RuntimeException {
    public ErroDeNegocio(String mensagem) {
        super(mensagem);
    }
}
