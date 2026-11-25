import React, { useEffect, useState } from 'react'
import { api } from '../api'
import Account from './Account'
import { GMAIL_PATTERN, GMAIL_TITLE, sameEmail } from '../emailPolicy'

const empty = { fullName: '', email: '', password: '', registrationId: '', specialtyId: '', department: 'Outpatient', active: true, branches: [{ branchId: '', room: '', fee: '' }] }
const blankSchedule = { scheduleDate: '', startTime: '09:00', endTime: '12:00', slotMinutes: 30, capacity: 6, branchId: '' }

export default function Doctor({ session, flash, fail, onEmailChanged }) {
  const [doctors, setDoctors] = useState([])
  const [specialties, setSpecialties] = useState([])
  const [branches, setBranches] = useState([])
  const [newBranch, setNewBranch] = useState({ name: '', address: '' })
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState(null)
  const [mine, setMine] = useState(null)
  const [slots, setSlots] = useState([])
  const [schedules, setSchedules] = useState([])
  const [leaves, setLeaves] = useState([])
  const [leaveDraft, setLeaveDraft] = useState({ date: '', reason: '' })
  const [draft, setDraft] = useState(null)
  const [newDoctor, setNewDoctor] = useState(empty)
  const [specialtyName, setSpecialtyName] = useState('')
  const [schedule, setSchedule] = useState(blankSchedule)
  const [editingSchedule, setEditingSchedule] = useState(null)
  const selectedDoctor = session.role === 'DOCTOR' ? mine : doctors.find(d => d.id === selected)
  const canEdit = selected && (session.role === 'ADMIN' || (session.role === 'DOCTOR' && mine?.id === selected))
  const load = async () => {
    try {
      const doctorPath = session.role === 'ADMIN' ? '/doctors/admin?specialty=' : '/doctors?specialty='
      const [all, kinds, places] = await Promise.all([session.role === 'DOCTOR' ? Promise.resolve([]) : api.get(doctorPath + encodeURIComponent(filter)), api.get('/doctors/specialties'), api.get('/branches')])
      setDoctors(all); setSpecialties(kinds); setBranches(places)
    } catch (e) { fail(e) }
  }
  useEffect(() => { load() }, [filter])
  useEffect(() => { if (session.role === 'DOCTOR') api.get('/doctors/me').then(setMine).catch(fail) }, [session.role])
  useEffect(() => {
    if (session.role === 'DOCTOR' && mine?.id === selected) { api.get('/doctors/' + selected + '/schedules').then(setSchedules).catch(fail); api.get('/doctors/' + selected + '/leave').then(setLeaves).catch(fail) }
  }, [mine?.id, selected])
  const select = async id => {
    setSelected(id); setDraft(null); setSchedules([]); setLeaves([]); setEditingSchedule(null)
    const target = session.role === 'DOCTOR' ? mine : doctors.find(d => d.id === id)
    setSchedule({ ...blankSchedule, branchId: target?.branches?.[0]?.branch_id || '' })
    try {
      setSlots(await api.get('/doctors/' + id + '/slots'))
      if (session.role === 'ADMIN' || (session.role === 'DOCTOR' && mine?.id === id)) {
        setSchedules(await api.get('/doctors/' + id + '/schedules'))
        setLeaves(await api.get('/doctors/' + id + '/leave'))
      }
    } catch (e) { fail(e) }
  }
  const clearSelection = () => {
    if (session.role === 'DOCTOR') return
    setSelected(null); setSlots([]); setSchedules([]); setLeaves([]); setDraft(null); setEditingSchedule(null)
    setLeaveDraft({ date: '', reason: '' })
    setSchedule(blankSchedule)
  }
  useEffect(() => { if (mine?.id && !selected) select(mine.id) }, [mine?.id, selected])
  const startEdit = () => {
    const d = session.role === 'DOCTOR' ? mine : doctors.find(x => x.id === selected)
    if (d) setDraft({ ...empty, fullName: d.full_name, email: d.email || '', originalEmail: d.email || '', registrationId: d.registration_id || '', specialtyId: d.specialty_id || specialties.find(s => s.name === d.specialty)?.id || '', department: d.department, active: d.active === undefined ? true : Boolean(d.active), branches: (d.branches || []).map(b => ({ branchId: b.branch_id, room: b.room || '', fee: b.fee })) })
  }
  const saveEdit = async e => {
    e.preventDefault()
    try {
      const path = session.role === 'DOCTOR' ? '/doctors/me' : '/doctors/' + selected
      const body = { fullName: draft.fullName, email: draft.email, specialtyId: Number(draft.specialtyId), department: draft.department, active: Boolean(draft.active), branches: draft.branches.map(b => ({ branchId: Number(b.branchId), room: b.room, fee: Number(b.fee) })) }
      const result = await api.send('PUT', path, body)
      if (session.role === 'DOCTOR' && result.emailChanged) {
        await api.send('POST', '/auth/logout')
        onEmailChanged()
        return
      }
      flash('Doctor profile saved'); setDraft(null); await load()
      if (session.role === 'DOCTOR') setMine(await api.get('/doctors/me'))
    } catch (e) { fail(e) }
  }
  const addDoctor = async e => {
    e.preventDefault()
    try {
      await api.send('POST', '/doctors/with-account', { ...newDoctor, specialtyId: Number(newDoctor.specialtyId), branches: newDoctor.branches.map(b => ({ ...b, branchId: Number(b.branchId), fee: Number(b.fee) })) })
      flash('Doctor login and profile created'); setNewDoctor({ ...empty, branches: [{ branchId: '', room: '', fee: '' }] }); load()
    } catch (e) { fail(e) }
  }
  const addSpecialty = async e => {
    e.preventDefault()
    try {
      await api.send('POST', '/doctors/specialties', { name: specialtyName })
      flash('Specialty added'); setSpecialtyName(''); load()
    } catch (e) { fail(e) }
  }
  const removeSpecialty = async id => {
    if (!confirm('Remove this specialty? It must have no doctors assigned.')) return
    try { await api.send('DELETE', '/doctors/specialties/' + id); flash('Specialty removed'); load() } catch (e) { fail(e) }
  }
  const addBranch = async e => {
    e.preventDefault()
    try { await api.send('POST', '/branches', newBranch); setNewBranch({ name: '', address: '' }); flash('Branch added'); load() } catch (e) { fail(e) }
  }
  const removeBranch = async id => {
    if (!confirm('Remove this branch? It must have no doctors or appointments.')) return
    try { await api.send('DELETE', '/branches/' + id); flash('Branch removed'); load() } catch (e) { fail(e) }
  }
  const addSchedule = async e => {
    e.preventDefault()
    try {
      const path = '/doctors/' + selected + '/schedules' + (editingSchedule ? '/' + editingSchedule : '')
      await api.send(editingSchedule ? 'PUT' : 'POST', path, { ...schedule, branchId: Number(schedule.branchId), slotMinutes: Number(schedule.slotMinutes), capacity: Number(schedule.capacity) })
      flash(editingSchedule ? 'Schedule updated' : 'Schedule and slots created')
      setSchedule({ ...blankSchedule, branchId: selectedDoctor?.branches?.[0]?.branch_id || '' })
      select(selected)
    } catch (e) { fail(e) }
  }
  const startScheduleEdit = s => {
    setEditingSchedule(s.id)
    setSchedule({ scheduleDate: String(s.schedule_date).slice(0, 10), startTime: String(s.start_time).slice(0, 5), endTime: String(s.end_time).slice(0, 5), slotMinutes: s.slot_minutes, capacity: s.capacity, branchId: s.branch_id })
  }
  const disableSchedule = async id => {
    if (!confirm('Disable this schedule? Existing bookings stay on record.')) return
    try { await api.send('DELETE', '/doctors/' + selected + '/schedules/' + id); flash('Schedule disabled'); select(selected) } catch (e) { fail(e) }
  }
  const addLeave = async e => {
    e.preventDefault()
    try { await api.send('POST', '/doctors/' + selected + '/leave', leaveDraft); flash('Leave date saved'); setLeaveDraft({ date: '', reason: '' }); select(selected) } catch (e) { fail(e) }
  }
  const removeLeave = async id => {
    try { await api.send('DELETE', '/doctors/' + selected + '/leave/' + id); flash('Leave removed'); select(selected) } catch (e) { fail(e) }
  }
  const removeDoctor = async () => {
    if (!confirm('Permanently delete this unused doctor profile and login? This is allowed only when it has no related records.')) return
    try { await api.send('DELETE', '/doctors/' + selected); flash('Doctor profile deleted'); setSelected(null); setDraft(null); await load() }
    catch (error) { fail(error) }
  }
  const doctorFields = (value, setter, buttonText, editing = false) => <div className="form-grid">
    <label>Name<input className="form-control" required value={value.fullName} onChange={e => setter({ ...value, fullName: e.target.value })}/></label>
    {editing && <label>Login email<input className="form-control" type="email" pattern={sameEmail(value.email, value.originalEmail) ? undefined : GMAIL_PATTERN} title={sameEmail(value.email, value.originalEmail) ? undefined : GMAIL_TITLE} required value={value.email} onChange={e => setter({ ...value, email: e.target.value })}/><small className="muted">Changed emails must be unused Gmail addresses.</small></label>}
    <label>Medical registration ID (MBBS/SLMC)<input className="form-control" readOnly={editing} aria-readonly={editing} required={!editing} minLength="3" maxLength="50" pattern="[A-Za-z0-9][A-Za-z0-9/-]{2,49}" title={editing ? 'The medical registration ID cannot be changed' : 'Use 3-50 letters, numbers, hyphens, or slashes'} value={value.registrationId} onChange={e => !editing && setter({ ...value, registrationId: e.target.value })}/>{editing && <small className="muted">SLMC cannot be changed after the doctor is created.</small>}</label>
    <label>Specialty<select className="form-select" required value={value.specialtyId} onChange={e => setter({ ...value, specialtyId: e.target.value })}><option value="">Choose…</option>{specialties.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
    <label>Department<input className="form-control" required value={value.department} onChange={e => setter({ ...value, department: e.target.value })}/></label>
    <div className="form-grid-full"><strong>Branch assignments</strong>{value.branches.map((assignment, index) => <div className="form-grid mt-2" key={index}><label>Hospital branch<select className="form-select" required value={assignment.branchId} onChange={e => setter({ ...value, branches: value.branches.map((item, i) => i === index ? { ...item, branchId: e.target.value } : item) })}><option value="">Choose branch</option>{branches.map(b => <option key={b.id} value={b.id} disabled={value.branches.some((item, i) => i !== index && String(item.branchId) === String(b.id))}>{b.name}</option>)}</select></label><label>Room<input className="form-control" value={assignment.room} onChange={e => setter({ ...value, branches: value.branches.map((item, i) => i === index ? { ...item, room: e.target.value } : item) })}/></label><label>Fee<input className="form-control" type="number" min="0" required value={assignment.fee} onChange={e => setter({ ...value, branches: value.branches.map((item, i) => i === index ? { ...item, fee: e.target.value } : item) })}/></label>{value.branches.length > 1 && <button type="button" className="btn btn-outline-danger align-self-end" onClick={() => setter({ ...value, branches: value.branches.filter((_, i) => i !== index) })}>Remove branch</button>}</div>)}<button type="button" className="btn btn-outline-primary mt-2" disabled={value.branches.length >= branches.length} onClick={() => setter({ ...value, branches: [...value.branches, { branchId: '', room: '', fee: '' }] })}>Add another branch</button></div>
    {buttonText === 'Save profile' && <label className="form-check-label"><input className="form-check-input me-2" type="checkbox" checked={Boolean(value.active)} onChange={e => setter({ ...value, active: e.target.checked })}/>Active on public website</label>}
    <button className="btn btn-primary align-self-end">{buttonText}</button>
  </div>
  const visibleDoctors = session.role === 'DOCTOR'
    ? mine ? [{ ...mine, specialty: specialties.find(s => s.id === mine.specialty_id)?.name || '' }] : []
    : doctors
  return <>
    <div className="page-heading"><div><p className="eyebrow">{session.role === 'DOCTOR' ? 'MY PRACTICE' : 'DOCTOR MANAGEMENT'}</p><h1>{session.role === 'DOCTOR' ? 'My profile & schedule' : 'Doctors & availability'}</h1><p>{session.role === 'DOCTOR' ? 'Manage your own consultation times and profile.' : 'Find a specialist and see consultation times.'}</p></div>{session.role !== 'DOCTOR' && <select className="form-select specialty-filter" value={filter} onChange={e => setFilter(e.target.value)}><option value="">All specialties</option>{specialties.map(s => <option key={s.id}>{s.name}</option>)}</select>}</div>
    <div className="doctor-grid">{visibleDoctors.map(d => <button key={d.id} className={'doctor-card ' + (selected === d.id ? 'selected' : '')} aria-pressed={selected === d.id} onClick={() => selected === d.id && session.role !== 'DOCTOR' ? clearSelection() : select(d.id)}><span className="avatar">✚</span><strong>{d.full_name}</strong><span>{d.specialty}</span>{session.role === 'ADMIN' && <small>Medical ID: {d.registration_id || 'Not assigned'}</small>}<small>{d.department}</small>{(d.branches || []).map(b => <small key={b.branch_id}>{b.branch_name} · Room {b.room || 'TBA'} · LKR {Number(b.fee).toLocaleString()}</small>)}{d.active === false && <small>Inactive</small>}</button>)}</div>
    {selected && <section className="panel mt-4"><h3>{session.role === 'DOCTOR' ? 'Doctor profile' : 'Available consultation slots'}</h3>
      {session.role === 'DOCTOR' && mine && <div className="profile-summary"><div><span>Medical registration ID</span><strong>{mine.registration_id || 'Not assigned'}</strong></div><div><span>Login email</span><strong>{mine.email}</strong></div><div><span>Specialty</span><strong>{specialties.find(item => item.id === mine.specialty_id)?.name || 'Not assigned'}</strong></div><div><span>Department</span><strong>{mine.department}</strong></div>{(mine.branches || []).map(b => <div key={b.branch_id}><span>{b.branch_name}</span><strong>Room {b.room || 'TBA'} · LKR {Number(b.fee).toLocaleString()}</strong></div>)}<div><span>Status</span><strong>{mine.active ? 'Active' : 'Inactive'}</strong></div></div>}
      <h4 className={session.role === 'DOCTOR' ? 'mt-4' : ''}>Available consultation slots</h4>{slots.length === 0 ? <p className="muted">No open slots yet.</p> : <div className="slot-grid">{slots.slice(0, 30).map(s => <span className="slot" key={s.id}>{new Date(s.starts_at).toLocaleString()} · {s.branch_name} · Room {s.room || 'TBA'}</span>)}</div>}
      {canEdit && <><hr/><div className="d-flex justify-content-between align-items-center"><h4>Manage this doctor</h4><div className="actions"><button className="btn btn-outline-primary btn-sm" onClick={startEdit}>Edit profile</button>{session.role === 'ADMIN' && <button className="btn btn-outline-danger btn-sm" onClick={removeDoctor}>Delete profile</button>}</div></div>
        {draft && <form onSubmit={saveEdit}>{doctorFields(draft, setDraft, 'Save profile', true)}</form>}
        <h4 className="mt-4">Consultation dates</h4>{schedules.length === 0 && <p className="muted">No consultation dates yet.</p>}{schedules.map(s => <div key={s.id} className="list-row"><span>{s.branch_name} · {new Date(String(s.schedule_date).slice(0, 10) + 'T00:00:00').toLocaleDateString()} · {s.start_time}–{s.end_time} · {s.slot_minutes} minutes · {s.capacity} patients {!s.active && '(inactive)'}</span>{s.active && <div className="actions"><button className="btn btn-sm btn-outline-primary" onClick={() => startScheduleEdit(s)}>Edit</button><button className="btn btn-sm btn-outline-danger" onClick={() => disableSchedule(s.id)}>Disable</button></div>}</div>)}
        <h4 className="mt-3">{editingSchedule ? 'Edit consultation date' : 'Add consultation date'}</h4><form className="form-grid" onSubmit={addSchedule}><label>Branch<select className="form-select" required value={schedule.branchId} onChange={e => setSchedule({ ...schedule, branchId: e.target.value })}><option value="">Choose branch</option>{(selectedDoctor?.branches || []).filter(b => b.active !== false).map(b => <option key={b.branch_id} value={b.branch_id}>{b.branch_name}</option>)}</select></label><label>Date<input className="form-control" type="date" required min={new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)} value={schedule.scheduleDate} onChange={e => setSchedule({ ...schedule, scheduleDate: e.target.value })}/></label>{[['startTime', 'Start', 'time'], ['endTime', 'End', 'time'], ['slotMinutes', 'Minutes per slot', 'number'], ['capacity', 'Max patients', 'number']].map(([name, label, type]) => <label key={name}>{label}<input className="form-control" type={type} min={type === 'number' ? 1 : undefined} value={schedule[name]} onChange={e => setSchedule({ ...schedule, [name]: e.target.value })}/></label>)}<button className="btn btn-primary align-self-end">{editingSchedule ? 'Save consultation date' : 'Create consultation slots'}</button>{editingSchedule && <button type="button" className="btn btn-link" onClick={() => { setEditingSchedule(null); setSchedule({ ...blankSchedule, branchId: selectedDoctor?.branches?.[0]?.branch_id || '' }) }}>Cancel edit</button>}</form>
        <h4 className="mt-4">Leave dates</h4><p className="muted">Open times are hidden on leave days. If patients are already booked, handle their appointments before adding leave.</p>{leaves.map(l => <div className="list-row" key={l.id}><span>{String(l.leave_date).slice(0, 10)} · {l.reason || 'Leave'}</span><button className="btn btn-sm btn-outline-danger" onClick={() => removeLeave(l.id)}>Remove</button></div>)}<form className="form-grid" onSubmit={addLeave}><label>Date<input className="form-control" type="date" required min={new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)} value={leaveDraft.date} onChange={e => setLeaveDraft({ ...leaveDraft, date: e.target.value })}/></label><label>Reason (optional)<input className="form-control" maxLength="200" value={leaveDraft.reason} onChange={e => setLeaveDraft({ ...leaveDraft, reason: e.target.value })}/></label><button className="btn btn-primary align-self-end">Add leave</button></form>
      </>}</section>}
    {session.role === 'ADMIN' && <>
      <section className="panel mt-4"><h3>Hospital branches</h3>{branches.map(b => <div className="list-row" key={b.id}><div><strong>{b.name}</strong><small>{b.address || ''}</small></div><button className="btn btn-sm btn-outline-danger" onClick={() => removeBranch(b.id)}>Remove</button></div>)}<hr/><form className="form-grid" onSubmit={addBranch}><label>Branch name<input className="form-control" required value={newBranch.name} onChange={e => setNewBranch({ ...newBranch, name: e.target.value })}/></label><label>Address<input className="form-control" value={newBranch.address} onChange={e => setNewBranch({ ...newBranch, address: e.target.value })}/></label><button className="btn btn-primary align-self-end">Add branch</button></form></section>
      <section className="panel mt-4"><h3>Specialties</h3>{specialties.length === 0 && <p className="muted">No specialties yet.</p>}{specialties.map(s => <div className="list-row" key={s.id}><strong>{s.name}</strong><button className="btn btn-sm btn-outline-danger" onClick={() => removeSpecialty(s.id)}>Remove</button></div>)}<hr/><h4>Add specialty</h4><form className="form-grid" onSubmit={addSpecialty}><label>Specialty name<input className="form-control" required value={specialtyName} onChange={e => setSpecialtyName(e.target.value)}/></label><button className="btn btn-primary align-self-end">Add specialty</button></form></section>
      <section className="panel mt-4"><h3>Add doctor and login</h3><p className="muted">Each doctor gets a separate email and password linked to this profile.</p><form onSubmit={addDoctor}><div className="form-grid"><label>Login email<input className="form-control" type="email" pattern={GMAIL_PATTERN} title={GMAIL_TITLE} required value={newDoctor.email} onChange={e => setNewDoctor({ ...newDoctor, email: e.target.value })}/></label><label>Initial password<input className="form-control" type="password" minLength="8" required value={newDoctor.password} onChange={e => setNewDoctor({ ...newDoctor, password: e.target.value })}/></label></div>{doctorFields(newDoctor, setNewDoctor, 'Create doctor')}</form></section>
    </>}
    {session.role === 'DOCTOR' && <Account flash={flash} fail={fail} onEmailChanged={onEmailChanged}/>}
  </>
}
