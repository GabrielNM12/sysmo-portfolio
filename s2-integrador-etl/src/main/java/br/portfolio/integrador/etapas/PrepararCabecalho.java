package br.portfolio.integrador.etapas;

import br.portfolio.integrador.motor.Contexto;
import br.portfolio.integrador.motor.ErroDeNegocio;
import br.portfolio.integrador.motor.Etapa;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** PREPARAR CABEÇALHO: data no fuso da loja, frete, desconto e forma de pagamento. */
@ApplicationScoped
public class PrepararCabecalho implements Etapa {

    static final ZoneId FUSO_LOJA = ZoneId.of("America/Sao_Paulo");

    @Override
    public String tipo() {
        return "preparar-cabecalho";
    }

    @Override
    public String executar(Contexto ctx, Map<String, Object> p) {
        LocalDateTime data;
        try {
            data = OffsetDateTime.parse(ctx.exigirTexto("pedido.criadoEm")).atZoneSameInstant(FUSO_LOJA).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new ErroDeNegocio("data do pedido inválida: " + ctx.texto("pedido.criadoEm"));
        }
        BigDecimal frete = valor(ctx.obter("pedido.entrega.taxa"));
        BigDecimal desconto = valor(ctx.obter("pedido.desconto"));
        String observacao = ctx.texto("pedido.observacao");
        Cabecalho cab = new Cabecalho(((Number) ctx.obter("empresa_pedido")).longValue(), data,
                ctx.texto("tipo_entrega"), frete, desconto, ctx.texto("pedido.pagamento.forma"),
                valor(ctx.obter("pedido.pagamento.valor")), observacao == null ? null : observacao.trim());
        ctx.definir("cabecalho", cab);
        return "frete " + frete + ", desconto " + desconto + ", pagamento " + cab.formaPagamento();
    }

    static BigDecimal valor(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
