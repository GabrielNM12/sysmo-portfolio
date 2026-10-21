package br.portfolio.integrador.etapas;

import br.portfolio.integrador.banco.Documentos;
import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.Etapa;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/** EXTRAIR ENDEREÇO: entrega em casa exige endereço completo; retirada na loja não. */
@ApplicationScoped
public class ExtrairEndereco implements Etapa {

    @Override
    public String tipo() {
        return "extrair-endereco";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) {
        String tipo = String.valueOf(ctx.obter("pedido.entrega.tipo"));
        if (!"ENTREGA".equals(tipo) && !"RETIRADA".equals(tipo)) {
            throw new ErroDeNegocio("tipo de entrega desconhecido: " + tipo);
        }
        ctx.definir("tipo_entrega", tipo);
        if ("RETIRADA".equals(tipo)) {
            ctx.definir("endereco", null);
            return "retirada na loja";
        }
        String cep = Documentos.digitos(ctx.exigirTexto("pedido.entrega.endereco.cep"));
        if (cep.length() != 8) {
            throw new ErroDeNegocio("CEP inválido: " + cep);
        }
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("cep", cep);
        end.put("logradouro", ctx.exigirTexto("pedido.entrega.endereco.logradouro"));
        end.put("numero", ctx.exigirTexto("pedido.entrega.endereco.numero"));
        end.put("complemento", ctx.texto("pedido.entrega.endereco.complemento"));
        end.put("bairro", ctx.texto("pedido.entrega.endereco.bairro"));
        end.put("cidade", ctx.exigirTexto("pedido.entrega.endereco.cidade"));
        end.put("uf", ctx.exigirTexto("pedido.entrega.endereco.uf").toUpperCase());
        ctx.definir("endereco", end);
        return "entrega em " + end.get("cidade") + "/" + end.get("uf");
    }
}
