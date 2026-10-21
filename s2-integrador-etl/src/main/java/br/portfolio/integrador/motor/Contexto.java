package br.portfolio.integrador.motor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Variáveis da execução. Há o escopo global (variáveis do grupo e o que as etapas publicam,
 * como o token da API) e, dentro de um laço, o escopo do item (o pedido atual).
 * A busca procura primeiro no item e depois no global, como as "variáveis globais" do Integrador.
 */
public final class Contexto {

    private static final Pattern EXPRESSAO = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<String, Object> globais;
    private final Map<String, Object> item;

    public Contexto(Map<String, Object> variaveis) {
        this(new HashMap<>(variaveis), null);
    }

    private Contexto(Map<String, Object> globais, Map<String, Object> item) {
        this.globais = globais;
        this.item = item;
    }

    /** Novo contexto para um item do laço; compartilha o escopo global. */
    public Contexto paraItem(String nome, Object valor) {
        Map<String, Object> escopo = new HashMap<>();
        escopo.put(nome, valor);
        return new Contexto(globais, escopo);
    }

    public boolean dentroDeItem() {
        return item != null;
    }

    /** Define no escopo atual: no item, se estiver num laço; senão no global. */
    public void definir(String nome, Object valor) {
        (item != null ? item : globais).put(nome, valor);
    }

    public void definirGlobal(String nome, Object valor) {
        globais.put(nome, valor);
    }

    /** Navega por mapas e listas: "pedido.cliente.cpf", "pedido.itens.0.ean". */
    public Object obter(String caminho) {
        String[] partes = caminho.trim().split("\\.");
        Object atual = null;
        if (item != null && item.containsKey(partes[0])) {
            atual = item.get(partes[0]);
        } else if (globais.containsKey(partes[0])) {
            atual = globais.get(partes[0]);
        } else {
            return null;
        }
        for (int i = 1; i < partes.length && atual != null; i++) {
            if (atual instanceof Map<?, ?> mapa) {
                atual = mapa.get(partes[i]);
            } else if (atual instanceof List<?> lista && partes[i].matches("\\d+")) {
                int indice = Integer.parseInt(partes[i]);
                atual = indice < lista.size() ? lista.get(indice) : null;
            } else {
                return null;
            }
        }
        return atual;
    }

    public String texto(String caminho) {
        Object valor = obter(caminho);
        return valor == null ? null : valor.toString();
    }

    public String exigirTexto(String caminho) {
        String valor = texto(caminho);
        if (valor == null || valor.isBlank()) {
            throw new ErroDeNegocio("campo obrigatório ausente: " + caminho);
        }
        return valor;
    }

    /** "${x}" sozinho devolve o objeto original; expressões dentro de texto viram texto. */
    public Object resolver(Object valor) {
        if (!(valor instanceof String s)) {
            return valor;
        }
        Matcher inteiro = EXPRESSAO.matcher(s);
        if (inteiro.matches()) {
            return obter(inteiro.group(1));
        }
        Matcher m = EXPRESSAO.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = obter(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public Map<String, Object> resolverParametros(Map<String, Object> parametros) {
        Map<String, Object> resolvidos = new HashMap<>();
        parametros.forEach((k, v) -> resolvidos.put(k, resolver(v)));
        return resolvidos;
    }

    /** Condição simples: "variavel" (verdadeira se não nula/false) ou "!variavel". */
    public boolean avaliar(String condicao) {
        String c = condicao.trim();
        boolean negar = c.startsWith("!");
        Object valor = obter(negar ? c.substring(1) : c);
        boolean verdadeiro = valor != null && !Boolean.FALSE.equals(valor) && !"false".equals(valor);
        return negar != verdadeiro;
    }
}
