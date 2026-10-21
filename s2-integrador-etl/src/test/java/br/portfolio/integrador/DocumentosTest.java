package br.portfolio.integrador;

import br.portfolio.integrador.banco.Documentos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentosTest {

    @Test
    void cpf() {
        assertEquals("52998224725", Documentos.cpfComDv("529982247"));
        assertTrue(Documentos.cpfValido("529.982.247-25"));
        assertFalse(Documentos.cpfValido("529.982.247-24"));
        assertFalse(Documentos.cpfValido("111.111.111-11"));
    }

    @Test
    void cnpj() {
        assertTrue(Documentos.cnpjValido("11.222.333/0001-81"));
        assertFalse(Documentos.cnpjValido("11.222.333/0001-82"));
    }

    @Test
    void gtin() {
        assertTrue(Documentos.gtinValido("7891000100103"));
        assertFalse(Documentos.gtinValido("7891000100104"));
        assertEquals(1, Documentos.dvGtin("210010001299"));
    }
}
