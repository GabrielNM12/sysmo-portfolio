package br.portfolio.integrador.etapas;

import br.portfolio.integrador.banco.Documentos;
import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.Etapa;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;

/** EXTRAIR CLIENTE: normaliza e valida os dados do consumidor antes de virar transacionador. */
@ApplicationScoped
public class ExtrairCliente implements Etapa {

    @Override
    public String tipo() {
        return "extrair-cliente";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) {
        String cpf = Documentos.digitos(ctx.exigirTexto("pedido.cliente.cpf"));
        if (!Documentos.cpfValido(cpf)) {
            throw new ErroDeNegocio("CPF do cliente inválido: " + cpf);
        }
        Map<String, Object> cliente = new HashMap<>();
        cliente.put("cpf", cpf);
        cliente.put("nome", ctx.exigirTexto("pedido.cliente.nome").trim().replaceAll("\\s+", " ").toUpperCase());
        cliente.put("email", ctx.texto("pedido.cliente.email"));
        cliente.put("telefone", Documentos.digitos(ctx.texto("pedido.cliente.telefone")));
        cliente.put("aceitaMarketing", Boolean.TRUE.equals(ctx.obter("pedido.cliente.aceitaComunicacao")));
        ctx.definir("cliente", cliente);
        return "CPF ***." + cpf.substring(3, 6) + ".***-**";      // log sem expor o documento inteiro (LGPD)
    }
}
