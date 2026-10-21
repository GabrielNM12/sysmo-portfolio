package br.portfolio.recebimento.nfe;

/** Chave de acesso de 44 dígitos: cUF AAMM CNPJ mod série número tpEmis cNF DV (módulo 11). */
public final class ChaveAcesso {

    private ChaveAcesso() {
    }

    public static int digito(String base43) {
        int soma = 0;
        int peso = 2;
        for (int i = base43.length() - 1; i >= 0; i--) {
            soma += (base43.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    public static boolean valida(String chave) {
        return chave != null && chave.matches("\\d{44}") && digito(chave.substring(0, 43)) == chave.charAt(43) - '0';
    }

    public static String montar(int cuf, int ano, int mes, String cnpj, int modelo, int serie, long numero, int codigo) {
        String base = String.format("%02d%02d%02d%014d%02d%03d%09d1%08d", cuf, ano % 100, mes, Long.parseLong(cnpj),
                modelo, serie, numero, codigo);
        return base + digito(base);
    }

    public static String cnpj(String chave) {
        return chave.substring(6, 20);
    }

    public static int modelo(String chave) {
        return Integer.parseInt(chave.substring(20, 22));
    }
}
