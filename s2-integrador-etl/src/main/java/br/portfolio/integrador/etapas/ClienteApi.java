package br.portfolio.integrador.etapas;

import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.ErroTransitorio;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente HTTP/JSON das etapas. Traduz o status HTTP em decisão de retentativa:
 * 429 e 5xx são transitórios; 401/403 e demais 4xx são erro de negócio (não adianta repetir).
 */
@ApplicationScoped
public class ClienteApi {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    ObjectMapper json;

    public <T> T get(String url, String token, Class<T> tipo) throws IOException, InterruptedException {
        return enviar(base(url, token).GET().build(), tipo);
    }

    public <T> T post(String url, String token, Object corpo, Class<T> tipo) throws IOException, InterruptedException {
        HttpRequest req = base(url, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();
        return enviar(req, tipo);
    }

    private HttpRequest.Builder base(String url, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private <T> T enviar(HttpRequest req, Class<T> tipo) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status == 429 || status >= 500) {
            throw new ErroTransitorio("HTTP " + status + " em " + req.method() + " " + req.uri().getPath());
        }
        if (status == 401 || status == 403) {
            throw new ErroDeNegocio("acesso negado pela API (HTTP " + status + "): verifique client_id/secret no cadastro do webservice");
        }
        if (status >= 400) {
            throw new ErroDeNegocio("HTTP " + status + " em " + req.uri().getPath() + ": " + resp.body());
        }
        if (tipo == Void.class || resp.body() == null || resp.body().isBlank()) {
            return null;
        }
        return json.readValue(resp.body(), tipo);
    }
}
