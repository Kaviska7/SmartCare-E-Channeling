import React, { useEffect, useMemo, useState } from 'react'
import { api } from '../api'

const emptyDraft = { appointmentId: '', rating: 5, comment: '' }

export default function Feedback({ session, flash, fail, initialAppointmentId, onTargetHandled }) {
  const patient = session.role === 'PATIENT'
  const [items, setItems] = useState([])
  const [appointments, setAppointments] = useState([])
  const [draft, setDraft] = useState(emptyDraft)
  const [editing, setEditing] = useState(null)

  const load = async () => {
    try {
      if (patient) {
        const [own, visits] = await Promise.all([api.get('/reviews'), api.get('/appointments')])
        setItems(own); setAppointments(visits)
      } else setItems(await api.get('/reviews/admin'))
    } catch (error) { fail(error) }
  }
  useEffect(() => { load() }, [session.role])
  useEffect(() => {
    if (patient && initialAppointmentId) {
      setEditing(null); setDraft({ appointmentId: String(initialAppointmentId), rating: 5, comment: '' })
      onTargetHandled?.()
    }
  }, [initialAppointmentId, patient])

  const reviewedIds = useMemo(() => new Set(items.map(item => Number(item.appointment_id))), [items])
  const eligible = appointments.filter(item => item.status === 'COMPLETED' && !reviewedIds.has(Number(item.id)))

  const save = async event => {
    event.preventDefault()
    try {
      if (editing) await api.send('PUT', '/reviews/' + editing, { rating: Number(draft.rating), comment: draft.comment.trim() })
      else await api.send('POST', '/reviews', { appointmentId: Number(draft.appointmentId), rating: Number(draft.rating), comment: draft.comment.trim() })
      flash(editing ? 'Feedback updated' : 'Feedback submitted')
      setEditing(null); setDraft(emptyDraft); await load()
    } catch (error) { fail(error) }
  }
  const startEdit = item => { setEditing(item.id);setDraft({ appointmentId: String(item.appointment_id), rating: item.rating, comment: item.comment || '' }) }
  const remove = async item => {
    if (!confirm(patient ? 'Delete this feedback? You can submit replacement feedback later.' : 'Delete this patient feedback?')) return
    try { await api.send('DELETE', '/reviews/' + item.id);flash('Feedback deleted');if (editing === item.id) { setEditing(null);setDraft(emptyDraft) }await load() }
    catch (error) { fail(error) }
  }

  return <>
    <div className="page-heading"><div><p className="eyebrow">FEEDBACK MANAGEMENT</p><h1>{patient ? 'My feedback' : 'Patient feedback'}</h1><p>{patient ? 'Optionally rate completed consultations and manage your comments.' : 'Review feedback submitted for completed appointments.'}</p></div></div>
    {patient && <section className="panel"><h3>{editing ? 'Edit feedback' : 'Leave feedback'}</h3>
      {!editing && eligible.length === 0 ? <p className="muted">No completed appointments are waiting for feedback.</p> : <form className="form-grid" onSubmit={save}>
        <label>Completed appointment<select className="form-select" required disabled={Boolean(editing)} value={draft.appointmentId} onChange={event => setDraft({ ...draft, appointmentId: event.target.value })}><option value="">Choose appointment</option>{(editing ? appointments.filter(item => Number(item.id) === Number(draft.appointmentId)) : eligible).map(item => <option key={item.id} value={item.id}>#{item.id} · {item.doctor_name} · {new Date(item.starts_at).toLocaleString()}</option>)}</select></label>
        <label>Rating<select className="form-select" value={draft.rating} onChange={event => setDraft({ ...draft, rating: event.target.value })}>{[5,4,3,2,1].map(value => <option key={value} value={value}>{value} / 5</option>)}</select></label>
        <label>Comment (optional)<textarea className="form-control" maxLength="500" value={draft.comment} onChange={event => setDraft({ ...draft, comment: event.target.value })}/></label>
        <button className="btn btn-primary align-self-end">{editing ? 'Save changes' : 'Submit feedback'}</button>
        {editing && <button type="button" className="btn btn-link align-self-end" onClick={() => { setEditing(null);setDraft(emptyDraft) }}>Cancel edit</button>}
      </form>}
    </section>}
    <section className={'panel ' + (patient ? 'mt-4' : '')}><h3>{patient ? `Submitted feedback (${items.length})` : `All active feedback (${items.length})`}</h3>
      {items.length === 0 ? <p className="muted">No feedback to display.</p> : items.map(item => <div className="feedback-row" key={item.id}><div><strong>{'★'.repeat(Number(item.rating))}{'☆'.repeat(5-Number(item.rating))} · Appointment #{item.appointment_id}</strong><span>{patient ? item.doctor_name : `${item.patient_name} → ${item.doctor_name}`}</span><small>{item.branch_name} · {new Date(item.starts_at).toLocaleString()}</small>{!patient && <small>{item.patient_email}</small>}<p>{item.comment || 'No written comment.'}</p><small>Updated {new Date(item.updated_at).toLocaleString()}</small></div><div className="actions">{patient && <button className="btn btn-sm btn-outline-primary" onClick={() => startEdit(item)}>Edit</button>}<button className="btn btn-sm btn-outline-danger" onClick={() => remove(item)}>Delete</button></div></div>)}
    </section>
  </>
}
