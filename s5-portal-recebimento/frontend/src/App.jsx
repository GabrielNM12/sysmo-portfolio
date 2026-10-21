import { useEffect, useState } from 'react'
import { api, definirToken } from './api.js'
import Login from './Login.jsx'
import Pedidos from './Pedidos.jsx'
import Pedido from './Pedido.jsx'
import MeusAgendamentos from './MeusAgendamentos.jsx'
import Operacao from './Operacao.jsx'

export default function App() {
  const [sessao, setSessao] = useState(() => JSON.parse(sessionStorage.getItem('sessao') || 'null'))
  const [tela, setTela] = useState({ nome: 'inicio' })

  useEffect(() => {
    if (sessao) sessionStorage.setItem('sessao', JSON.stringify(sessao))
    else sessionStorage.removeItem('sessao')
  }, [sessao])

  const sair = () => { definirToken(null); setSessao(null); setTela({ nome: 'inicio' }) }

  if (!sessao) return <Login aoEntrar={(s) => { definirToken(s.token); setSessao(s) }} />

  return (
    <div className="app">
      <header className="topo">
        <div className="marca">Portal de Recebimento <span>Rede Oeste</span></div>
        {sessao.papel === 'FORNECEDOR' && (
          <nav>
            <button className={tela.nome !== 'agendamentos' ? 'ativo' : ''} onClick={() => setTela({ nome: 'inicio' })}>Pedidos em aberto</button>
            <button className={tela.nome === 'agendamentos' ? 'ativo' : ''} onClick={() => setTela({ nome: 'agendamentos' })}>Meus agendamentos</button>
          </nav>
        )}
        <div className="usuario">{sessao.nome}<button className="link" onClick={sair}>sair</button></div>
      </header>
      <main>
        {sessao.papel === 'OPERADOR' && <Operacao aoExpirar={sair} />}
        {sessao.papel === 'FORNECEDOR' && tela.nome === 'inicio' && <Pedidos aoAbrir={(id) => setTela({ nome: 'pedido', id })} aoExpirar={sair} />}
        {sessao.papel === 'FORNECEDOR' && tela.nome === 'pedido' && (
          <Pedido id={tela.id} aoVoltar={() => setTela({ nome: 'inicio' })} aoAgendar={() => setTela({ nome: 'agendamentos' })} />
        )}
        {sessao.papel === 'FORNECEDOR' && tela.nome === 'agendamentos' && <MeusAgendamentos />}
      </main>
    </div>
  )
}

export function useCarregar(funcao, deps) {
  const [estado, setEstado] = useState({ carregando: true, dados: null, erro: null })
  const recarregar = () => {
    setEstado((e) => ({ ...e, carregando: true }))
    funcao().then((dados) => setEstado({ carregando: false, dados, erro: null }))
      .catch((erro) => setEstado({ carregando: false, dados: null, erro }))
  }
  useEffect(recarregar, deps) // eslint-disable-line react-hooks/exhaustive-deps
  return [estado, recarregar]
}

export { api }
