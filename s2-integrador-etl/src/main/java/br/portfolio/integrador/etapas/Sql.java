package br.portfolio.integrador.etapas;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Pequenos atalhos de JDBC usados pelas etapas. */
final class Sql {

    private Sql() {
    }

    static PreparedStatement preparar(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    static Optional<Long> umLong(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = preparar(c, sql, params); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
        }
    }

    static Optional<BigDecimal> umDecimal(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = preparar(c, sql, params); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(rs.getBigDecimal(1)) : Optional.empty();
        }
    }

    static BigDecimal decimal(Object valor) {
        if (valor == null) {
            return null;
        }
        return valor instanceof BigDecimal b ? b : new BigDecimal(valor.toString());
    }
}
