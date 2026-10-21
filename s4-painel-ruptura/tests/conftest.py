import os

import pytest


@pytest.fixture(scope="session")
def dsn():
    """Testes de integração rodam contra o banco do S1 com o usuário somente leitura do painel."""
    valor = os.environ.get("DATABASE_URL")
    if not valor:
        pytest.skip("DATABASE_URL não definido: pulando testes contra o ERP")
    return valor
