import os

import pytest


@pytest.fixture(scope="session")
def dsn_erp():
    """Testes de integração só rodam com o banco do S1 disponível (CI ou ambiente local)."""
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        pytest.skip("DATABASE_URL não definido: pulando testes contra o ERP")
    return dsn
