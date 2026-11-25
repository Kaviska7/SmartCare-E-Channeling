import React, { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import './Queue.css'

const sriLankaDate = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Colombo', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
const displayDate = date => new Date(date + 'T12:00:00').toLocaleDateString('en-GB', { dateStyle: 'long' })
const displayTime = value => new Date(value).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })
const statusLabel = status => status === 'SKIPPED' ? 'No-show' : status.charAt(0) + status.slice(1).toLowerCase()

export default function Queue({ session, flash, fail, embedded = false }) {
  const [date, setDate] = useState(sriLankaDate)
  const [tokens, setTokens] = useState([])
  const [doctors, setDoctors] = useState([])
  const [branches, setBranches] = useState([])
  const [branchFilter, setBranchFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [search, setSearch] = useState('')
  const [matches, setMatches] = useState([])
  const [selectedId, setSelectedId] = useState('')
  const [searching, setSearching] = useState(false)
  const [searched, setSearched] = useState(false)
  const [changing, setChanging] = useState(null)
  const [slots, setSlots] = useState([])
  const [slotId, setSlotId] = useState('')
  const [slotsLoading, setSlotsLoading] = useState(false)
  const loadNumber = useRef(0)
  const searchNumber = useRef(0)
  const actionRunning = useRef(false)
  const slotRequest = useRef(0)
  const staff = ['RECEPTION', 'ADMIN'].includes(session.role)
  const live = date === sriLankaDate()
  const canFinish = live && (staff || session.role === 'DOCTOR')

  const load = async () => {
    const request = ++loadNumber.current
    try {
      const [queue, allDoctors, allBranches] = await Promise.all([
        api.get('/queue?date=' + encodeURIComponent(date)),
        session.role === 'DOCTOR' ? api.get('/doctors/me').then(d => [d]) : api.get('/doctors'),
        api.get('/branches')
      ])
      if (request !== loadNumber.current) return
      setTokens(queue); setDoctors(allDoctors); setBranches(allBranches)
    } catch (error) { if (request === loadNumber.current) fail(error) }
    finally { if (request === loadNumber.current) setLoading(false) }
  }
  useEffect(() => {
    setLoading(true); setTokens([]); setChanging(null); setSlots([]); setSlotId('')
    setMatches([]); setSelectedId(''); setSearched(false); setSearching(false)
    searchNumber.current++; slotRequest.current++
    load()
    const timer = setInterval(load, 15000)
    return () => { clearInterval(timer); loadNumber.current++; searchNumber.current++; slotRequest.current++ }
  }, [date])

  const find = async value => {
    const request = ++searchNumber.current
    if (!value.trim() || !live) { setMatches([]); setSearching(false); return }
    setSearching(true)
    try {
      const found = await api.get('/queue/find?search=' + encodeURIComponent(value.trim()))
      if (request !== searchNumber.current) return
      setMatches(found); setSearched(true)
      setSelectedId(previous => found.some(a => String(a.appointment_id) === previous) ? previous : found.length === 1 ? String(found[0].appointment_id) : '')
    } catch (error) { if (request === searchNumber.current) fail(error) }
    finally { if (request === searchNumber.current) setSearching(false) }
  }
  useEffect(() => {
    if (!staff || !live || !search.trim()) return
    const timer = setTimeout(() => find(search), 300)
    return () => { clearTimeout(timer); searchNumber.current++ }
  }, [search, live])

  const act = async (operation, message) => {
    if (actionRunning.current) return
    actionRunning.current = true; setBusy(true)
    try {
      const result = await operation()
      flash(typeof message === 'function' ? message(result) : message)
      await load()
      if (staff && search.trim()) await find(search)
    } catch (error) { fail(error); await load() }
    finally { actionRunning.current = false; setBusy(false) }
  }
  const checkIn = event => {
    event.preventDefault()
    if (!selectedId || !live) return
    act(() => api.send('POST', '/queue/check-in/' + selectedId), result => 'Token ' + result.tokenNumber + ' issued')
  }
  const call = (doctorId, branchId) => act(
    () => api.send('POST', '/queue/call-next/' + doctorId + '?branchId=' + branchId + '&date=' + date),
    result => result.tokenId ? 'Next patient called to the doctor' : 'No waiting patients'
  )
  const finish = (token, action) => act(
    () => api.send('POST', '/queue/' + token.id + '/' + action),
    action === 'complete' ? 'Consultation completed. You can call the next patient.' : 'Patient marked No-show. You can call the next patient.'
  )
  const remove = token => {
    if (!window.confirm('Cancel the visit for ' + token.patient_name + ' and remove it from the live queue? Its history will be retained.')) return
    act(() => api.send('DELETE', '/queue/' + token.id), 'Visit cancelled and removed from the live queue')
  }
  const openReschedule = async token => {
    const request = ++slotRequest.current
    setChanging(token); setSlots([]); setSlotId(''); setSlotsLoading(true)
    try {
      const available = await api.get('/doctors/' + token.doctor_id + '/slots?branchId=' + token.branch_id)
      if (request === slotRequest.current) setSlots(available)
    } catch (error) { if (request === slotRequest.current) fail(error) }
    finally { if (request === slotRequest.current) setSlotsLoading(false) }
  }
  const closeReschedule = () => { slotRequest.current++; setChanging(null); setSlots([]); setSlotId('') }
  const reschedule = () => {
    if (!changing || !slotId) return
    act(async () => {
      const result = await api.send('PUT', '/queue/' + changing.id + '/slot', { slotId: Number(slotId) })
      closeReschedule()
      return result
    }, 'Visit rescheduled. Issue a fresh token when the patient checks in on the new date.')
  }

  const branchMap = new Map(branches.filter(b => !branchFilter || String(b.id) === branchFilter).map(b => [String(b.id), { ...b, doctors: new Map() }]))
  for (const doctor of doctors) {
    for (const assignment of (doctor.branches || []).filter(b => b.active !== false && b.active !== 0)) {
      const branch = branchMap.get(String(assignment.branch_id))
      if (branch) branch.doctors.set(String(doctor.id), { id: doctor.id, name: doctor.full_name || doctor.display_name, room: assignment.room, visits: [] })
    }
  }
  for (const token of tokens) {
    if (branchFilter && String(token.branch_id) !== branchFilter) continue
    const key = String(token.branch_id)
    if (!branchMap.has(key)) branchMap.set(key, { id: token.branch_id, name: token.branch_name, doctors: new Map() })
    const branch = branchMap.get(key)
    const doctorKey = String(token.doctor_id)
    if (!branch.doctors.has(doctorKey)) branch.doctors.set(doctorKey, { id: token.doctor_id, name: token.doctor_name, visits: [] })
    branch.doctors.get(doctorKey).visits.push(token)
  }
  const sections = [...branchMap.values()].filter(b => session.role !== 'DOCTOR' || b.doctors.size > 0)
  const tokenRow = token => <div className="doctor-token-row" key={token.id}>
    <span className="token-gradient">{token.token_number}</span>
    <div className="doctor-token-info"><strong>{token.patient_name}</strong><small>Appointment #{token.appointment_id} · {displayTime(token.starts_at)}</small><small>{token.wait_minutes} min waiting</small><span className={'status status-' + token.status.toLowerCase()}>{statusLabel(token.status)}</span></div>
    {canFinish && token.status === 'CALLED' && <div className="doctor-token-actions">
      <button disabled={busy} className="btn btn-sm btn-outline-primary" onClick={() => finish(token, 'complete')}>Complete</button>
      <button disabled={busy} className="btn btn-sm btn-outline-secondary" onClick={() => finish(token, 'skip')}>No-show</button>
    </div>}
    {staff && live && token.status === 'WAITING' && <div className="doctor-token-actions">
      <button disabled={busy} className="btn btn-sm btn-outline-primary" onClick={() => openReschedule(token)}>Reschedule</button>
      <button disabled={busy} className="btn btn-sm btn-outline-danger" onClick={() => remove(token)}>Delete</button>
    </div>}
  </div>

  return <>
    {!embedded && <div className="page-heading"><div><p className="eyebrow">QUEUE & CHANNELING</p><h1>Queue management</h1><p>{staff ? 'Manage patients by hospital branch and doctor.' : session.role === 'DOCTOR' ? 'Your consultation queue.' : 'Monitor branch queues and waiting patients.'}</p></div></div>}
    <section className="panel">
      <div className="branch-filter-bar"><div><h3>{displayDate(date)}</h3><p className="muted mb-0">{live ? 'Today’s live queue · Sri Lanka' : 'View only · Live actions are available for today'}</p></div>
        <div className="queue-date-controls"><label>Queue date<input className="form-control" type="date" value={date} disabled={busy} onChange={event => { if (event.target.value) setDate(event.target.value) }}/></label><button className="btn btn-outline-primary" disabled={busy} onClick={load}>Refresh</button></div>
      </div>
      {session.role !== 'DOCTOR' && <div className="branch-filter-pills mb-3">
        <button className={'branch-pill ' + (!branchFilter ? 'active' : '')} onClick={() => setBranchFilter('')}>All branches</button>
        {branches.map(b => <button key={b.id} className={'branch-pill ' + (branchFilter === String(b.id) ? 'active' : '')} onClick={() => setBranchFilter(String(b.id))}>{b.name}</button>)}
      </div>}
      {loading ? <p role="status">Loading queues…</p> : sections.length === 0 ? <p className="muted">No branches or queue records to display.</p> : sections.map(branch => <section className="branch-queue-section" key={branch.id}>
        <div className="branch-queue-header"><div className="branch-queue-title"><h3>{branch.name}</h3><span className="branch-badge">{branch.doctors.size} doctors</span></div><span className="muted">{displayDate(date)}</span></div>
        {branch.doctors.size === 0 ? <p className="muted">No active doctors assigned to this branch.</p> : <div className="doctor-queue-grid">{[...branch.doctors.values()].map(doctor => {
          const active = doctor.visits.filter(t => ['WAITING', 'CALLED'].includes(t.status))
          const history = doctor.visits.filter(t => !['WAITING', 'CALLED'].includes(t.status))
          const called = active.find(t => t.status === 'CALLED')
          const waiting = active.filter(t => t.status === 'WAITING')
          const calledElsewhere = tokens.some(t => String(t.doctor_id) === String(doctor.id) && t.status === 'CALLED')
          return <article className="doctor-queue-card" key={doctor.id}>
            <div className="doctor-queue-head"><div><h4>{doctor.name}</h4><small>Room {doctor.room || 'TBA'} · {waiting.length} waiting</small></div><div className="doctor-queue-current"><span>Now calling</span><strong>{called ? called.token_number : '—'}</strong></div></div>
            {called ? tokenRow(called) : <p className="doctor-queue-empty muted">No patient currently called.</p>}
            <div className="queue-call-controls">
              {staff && <button className="btn btn-primary" disabled={busy || !live || calledElsewhere || waiting.length === 0} onClick={() => call(doctor.id, branch.id)}>Call next</button>}
              {live && calledElsewhere && staff && <small>Finish the doctor’s current patient before calling another.</small>}
              {!active.length && <span className="muted">{doctor.visits.length ? 'Queue finished' : 'No tokens issued for this date'}</span>}
            </div>
            <div className="doctor-queue-list">{waiting.map(tokenRow)}</div>
            {history.length > 0 && <details className="queue-history"><summary>Finished visits and history ({history.length})</summary>{history.map(tokenRow)}</details>}
          </article>
        })}</div>}
      </section>)}
    </section>
    {changing && staff && live && <section className="panel mt-3" aria-label="Reschedule patient">
      <h3>Reschedule {changing.patient_name}</h3><p>{changing.doctor_name} · {changing.branch_name}. Choose a future time with the same doctor and branch.</p>
      {slotsLoading ? <p role="status">Loading available times…</p> : <><label className="form-label w-100">Available consultation time<select className="form-select" value={slotId} onChange={event => setSlotId(event.target.value)}><option value="">Choose a time</option>{slots.map(slot => <option key={slot.id} value={slot.id}>{displayTime(slot.starts_at)} · Room {slot.room || 'TBA'}</option>)}</select></label>{slots.length === 0 && <p className="muted">No available future slots for this doctor at this branch.</p>}</>}
      <button className="btn btn-primary" disabled={busy || !slotId || slotsLoading} onClick={reschedule}>Save new time</button><button className="btn btn-link" disabled={busy} onClick={closeReschedule}>Close</button>
    </section>}
    {staff && live && <section className="panel mt-4">
      <h3>Issue patient token</h3><p className="muted">Find a confirmed appointment scheduled for today.</p>
      <form onSubmit={checkIn}><label className="form-label w-100">Patient ID, appointment ID, NIC, phone, or name<div className="queue-search"><input className="form-control" maxLength={80} disabled={busy} value={search} onChange={event => { searchNumber.current++; setSearch(event.target.value); setMatches([]); setSelectedId(''); setSearched(false) }}/><button type="button" className="btn btn-outline-primary" disabled={busy || searching || !search.trim()} onClick={() => find(search)}>{searching ? 'Finding…' : 'Find patient'}</button></div></label>
        {searched && matches.length === 0 && <p className="muted">No confirmed appointment available for check-in today.</p>}
        <div className="queue-matches">{matches.map(a => <label className={'queue-match ' + (selectedId === String(a.appointment_id) ? 'queue-match-selected' : '')} key={a.appointment_id}><input type="radio" name="queue-appointment" disabled={busy} checked={selectedId === String(a.appointment_id)} onChange={() => setSelectedId(String(a.appointment_id))}/><span className="queue-match-body"><strong>{a.patient_name}</strong><span>Appointment #{a.appointment_id} · {a.doctor_name} · {a.branch_name}</span><span>{displayTime(a.starts_at)}</span></span></label>)}</div>
        <button className="btn btn-primary mt-3" disabled={busy || !selectedId}>Issue token</button>
      </form>
    </section>}
  </>
}
