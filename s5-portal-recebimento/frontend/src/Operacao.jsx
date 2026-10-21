import { useState } from 'react'
import { api, moeda, hojeMais, dataBR } from './api.js'
import { useCarregar } from './App.jsx'

const UNIDADES = [[1, 'CD Oeste (Chapecó)'], [2, 'Oeste Centro'], [3, 'Oeste Efapi'], [4, 'Oeste Xanxerê'], [5, 'Oeste Concórdia'], [6, 'Oeste Joaçaba']]

export default function Operacao() {
  const [empresa, setEmpresa] = useState(1)
  const [data, setData] = useState(hojeMais(1))
  const [{ carregando, dados, erro }, recarregar] = useCarregar(() => api.agendaDoDia(empresa, data), [empresa, data])
  const [falha, setFalha] = useState(null)

  const acao = async (fn) => {
    setFalha(null)
    try { await fn(); recarregar() } catch (e) { setFalha(e.message) }
  }

  return (
    <section>
      <h2>Agenda de descarga</h2>
      <div className="filtros">
        <select value={empresa} onChange={(e) => setEmpresa(Number(e.target.value))}>
          {UNIDADES.map(([id, n]) => <option key={id} value={id}>{n}</option>)}
        </select>
        <input type="date" value={data} onChange={(e) => setData(e.target.value)} />
      </div>
      {falha && <div className="alerta erro">{falha}</div>}
      {erro && <div className="alerta erro">{erro.message}</div>}
      {carregando && !dados && <p className="sub">Carregando…</p>}
      {dados && (
        <>
          <p className="sub">{dataBR(dados.data)} · {dados.agendamentos.length} entrega(s) agendada(s)</p>
          <div className="docas-operacao">
            {dados.docas.map((d) => {
              const lista = dados.agendamentos.filter((a) => a.doca === d.numero)
              return (
                <div key={d.id} className="cartao coluna-doca">
                  <h3>Doca {d.numero} {d.refrigerada && <span className="selo frio">refrigerada</span>}</h3>
                  {lista.length === 0 && <div className="vazio pequeno">livre</div>}
                  {lista.map((a) => (
                    <div key={a.id} className={'entrega st-' + a.status}>
                      <div className="linha"><b>{a.hora.slice(0, 5)}</b><span className={'selo s-' + a.resultado}>{a.resultado.replaceAll('_', ' ').toLowerCase()}</span></div>
                      <div>{a.fornecedor}</div>
                      <div className="suave">Pedido {a.pedido_compra_id} · NF {moeda(a.valor_total)}</div>
                      {a.status === 'AGENDADO' ? (
                        <div className="acoes">
                          <button className="primario pequeno" onClick={() => acao(() => api.receber(a.id))}>Recebido</button>
                          <button className="pequeno" onClick={() => acao(() => api.naoCompareceu(a.id))}>Não compareceu</button>
                        </div>
                      ) : <div className="suave">{a.status.replaceAll('_', ' ').toLowerCase()}</div>}
                    </div>
                  ))}
                </div>
              )
            })}
          </div>
        </>
      )}
    </section>
  )
}
