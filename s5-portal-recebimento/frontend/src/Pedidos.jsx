import { api, moeda, dataBR } from './api.js'
import { useCarregar } from './App.jsx'

export default function Pedidos({ aoAbrir }) {
  const [{ carregando, dados, erro }] = useCarregar(api.pedidos, [])
  if (carregando) return <p className="sub">Carregando pedidos…</p>
  if (erro) return <div className="alerta erro">{erro.message}</div>
  return (
    <section>
      <h2>Pedidos de compra em aberto</h2>
      <p className="sub">Escolha o pedido, envie a NF-e e reserve um horário de doca.</p>
      {dados.length === 0 && <div className="vazio">Nenhum pedido em aberto para o seu CNPJ.</div>}
      <div className="grade">
        {dados.map((p) => (
          <button key={p.id} className="cartao pedido" onClick={() => aoAbrir(p.id)}>
            <div className="linha"><b>Pedido {p.id}</b>{p.perecivel && <span className="selo frio">refrigerado</span>}</div>
            <div className="destino">{p.destino}</div>
            <div className="linha suave"><span>Emitido {dataBR(p.data_emissao)}</span><span>Previsão {dataBR(p.previsao_entrega)}</span></div>
            <div className="linha"><span>{p.itens} itens</span><b>{moeda(p.valor)}</b></div>
          </button>
        ))}
      </div>
    </section>
  )
}
