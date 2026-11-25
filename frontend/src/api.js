async function csrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('Could not prepare secure request')
  return (await response.json()).token
}

async function parse(response) {
  const contentType = response.headers.get('content-type') || ''
  const data = contentType.includes('application/json') ? await response.json() : await response.text()
  if (!response.ok) throw new Error(data?.error || data?.message || 'Request failed')
  return data
}

export const api = {
  get: async path => parse(await fetch('/api' + path, { credentials: 'same-origin' })),
  send: async (method, path, body) => parse(await fetch('/api' + path, {
    method, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': await csrf() },
    body: body === undefined ? undefined : JSON.stringify(body)
  })),
  login: async (email, password, portal) => {
    const body = new URLSearchParams({ username: email, password, portal })
    return parse(await fetch('/api/auth/login', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': await csrf() }, body }))
  },
  download: async (path, filename) => {
    const response = await fetch('/api' + path, { credentials: 'same-origin' })
    if (!response.ok) { await parse(response); return }
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a'); link.href = url; link.download = filename; link.click(); URL.revokeObjectURL(url)
  },
  downloadSend: async (method, path, body, filename) => {
    const response = await fetch('/api' + path, {
      method, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': await csrf() },
      body: body === undefined ? undefined : JSON.stringify(body)
    })
    if (!response.ok) { await parse(response); return }
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a'); link.href = url; link.download = filename; link.click(); URL.revokeObjectURL(url)
  }
}
