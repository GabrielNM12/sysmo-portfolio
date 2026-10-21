import { useState } from 'react'
import { api, moeda, num, dataBR, hojeMais } from './api.js'
import { useCarregar } from './App.jsx'

const CENARIOS = [
  ['CORRETA', 'Nota correta'],
  ['ENTREGA_PARCIAL', 'Entrega parcial'],
  ['PRECO_ACIMA', 'Preço acima do pedido'],
  ['QUANTIDADE_ACIMA', 'Quantidade acima'],
  ['PRODUTO_NAO_PEDIDO', 'Produto não pedido'],
  ['NCM_DIVERGENTE', 'NCM divergente'],
  ['OUTRO_DESTINATARIO', 'Outro destinatário'],
  ['SEM_PROTOCOLO', 'Sem protocolo SEFAZ'],
]

const ROTULO = { APROVADA: 'Aprovada', APROVADA_COM_RESSALVAS: 'Aprovada com ressalvas', BLOQUEADA: 'Bloqueada' }

export default function Pedido({ id, aoVoltar, aoAgendar }) {
  const [{ carregando, dados, erro }] = useCarregar(() => api.pedido(id), [id])
  const [xml, setXml] = useState('')
  const [cenario, setCenario] = useState('CORRETA')
  const [resultado, setResultado] = useState(null)
  const [falha, setFalha] = useState(null)
  const [data, setData] = useState(hojeMais(1))
  const [agenda, setAgenda] = useState(null)
  const [escolha, setEscolha] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  if (carregando) return <p className="sub">Carregando…</p>
  if (erro) return <div className="alerta erro">{erro.message}</div>
  const { pedido, agendamento } = dados

  const executar = async (fn) => {
    setFalha(null)
    setOcupado(true)
    try { return await fn() } catch (e) { setFalha(e.message); return null } finally { setOcupado(false) }
  }

  const lerArquivo = (e) => {
    const arq = e.target.files[0]
    if (!arq) return
    arq.text().then((t) => { setXml(t); setResultado(null) })
  }

  const gerarExemplo = () => executar(async () => { setXml(await api.nfeExemplo(id, cenario)); setResultado(null) })
  const validar = () => executar(async () => {
    const r = await api.validar(id, xml)
    setResultado(r)
    if (r.status !== 'BLOQUEADA') setAgenda(await api.disponibilidade(id, data))
  })
  const trocarData = (d) => { setData(d); setEscolha(null); executar(async () => setAgenda(await api.disponibilidade(id, d))) }
  const agendar = () => executar(async () => {
    await api.agendar({ pedidoId: id, docaId: escolha.doca, data, hora: escolha.hora, xml })
    aoAgendar()
  })

  return (
    <section>
      <button className="link" onClick={aoVoltar}>← pedidos</button>
      <h2>Pedido {pedido.id} <small>{pedido.empresaNome}</small></h2>
      <p className="sub">Previsão {dataBR(pedido.previsaoEntrega)} · {moeda(dados.valorTotal)}
        {dados.exigeDocaRefrigerada && <span className="selo frio">exige doca refrigerada</span>}</p>
      {agendamento && <div className="alerta ok">Já agendado para {dataBR(agendamento.data)} às {agendamento.hora.slice(0, 5)}.</div>}

      <div className="cartao">
        <table>
          <thead><tr><th>#</th><th>Produto</th><th>DUN-14 / EAN</th><th className="num">Caixas</th><th className="num">Un/cx</th><th className="num">Custo/cx</th></tr></thead>
          <tbody>
            {pedido.itens.map((i) => (
              <tr key={i.sequencia}>
                <td>{i.sequencia}</td>
                <td>{i.descricao}{i.perecivel && <span className="selo frio">frio</span>}</td>
                <td className="mono">{i.dun14}<br /><small>{i.eanUnidade}</small></td>
                <td className="num">{num(i.quantidadeCaixas - i.quantidadeRecebidaCaixas)}</td>
                <td className="num">{num(i.fator)}</td>
                <td className="num">{moeda(i.custoCaixa)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {!agendamento && (
        <div className="passos">
          <div className="cartao">
            <h3>1 · XML da NF-e</h3>
            <div className="acoes">
              <label className="botao">Enviar arquivo .xml<input type="file" accept=".xml,text/xml" onChange={lerArquivo} hidden /></label>
              <span className="sub">ou, no laboratório:</span>
              <select value={cenario} onChange={(e) => setCenario(e.target.value)}>
                {CENARIOS.map(([v, r]) => <option key={v} value={v}>{r}</option>)}
              </select>
              <button onClick={gerarExemplo} disabled={ocupado}>Gerar NF-e de exemplo</button>
            </div>
            <textarea value={xml} onChange={(e) => { setXml(e.target.value); setResultado(null) }} placeholder="Cole aqui o XML da nota (nfeProc)" rows={6} />
            <button className="primario" onClick={validar} disabled={!xml || ocupado}>Conferir com o pedido</button>
            {resultado && <Resultado r={resultado} />}
          </div>

          {resultado && resultado.status !== 'BLOQUEADA' && agenda && (
            <div className="cartao">
              <h3>2 · Horário de descarga</h3>
              <label className="inline">Data <input type="date" value={data} min={hojeMais(1)} onChange={(e) => trocarData(e.target.value)} /></label>
              {agenda.motivoIndisponivel && <div className="alerta aviso">{agenda.motivoIndisponivel}</div>}
              <div className="agenda">
                {agenda.docas.map((d) => (
                  <div key={d.id} className="doca">
                    <div className="nome-doca">Doca {d.numero}{d.refrigerada && <span className="selo frio">❄</span>}</div>
                    {d.horarios.map((h) => {
                      const sel = escolha && escolha.doca === d.id && escolha.hora === h.hora
                      return (
                        <button key={h.hora} disabled={!h.livre} className={'slot' + (sel ? ' escolhido' : '')}
                          onClick={() => setEscolha({ doca: d.id, hora: h.hora })}>{h.hora.slice(0, 5)}</button>
                      )
                    })}
                  </div>
                ))}
              </div>
              <button className="primario" disabled={!escolha || ocupado} onClick={agendar}>
                {escolha ? `Confirmar ${dataBR(data)} às ${escolha.hora.slice(0, 5)}` : 'Escolha um horário livre'}
              </button>
            </div>
          )}
        </div>
      )}
      {falha && <div className="alerta erro">{falha}</div>}
    </section>
  )
}

function Resultado({ r }) {
  return (
    <div className="resultado">
      <div className={'status s-' + r.status}>{ROTULO[r.status]}</div>
      <p className="sub">NF-e {r.numeroNota} · {moeda(r.valorNota)} (pedido {moeda(r.valorPedido)}) · <span className="mono">{r.chave}</span></p>
      {r.divergencias.length > 0 && (
        <ul className="divergencias">
          {r.divergencias.map((d, i) => (
            <li key={i} className={d.gravidade === 'BLOQUEIA' ? 'bloqueia' : 'alerta-item'}>
              <b>{d.gravidade === 'BLOQUEIA' ? 'Bloqueia' : 'Atenção'}</b> {d.mensagem}
            </li>
          ))}
        </ul>
      )}
      <table>
        <thead><tr><th>Item NF</th><th>Descrição</th><th className="num">Unidades na nota</th><th className="num">Pendentes no pedido</th><th className="num">Custo/un nota</th><th className="num">Custo/un pedido</th></tr></thead>
        <tbody>
          {r.itens.map((i) => (
            <tr key={i.itemNota} className={i.produtoId ? '' : 'linha-erro'}>
              <td>{i.itemNota}</td><td>{i.descricaoNota}</td>
              <td className="num">{num(i.unidadesNota)}</td><td className="num">{i.unidadesPendentes == null ? '–' : num(i.unidadesPendentes)}</td>
              <td className="num">{moeda(i.custoUnitarioNota)}</td><td className="num">{i.custoUnitarioPedido == null ? '–' : moeda(i.custoUnitarioPedido)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
