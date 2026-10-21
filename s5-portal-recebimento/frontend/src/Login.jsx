import { useState } from 'react'
import { api } from './api.js'

export default function Login({ aoEntrar }) {
  const [modo, setModo] = useState('fornecedor')
  const [cnpj, setCnpj] = useState('')
  const [codigo, setCodigo] = useState('')
  const [erro, setErro] = useState(null)
  const [enviando, setEnviando] = useState(false)

  const entrar = async (e) => {
    e.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      aoEntrar(modo === 'fornecedor' ? await api.entrar(cnpj, codigo) : await api.entrarOperador(cnpj, codigo))
    } catch (err) {
      setErro(err.status === 401 ? 'Dados de acesso inválidos' : err.message)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="login">
      <form onSubmit={entrar} className="cartao">
        <h1>Portal de Recebimento</h1>
        <p className="sub">Agende a entrega e envie o XML da NF-e antes de o caminhão sair.</p>
        <div className="abas">
          <button type="button" className={modo === 'fornecedor' ? 'ativo' : ''} onClick={() => setModo('fornecedor')}>Fornecedor</button>
          <button type="button" className={modo === 'operador' ? 'ativo' : ''} onClick={() => setModo('operador')}>Operação do CD</button>
        </div>
        <label>{modo === 'fornecedor' ? 'CNPJ' : 'Usuário'}
          <input value={cnpj} onChange={(e) => setCnpj(e.target.value)} placeholder={modo === 'fornecedor' ? '00.000.000/0000-00' : 'operador'} required />
        </label>
        <label>{modo === 'fornecedor' ? 'Código de acesso' : 'Senha'}
          <input type="password" value={codigo} onChange={(e) => setCodigo(e.target.value)} required />
        </label>
        {erro && <div className="alerta erro">{erro}</div>}
        <button className="primario" disabled={enviando}>{enviando ? 'Entrando…' : 'Entrar'}</button>
        <p className="dica">Ambiente de laboratório: código inicial dos fornecedores <b>1234</b>; operação <b>operador / operador</b>.</p>
      </form>
    </div>
  )
}
