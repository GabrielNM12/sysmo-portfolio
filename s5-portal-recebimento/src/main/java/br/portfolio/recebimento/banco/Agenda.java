package br.portfolio.recebimento.banco;

import br.portfolio.recebimento.dominio.PedidoCompra;
import br.portfolio.recebimento.dominio.ResultadoConfronto;
import br.portfolio.recebimento.nfe.NotaFiscal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Docas e agendamentos no schema "portal". Conflito de horário é resolvido pelo banco
 * (índice único parcial): dois fornecedores clicando no mesmo horário, só um ganha.
 */
@ApplicationScoped
public class Agenda {

    private static final String DDL = """
            create table if not exists portal.doca (
                id          serial primary key,
                empresa_id  smallint not null,
                numero      smallint not null,
                refrigerada boolean  not null default false,
                unique (empresa_id, numero));
            create table if not exists portal.acesso (
                fornecedor_id bigint primary key,
                codigo_hash   text not null,
                ultimo_acesso timestamptz);
            create table if not exists portal.agendamento (
                id               bigserial primary key,
                empresa_id       smallint not null,
                doca_id          int      not null references portal.doca (id),
                data             date     not null,
                hora             time     not null,
                fornecedor_id    bigint   not null,
                pedido_compra_id int      not null,
                chave_nfe        char(44) not null,
                nota_entrada_id  int,
                resultado        text     not null,
                divergencias     jsonb    not null default '[]',
                status           text     not null default 'AGENDADO'
                                 check (status in ('AGENDADO', 'RECEBIDO', 'CANCELADO', 'NAO_COMPARECEU')),
                criado_em        timestamptz not null default now(),
                recebido_em      timestamptz);
            create unique index if not exists ux_agenda_horario on portal.agendamento (doca_id, data, hora) where status <> 'CANCELADO';
            create unique index if not exists ux_agenda_chave   on portal.agendamento (chave_nfe) where status <> 'CANCELADO';
            create unique index if not exists ux_agenda_pedido  on portal.agendamento (pedido_compra_id) where status = 'AGENDADO';
            insert into portal.doca (empresa_id, numero, refrigerada)
            select e.id, n, n > case when e.tipo = 'CD' then 4 else 1 end
              from erp.empresa e, generate_series(1, case when e.tipo = 'CD' then 6 else 2 end) n
            on conflict do nothing;
            """;

    public static class HorarioOcupado extends RuntimeException {
        public HorarioOcupado(String m) {
            super(m);
        }
    }

    @Inject
    AgroalDataSource ds;

    @Inject
    ObjectMapper json;

    void criar(@Observes StartupEvent e) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(DDL);
        }
    }

    public List<Map<String, Object>> docas(int empresa) {
        return consulta("select id, numero, refrigerada from portal.doca where empresa_id = ? order by numero", empresa);
    }

    public List<Map<String, Object>> ocupados(int empresa, LocalDate data) {
        return consulta("""
                select a.doca_id, a.hora from portal.agendamento a
                 where a.empresa_id = ? and a.data = ? and a.status <> 'CANCELADO'""", empresa, Date.valueOf(data));
    }

    public boolean chaveEmUso(String chave) {
        return !consulta("""
                select 1 from portal.agendamento where chave_nfe = ? and status <> 'CANCELADO'
                union all select 1 from erp.nota_entrada where chave = ? and status <> 'RECUSADA'""", chave, chave).isEmpty();
    }

    public Optional<Map<String, Object>> agendamentoAtivoDoPedido(int pedido) {
        return consulta("select id, data, hora from portal.agendamento where pedido_compra_id = ? and status = 'AGENDADO'", pedido)
                .stream().findFirst();
    }

    public Optional<Map<String, Object>> doca(int id) {
        return consulta("select id, empresa_id, numero, refrigerada from portal.doca where id = ?", id).stream().findFirst();
    }

    /** Grava nota de entrada (DIGITADA) no ERP e o agendamento, numa transação. */
    public long agendar(PedidoCompra pedido, NotaFiscal nota, ResultadoConfronto r, String xml, int docaId,
                        LocalDate data, LocalTime hora) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                long notaId;
                try (PreparedStatement ps = c.prepareStatement("""
                        insert into erp.nota_entrada (chave, numero, serie, fornecedor_id, empresa_id, pedido_compra_id, data_emissao,
                               valor_produtos, valor_total, status, xml)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DIGITADA', ?) returning id""")) {
                    ps.setString(1, nota.chave());
                    ps.setLong(2, nota.numero());
                    ps.setInt(3, nota.serie());
                    ps.setLong(4, pedido.fornecedorId());
                    ps.setInt(5, pedido.empresaId());
                    ps.setInt(6, pedido.id());
                    ps.setTimestamp(7, Timestamp.from(nota.emissao().toInstant()));
                    ps.setBigDecimal(8, nota.valorProdutos());
                    ps.setBigDecimal(9, nota.valorNota());
                    ps.setString(10, xml);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        notaId = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        insert into erp.nota_entrada_item (nota_entrada_id, sequencia, produto_id, codigo_fornecedor, ean, descricao,
                               ncm, cfop, unidade, quantidade, valor_unitario, valor_total)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    for (NotaFiscal.ItemNota i : nota.itens()) {
                        Long produto = r.itens().stream().filter(x -> x.itemNota() == i.numero()).findFirst()
                                .map(ResultadoConfronto.ItemConferido::produtoId).orElse(null);
                        ps.setLong(1, notaId);
                        ps.setInt(2, i.numero());
                        ps.setObject(3, produto);
                        ps.setString(4, i.codigoFornecedor());
                        ps.setString(5, i.ean() != null && i.ean().length() <= 14 ? i.ean() : null);
                        ps.setString(6, i.descricao());
                        ps.setString(7, i.ncm());
                        ps.setString(8, i.cfop());
                        ps.setString(9, i.unidade());
                        ps.setBigDecimal(10, i.quantidade());
                        ps.setBigDecimal(11, i.valorUnitario());
                        ps.setBigDecimal(12, i.valorTotal());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                long id;
                try (PreparedStatement ps = c.prepareStatement("""
                        insert into portal.agendamento (empresa_id, doca_id, data, hora, fornecedor_id, pedido_compra_id, chave_nfe,
                               nota_entrada_id, resultado, divergencias)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) returning id""")) {
                    ps.setInt(1, pedido.empresaId());
                    ps.setInt(2, docaId);
                    ps.setDate(3, Date.valueOf(data));
                    ps.setTime(4, Time.valueOf(hora));
                    ps.setLong(5, pedido.fornecedorId());
                    ps.setInt(6, pedido.id());
                    ps.setString(7, nota.chave());
                    ps.setLong(8, notaId);
                    ps.setString(9, r.status().name());
                    ps.setString(10, json.writeValueAsString(r.divergencias()));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
                c.commit();
                return id;
            } catch (SQLException e) {
                c.rollback();
                if ("23505".equals(e.getSQLState())) {
                    throw new HorarioOcupado(mensagemConflito(e.getMessage()));
                }
                throw e;
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mensagemConflito(String erro) {
        if (erro.contains("ux_agenda_horario")) {
            return "Esse horário acabou de ser reservado por outro fornecedor";
        }
        if (erro.contains("ux_agenda_pedido")) {
            return "Este pedido já tem um agendamento ativo";
        }
        return "Esta NF-e já foi utilizada";
    }

    public List<Map<String, Object>> doFornecedor(long fornecedor) {
        return consulta("""
                select a.id, a.pedido_compra_id, a.data, a.hora, d.numero as doca, d.refrigerada, e.fantasia as destino,
                       a.chave_nfe, a.resultado, a.status, a.divergencias
                  from portal.agendamento a join portal.doca d on d.id = a.doca_id join erp.empresa e on e.id = a.empresa_id
                 where a.fornecedor_id = ? order by a.data desc, a.hora desc""", fornecedor);
    }

    public List<Map<String, Object>> doDia(int empresa, LocalDate data) {
        return consulta("""
                select a.id, a.hora, d.numero as doca, d.refrigerada, t.nome as fornecedor, a.pedido_compra_id,
                       a.chave_nfe, a.resultado, a.status, a.divergencias, n.valor_total
                  from portal.agendamento a
                  join portal.doca d on d.id = a.doca_id
                  join erp.transacionador t on t.id = a.fornecedor_id
                  left join erp.nota_entrada n on n.id = a.nota_entrada_id
                 where a.empresa_id = ? and a.data = ? and a.status <> 'CANCELADO'
                 order by a.hora, d.numero""", empresa, Date.valueOf(data));
    }

    public Optional<Map<String, Object>> agendamento(long id) {
        return consulta("select id, fornecedor_id, pedido_compra_id, nota_entrada_id, status, empresa_id from portal.agendamento where id = ?", id)
                .stream().findFirst();
    }

    /** Fornecedor desistiu: libera o horário e recusa a nota digitada. */
    public void cancelar(long id) {
        executarEmTransacao(c -> {
            atualizar(c, "update portal.agendamento set status = 'CANCELADO' where id = ? and status = 'AGENDADO'", id);
            atualizar(c, "update erp.nota_entrada set status = 'RECUSADA' where id = (select nota_entrada_id from portal.agendamento where id = ?)", id);
        });
    }

    public void naoCompareceu(long id) {
        executarEmTransacao(c -> {
            atualizar(c, "update portal.agendamento set status = 'NAO_COMPARECEU' where id = ? and status = 'AGENDADO'", id);
            atualizar(c, "update erp.nota_entrada set status = 'RECUSADA' where id = (select nota_entrada_id from portal.agendamento where id = ?)", id);
        });
    }

    /**
     * Recebimento conferido na doca: nota passa a CONFERIDA (o ERP escritura e dá entrada no estoque)
     * e o pedido de compra recebe as quantidades. Caixas recebidas = unidades da nota / fator.
     */
    public void receber(long id, PedidoCompra pedido, ResultadoConfronto conferencia) {
        executarEmTransacao(c -> {
            atualizar(c, "update portal.agendamento set status = 'RECEBIDO', recebido_em = now() where id = ? and status = 'AGENDADO'", id);
            atualizar(c, "update erp.nota_entrada set status = 'CONFERIDA', data_entrada = now() where id = (select nota_entrada_id from portal.agendamento where id = ?)", id);
            boolean completo = true;
            for (PedidoCompra.Item item : pedido.itens()) {
                BigDecimal unidades = conferencia.itens().stream()
                        .filter(x -> x.produtoId() != null && x.produtoId() == item.produtoId())
                        .map(ResultadoConfronto.ItemConferido::unidadesNota).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal caixas = unidades.divide(item.fator(), 3, java.math.RoundingMode.HALF_UP);
                if (caixas.signum() > 0) {
                    atualizar(c, "update erp.pedido_compra_item set quantidade_recebida = quantidade_recebida + ? where pedido_compra_id = ? and sequencia = ?",
                            caixas, pedido.id(), item.sequencia());
                }
                if (item.quantidadeRecebidaCaixas().add(caixas).compareTo(item.quantidadeCaixas()) < 0) {
                    completo = false;
                }
            }
            atualizar(c, "update erp.pedido_compra set status = ? where id = ?", completo ? "RECEBIDO" : "PARCIAL", pedido.id());
        });
    }

    @FunctionalInterface
    private interface Trabalho {
        void executar(Connection c) throws SQLException;
    }

    private void executarEmTransacao(Trabalho t) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                t.executar(c);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void atualizar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    // ---------- acesso de fornecedores ----------

    public Optional<String> hashDoAcesso(long fornecedor) {
        return consulta("select codigo_hash from portal.acesso where fornecedor_id = ?", fornecedor).stream()
                .findFirst().map(m -> (String) m.get("codigo_hash"));
    }

    public void registrarAcesso(long fornecedor, String hash) {
        executarEmTransacao(c -> atualizar(c, """
                insert into portal.acesso (fornecedor_id, codigo_hash, ultimo_acesso) values (?, ?, now())
                on conflict (fornecedor_id) do update set ultimo_acesso = now()""", fornecedor, hash));
    }

    private List<Map<String, Object>> consulta(String sql, Object... params) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return Erp.linhas(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
