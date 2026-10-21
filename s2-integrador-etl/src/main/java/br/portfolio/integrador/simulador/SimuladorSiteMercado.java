package br.portfolio.integrador.simulador;

import br.portfolio.integrador.banco.Documentos;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado em memória de uma plataforma de e-commerce de supermercado (contrato inspirado em
 * SiteMercado/iFood, não é a API real). Gera pedidos a partir do catálogo do ERP de laboratório
 * e permite injetar falhas para demonstrar retentativa, pendência e idempotência.
 */
@ApplicationScoped
public class SimuladorSiteMercado {

    public enum Cenario { NORMAL, PESAVEL, ITEM_EM_FALTA, PRECO_PROMOCIONAL, EAN_DESCONHECIDO, CPF_INVALIDO, EM_SEPARACAO, RETIRADA }

    private static final String[][] CLIENTES = {
            {"Ana Paula Zanella", "48991230001"}, {"Bruno Kunz", "49999120002"}, {"Carla Bortolini", "49988110003"},
            {"Diego Fritsch", "49991450004"}, {"Elaine Dal Pizzol", "49984560005"}, {"Fábio Lazzari", "49998760006"}};

    private final Map<String, Map<String, Object>> pedidos = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong sequencia = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() / 10);
    private final AtomicInteger falhasPendentes = new AtomicInteger();
    private final Map<String, Boolean> tokens = new ConcurrentHashMap<>();
    private final Random sorteio = new Random(42);

    @Inject
    AgroalDataSource ds;

    /** Consome uma falha injetada (a próxima chamada devolve HTTP 503). */
    public boolean deveFalhar() {
        return falhasPendentes.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
    }

    public void injetarFalhas(int quantidade) {
        falhasPendentes.set(quantidade);
    }

    public String emitirToken() {
        String token = "tk-" + Long.toHexString(sorteio.nextLong());
        tokens.put(token, true);
        return token;
    }

    public boolean tokenValido(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") && tokens.containsKey(authorization.substring(7));
    }

    public List<Map<String, Object>> listar(String status, Boolean integrado) {
        return pedidos.values().stream()
                .filter(p -> status == null || status.equals(p.get("status")))
                .filter(p -> integrado == null || integrado.equals(p.get("numeroErp") != null))
                .sorted((a, b) -> ((String) a.get("id")).compareTo((String) b.get("id")))
                .toList();
    }

    public Collection<Map<String, Object>> todos() {
        return pedidos.values();
    }

    public boolean confirmar(String id, String numeroErp) {
        Map<String, Object> p = pedidos.get(id);
        if (p == null) {
            return false;
        }
        p.put("numeroErp", numeroErp);
        p.put("integradoEm", OffsetDateTime.now(ZoneOffset.ofHours(-3)).toString());
        return true;
    }

    /** Simula a plataforma "perdendo" a confirmação: o pedido volta a aparecer como não integrado. */
    public boolean desconfirmar(String id) {
        Map<String, Object> p = pedidos.get(id);
        if (p == null) {
            return false;
        }
        p.remove("numeroErp");
        p.remove("integradoEm");
        return true;
    }

    public void limpar() {
        pedidos.clear();
        falhasPendentes.set(0);
    }

    public synchronized List<Map<String, Object>> gerar(int quantidade, Cenario cenario, int empresa) throws SQLException {
        List<Map<String, Object>> criados = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            String[] loja = loja(c, empresa);
            for (int n = 0; n < quantidade; n++) {
                Map<String, Object> p = novoPedido(c, cenario, empresa, loja);
                pedidos.put((String) p.get("id"), p);
                criados.add(p);
            }
        }
        return criados;
    }

    private Map<String, Object> novoPedido(Connection c, Cenario cenario, int empresa, String[] loja) throws SQLException {
        String[] cli = CLIENTES[sorteio.nextInt(CLIENTES.length)];
        String cpf = Documentos.cpfComDv(String.format("%09d", 300_000_000 + sorteio.nextInt(1_000_000)));
        if (cenario == Cenario.CPF_INVALIDO) {
            cpf = cpf.substring(0, 10) + ((cpf.charAt(10) - '0' + 1) % 10);
        }
        List<Map<String, Object>> itens = new ArrayList<>();
        for (Object[] prod : produtos(c, empresa, 2 + sorteio.nextInt(4), false)) {
            itens.add(item(prod, BigDecimal.valueOf(1 + sorteio.nextInt(3)), null));
        }
        switch (cenario) {
            case PESAVEL -> {
                Object[] pes = produtos(c, empresa, 1, true).get(0);
                BigDecimal pedido = new BigDecimal("1.000");
                BigDecimal separado = new BigDecimal("1.085");          // a balança da separação pesa diferente do pedido
                itens.add(item(pes, pedido, separado));
            }
            case ITEM_EM_FALTA -> itens.get(0).put("quantidadeSeparada", BigDecimal.ZERO);
            case PRECO_PROMOCIONAL -> itens.get(0).put("precoUnitario",
                    ((BigDecimal) itens.get(0).get("precoUnitario")).multiply(new BigDecimal("0.85")).setScale(2, RoundingMode.HALF_UP));
            case EAN_DESCONHECIDO -> {
                String ean = "789999" + String.format("%06d", sorteio.nextInt(1_000_000));
                itens.add(item(new Object[]{0L, "PRODUTO SÓ DO SITE", ean + Documentos.dvGtin(ean), new BigDecimal("9.90"), null}, BigDecimal.ONE, null));
            }
            default -> { }
        }
        BigDecimal produtos = itens.stream()
                .map(i -> ((BigDecimal) i.get("quantidadeSeparada")).multiply((BigDecimal) i.get("precoUnitario")))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        boolean retirada = cenario == Cenario.RETIRADA;
        BigDecimal taxa = retirada ? BigDecimal.ZERO : new BigDecimal("9.90");

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "SM-" + sequencia.incrementAndGet());
        p.put("status", cenario == Cenario.EM_SEPARACAO ? "EM_SEPARACAO" : "SEPARACAO_FINALIZADA");
        p.put("criadoEm", OffsetDateTime.now(ZoneOffset.ofHours(-3)).withNano(0).toString());
        p.put("loja", Map.of("cnpj", formatarCnpj(loja[0]), "nome", loja[1]));
        p.put("cliente", Map.of("nome", cli[0], "cpf", cpf, "email", cli[0].split(" ")[0].toLowerCase() + "@email.example",
                "telefone", "(49) " + cli[1].substring(2), "aceitaComunicacao", sorteio.nextBoolean()));
        Map<String, Object> entrega = new LinkedHashMap<>();
        entrega.put("tipo", retirada ? "RETIRADA" : "ENTREGA");
        entrega.put("taxa", taxa);
        if (!retirada) {
            entrega.put("endereco", Map.of("cep", "89900-000", "logradouro", "Rua Willy Barth", "numero",
                    String.valueOf(100 + sorteio.nextInt(900)), "bairro", "Centro", "cidade", "São Miguel do Oeste", "uf", "SC"));
        }
        p.put("entrega", entrega);
        p.put("pagamento", Map.of("forma", sorteio.nextBoolean() ? "CREDITO" : "PIX", "valor", produtos.add(taxa)));
        p.put("desconto", BigDecimal.ZERO);
        p.put("observacao", sorteio.nextInt(3) == 0 ? "Deixar na portaria" : null);
        p.put("itens", itens);
        return p;
    }

    private Map<String, Object> item(Object[] prod, BigDecimal pedida, BigDecimal separada) {
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("sku", "SKU-" + prod[0]);
        it.put("ean", prod[2]);
        it.put("codigoInterno", prod[4] == null ? null : String.valueOf(prod[4]));
        it.put("descricao", prod[1]);
        it.put("unidade", prod[4] == null ? "UN" : "KG");
        it.put("quantidadePedida", pedida);
        it.put("quantidadeSeparada", separada == null ? pedida : separada);
        it.put("precoUnitario", prod[3]);
        return it;
    }

    /** id, descrição, EAN, preço vigente, PLU (pesável). */
    private List<Object[]> produtos(Connection c, int empresa, int quantidade, boolean pesavel) throws SQLException {
        String sql = """
                select p.id, p.descricao, e.ean, v.preco, p.codigo_balanca
                  from erp.produto p
                  join erp.produto_loja pl on pl.produto_id = p.id and pl.empresa_id = ? and pl.ativo
                  left join erp.produto_embalagem e on e.produto_id = p.id and e.venda and e.ean is not null
                 cross join lateral erp.fn_preco_vigente(?::smallint, p.id) v
                 where p.ativo and p.pesavel = ? and (p.pesavel or e.ean is not null)
                 order by md5(p.id::text || ?) limit ?""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, empresa);
            ps.setInt(2, empresa);
            ps.setBoolean(3, pesavel);
            ps.setString(4, String.valueOf(sorteio.nextInt()));
            ps.setInt(5, quantidade);
            List<Object[]> lista = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getBigDecimal(4).setScale(2, RoundingMode.HALF_UP), rs.getObject(5)});
                }
            }
            if (lista.isEmpty()) {
                throw new IllegalStateException("Catálogo vazio para a empresa " + empresa + ": carregue o S1 antes");
            }
            return lista;
        }
    }

    private static String[] loja(Connection c, int empresa) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select cnpj, fantasia from erp.empresa where id = ?")) {
            ps.setInt(1, empresa);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Empresa " + empresa + " não existe");
                }
                return new String[]{rs.getString(1), rs.getString(2)};
            }
        }
    }

    private static String formatarCnpj(String d) {
        return d.replaceFirst("(\\d{2})(\\d{3})(\\d{3})(\\d{4})(\\d{2})", "$1.$2.$3/$4-$5");
    }
}
