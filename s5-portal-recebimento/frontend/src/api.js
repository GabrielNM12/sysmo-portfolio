// Cliente da API do portal: guarda o token da sessão e transforma erros em mensagens legíveis.
let token = sessionStorage.getItem('token')

export function definirToken(t) {
  token = t
  if (t) sessionStorage.setItem('token', t)
  else sessionStorage.removeItem('token')
}

async function chamar(metodo, caminho, corpo, tipo = 'application/json') {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (corpo !== undefined) headers['Content-Type'] = tipo
  const resp = await fetch(caminho, {
    method: metodo,
    headers,
    body: corpo === undefined ? undefined : tipo === 'application/json' ? JSON.stringify(corpo) : corpo,
  })
  const texto = await resp.text()
  let dados = texto
  try { dados = texto ? JSON.parse(texto) : null } catch { /* resposta em XML/texto */ }
  if (!resp.ok) {
    if (resp.status === 401) definirToken(null)
    const erro = new Error((dados && dados.erro) || (resp.status === 401 ? 'Sessão expirada ou acesso negado' : `Erro ${resp.status}`))
    erro.status = resp.status
    erro.dados = dados
    throw erro
  }
  return dados
}

export const api = {
  entrar: (cnpj, codigo) => chamar('POST', '/api/sessao', { cnpj, codigo }),
  entrarOperador: (usuario, senha) => chamar('POST', '/api/sessao/operador', { usuario, senha }),
  pedidos: () => chamar('GET', '/api/pedidos'),
  pedido: (id) => chamar('GET', `/api/pedidos/${id}`),
  nfeExemplo: (id, cenario) => chamar('GET', `/api/pedidos/${id}/nfe-exemplo?cenario=${cenario}`),
  validar: (id, xml) => chamar('POST', `/api/pedidos/${id}/validar`, xml, 'application/xml'),
  disponibilidade: (id, data) => chamar('GET', `/api/pedidos/${id}/disponibilidade?data=${data}`),
  agendar: (dados) => chamar('POST', '/api/agendamentos', dados),
  meusAgendamentos: () => chamar('GET', '/api/agendamentos'),
  cancelar: (id) => chamar('DELETE', `/api/agendamentos/${id}`),
  agendaDoDia: (empresa, data) => chamar('GET', `/api/operacao/agenda?empresa=${empresa}&data=${data}`),
  receber: (id) => chamar('POST', `/api/operacao/agendamentos/${id}/recebido`),
  naoCompareceu: (id) => chamar('POST', `/api/operacao/agendamentos/${id}/nao-compareceu`),
}

export const moeda = (v) => Number(v ?? 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
export const num = (v) => Number(v ?? 0).toLocaleString('pt-BR', { maximumFractionDigits: 3 })
export const dataBR = (iso) => (iso ? iso.slice(0, 10).split('-').reverse().join('/') : '–')
export const hojeMais = (dias) => {
  const d = new Date()
  d.setDate(d.getDate() + dias)
  if (d.getDay() === 0) d.setDate(d.getDate() + 1)
  const dois = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${dois(d.getMonth() + 1)}-${dois(d.getDate())}`
}
