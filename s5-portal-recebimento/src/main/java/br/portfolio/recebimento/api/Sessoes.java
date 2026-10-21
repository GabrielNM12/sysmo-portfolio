package br.portfolio.recebimento.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sessões em memória com expiração (laboratório; em produção, OIDC/Keycloak). */
@ApplicationScoped
public class Sessoes {

    public enum Papel { FORNECEDOR, OPERADOR }

    public record Sessao(Papel papel, long fornecedorId, String nome, Instant expira) {
    }

    private static final Duration VALIDADE = Duration.ofHours(8);
    private final Map<String, Sessao> ativas = new ConcurrentHashMap<>();
    private final SecureRandom aleatorio = new SecureRandom();

    public String abrir(Papel papel, long fornecedorId, String nome) {
        byte[] b = new byte[24];
        aleatorio.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        ativas.put(token, new Sessao(papel, fornecedorId, nome, Instant.now().plus(VALIDADE)));
        return token;
    }

    public Sessao exigir(String authorization, Papel papel) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new NotAuthorizedException("Bearer");
        }
        Sessao s = ativas.get(authorization.substring(7));
        if (s == null || s.expira().isBefore(Instant.now())) {
            throw new NotAuthorizedException("Bearer");
        }
        if (s.papel() != papel) {
            throw new ForbiddenException("Acesso restrito a " + papel);
        }
        return s;
    }

    public static String hash(String codigo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(codigo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
