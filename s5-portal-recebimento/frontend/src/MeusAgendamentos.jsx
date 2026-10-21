import { api, dataBR } from './api.js'
import { useCarregar } from './App.jsx'

export default function MeusAgendamentos() {
  const [{ carregando, dados, erro }, recarregar] = useCarregar(api.meusAgendamentos, [])
  if (carregando && !dados) return <p className="sub">Carregando…</p>
  if (erro) return <div className="alerta erro">{erro.message}</div>
  const cancelar = async (id) => {
    if (!confirm('Cancelar este agendamento e liberar o horário?')) return
    await api.cancelar(id)
    recarregar()
  }
  return (
    <section>
      <h2>Meus agendamentos</h2>
      {dados.length === 0 && <div className="vazio">Nenhum agendamento ainda.</div>}
      <div className="cartao">
        <table>
          <thead><tr><th>Data</th><th>Hora</th><th>Destino</th><th>Doca</th><th>Pedido</th><th>Conferência da NF-e</th><th>Situação</th><th></th></tr></thead>
          <tbody>
            {dados.map((a) => (
              <tr key={a.id}>
                <td>{dataBR(a.data)}</td><td>{a.hora.slice(0, 5)}</td><td>{a.destino}</td>
                <td>{a.doca}{a.refrigerada && ' ❄'}</td><td>{a.pedido_compra_id}</td>
                <td><span className={'selo s-' + a.resultado}>{a.resultado.replaceAll('_', ' ').toLowerCase()}</span></td>
                <td>{a.status.replaceAll('_', ' ').toLowerCase()}</td>
                <td>{a.status === 'AGENDADO' && <button className="link" onClick={() => cancelar(a.id)}>cancelar</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
