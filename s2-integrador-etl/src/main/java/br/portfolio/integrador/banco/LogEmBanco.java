package br.portfolio.integrador.banco;

import br.portfolio.integrador.motor.LogDeExecucao;
import br.portfolio.integrador.motor.ResumoExecucao;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Log de execuções no schema "integrador" (separado do schema do ERP). */
@ApplicationScoped
public class LogEmBanco implements LogDeExecucao {

    private static final String DDL = """
            create table if not exists integrador.execucao (
                id           bigserial primary key,
                grupo        text        not null,
                inicio       timestamptz not null default now(),
                fim          timestamptz,
                status       text        not null default 'EM_EXECUCAO',
                itens_ok     int, itens_erro int, itens_ignorados int,
                duracao_ms   bigint,
                mensagem     text);
            create table if not exists integrador.execucao_etapa (
                id           bigserial primary key,
                execucao_id  bigint      not null references integrador.execucao (id) on delete cascade,
                registrado   timestamptz not null default now(),
                etapa        text        not null,
                item         text,
                tentativa    int         not null,
                status       text        not null,
                duracao_ms   bigint      not null,
                mensagem     text);
            create index if not exists ix_execucao_etapa_exec on integrador.execucao_etapa (execucao_id, id);
            """;

    @Inject
    AgroalDataSource ds;

    void criarTabelas(@Observes StartupEvent evento) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(DDL);
        }
    }

    @Override
    public long iniciar(String grupo) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("insert into integrador.execucao (grupo) values (?) returning id")) {
            ps.setString(1, grupo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível registrar a execução", e);
        }
    }

    @Override
    public void etapa(long execucao, String etapa, String item, int tentativa, String status, long duracaoMs, String mensagem) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     insert into integrador.execucao_etapa (execucao_id, etapa, item, tentativa, status, duracao_ms, mensagem)
                     values (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, execucao);
            ps.setString(2, etapa);
            ps.setString(3, item);
            ps.setInt(4, tentativa);
            ps.setString(5, status);
            ps.setLong(6, duracaoMs);
            ps.setString(7, mensagem);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao gravar log da etapa", e);
        }
    }

    @Override
    public void finalizar(long execucao, ResumoExecucao r) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     update integrador.execucao set fim = now(), status = ?, itens_ok = ?, itens_erro = ?,
                            itens_ignorados = ?, duracao_ms = ?, mensagem = ? where id = ?""")) {
            ps.setString(1, r.status());
            ps.setInt(2, r.itensOk());
            ps.setInt(3, r.itensComErro());
            ps.setInt(4, r.itensIgnorados());
            ps.setLong(5, r.duracaoMs());
            ps.setString(6, r.mensagem());
            ps.setLong(7, execucao);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao finalizar execução", e);
        }
    }

    public List<Map<String, Object>> listar(int limite) {
        return consultar("select * from integrador.execucao order by id desc limit ?", limite);
    }

    public Optional<Map<String, Object>> detalhe(long id) {
        List<Map<String, Object>> exec = consultar("select * from integrador.execucao where id = ?", id);
        if (exec.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> resultado = new LinkedHashMap<>(exec.get(0));
        resultado.put("etapas", consultar("""
                select etapa, item, tentativa, status, duracao_ms, mensagem, registrado
                  from integrador.execucao_etapa where execucao_id = ? order by id""", id));
        return Optional.of(resultado);
    }

    private List<Map<String, Object>> consultar(String sql, Object... params) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> linhas = new ArrayList<>();
                int colunas = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> linha = new LinkedHashMap<>();
                    for (int i = 1; i <= colunas; i++) {
                        Object v = rs.getObject(i);
                        if (v instanceof java.sql.Timestamp t) {
                            v = t.toInstant().toString();
                        } else if (v instanceof OffsetDateTime o) {
                            v = o.toString();
                        }
                        linha.put(rs.getMetaData().getColumnLabel(i), v);
                    }
                    linhas.add(linha);
                }
                return linhas;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
