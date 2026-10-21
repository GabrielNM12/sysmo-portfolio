package br.portfolio.integrador.banco;

/** Validação de CPF/CNPJ e GTIN: o integrador não deixa documento inválido entrar no ERP. */
public final class Documentos {

    private Documentos() {
    }

    public static String digitos(String valor) {
        return valor == null ? "" : valor.replaceAll("\\D", "");
    }

    public static boolean cpfValido(String cpf) {
        String d = digitos(cpf);
        if (d.length() != 11 || d.chars().distinct().count() == 1) {
            return false;
        }
        return d.equals(cpfComDv(d.substring(0, 9)));
    }

    public static String cpfComDv(String base9) {
        int soma = 0;
        for (int i = 0; i < 9; i++) {
            soma += (base9.charAt(i) - '0') * (10 - i);
        }
        int dv1 = soma % 11 < 2 ? 0 : 11 - soma % 11;
        soma = 0;
        String com1 = base9 + dv1;
        for (int i = 0; i < 10; i++) {
            soma += (com1.charAt(i) - '0') * (11 - i);
        }
        int dv2 = soma % 11 < 2 ? 0 : 11 - soma % 11;
        return com1 + dv2;
    }

    public static boolean cnpjValido(String cnpj) {
        String d = digitos(cnpj);
        if (d.length() != 14 || d.chars().distinct().count() == 1) {
            return false;
        }
        int[] p1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] p2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int soma = 0;
        for (int i = 0; i < 12; i++) {
            soma += (d.charAt(i) - '0') * p1[i];
        }
        int dv1 = soma % 11 < 2 ? 0 : 11 - soma % 11;
        soma = 0;
        for (int i = 0; i < 12; i++) {
            soma += (d.charAt(i) - '0') * p2[i];
        }
        soma += dv1 * p2[12];
        int dv2 = soma % 11 < 2 ? 0 : 11 - soma % 11;
        return d.charAt(12) - '0' == dv1 && d.charAt(13) - '0' == dv2;
    }

    public static boolean gtinValido(String gtin) {
        String d = digitos(gtin);
        if (d.length() != 8 && d.length() != 12 && d.length() != 13 && d.length() != 14 || !d.equals(gtin)) {
            return false;
        }
        return d.charAt(d.length() - 1) - '0' == dvGtin(d.substring(0, d.length() - 1));
    }

    public static int dvGtin(String dados) {
        int soma = 0;
        for (int i = 0; i < dados.length(); i++) {
            int digito = dados.charAt(dados.length() - 1 - i) - '0';
            soma += digito * (i % 2 == 0 ? 3 : 1);
        }
        return (10 - soma % 10) % 10;
    }
}
