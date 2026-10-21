package br.portfolio.integrador.motor;

/** Etapa esgotou as tentativas ou falhou por regra de negócio: interrompe o item (ou o grupo). */
public class FalhaDeEtapa extends RuntimeException {
    private final String etapa;

    public FalhaDeEtapa(String etapa, String mensagem, Throwable causa) {
        super(etapa + ": " + mensagem, causa);
        this.etapa = etapa;
    }

    public String etapa() {
        return etapa;
    }
}
