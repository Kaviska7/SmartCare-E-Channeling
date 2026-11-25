import React, { useEffect, useState } from 'react'
import { api } from '../api'
import '../public.css'
import smartcareLogo from '../assets/smartcare-logo.png'

export default function PublicBrowse({ onLogin, onRegister, onBook }) {
  const [doctors, setDoctors] = useState([])
  const [specialties, setSpecialties] = useState([])
  const [branches, setBranches] = useState([])
  const [branchId, setBranchId] = useState('')
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState(null)
  const [slots, setSlots] = useState([])
  const [error, setError] = useState('')
  const [loaded, setLoaded] = useState(false)
  useEffect(() => {
    setLoaded(false)
    setError('')
    Promise.all([api.get('/doctors?specialty=' + encodeURIComponent(filter) + (branchId ? '&branchId=' + branchId : '')), api.get('/doctors/specialties'), api.get('/branches')])
      .then(([all, kinds, places]) => { setDoctors(all); setSpecialties(kinds); setBranches(places); setLoaded(true) })
      .catch(e => setError(e.message))
  }, [filter, branchId])
  const select = async id => {
    setSelected(id)
    try { setSlots(await api.get('/doctors/' + id + '/slots' + (branchId ? '?branchId=' + branchId : ''))) }
    catch (e) { setError(e.message) }
  }
  const doctor = doctors.find(d => d.id === selected)
  const todayDoctors = doctors.filter(d => Number(d.today_open_slots) > 0)
  const laterDoctors = doctors.filter(d => Number(d.today_open_slots) === 0)
  const todaySlots = slots.filter(s => new Date(s.starts_at).toDateString() === new Date().toDateString())
  const laterSlots = slots.filter(s => new Date(s.starts_at).toDateString() !== new Date().toDateString())
  const doctorCard = d => <button key={d.id} className={'doctor-card ' + (selected === d.id ? 'selected' : '')} onClick={() => select(d.id)}><span className="avatar">✚</span><strong>{d.full_name}</strong><span>{d.specialty}</span>{Number(d.review_count) > 0 && <small>★ {Number(d.average_rating).toFixed(1)} · {d.review_count} {Number(d.review_count) === 1 ? 'review' : 'reviews'}</small>}<small>{d.department}</small>{(d.branches || []).filter(b => !branchId || String(b.branch_id) === branchId).map(b => <small key={b.branch_id}>{b.branch_name} · Room {b.room || 'TBA'} · LKR {Number(b.fee).toLocaleString()}</small>)}{Number(d.today_open_slots) > 0 && <span className="today-badge">Available today · {d.today_open_slots} {Number(d.today_open_slots) === 1 ? 'time' : 'times'}</span>}</button>
  const slotButton = s => <button className="slot slot-button" key={s.id} onClick={() => onBook({ doctorId: doctor.id, slotId: s.id, branchId: s.branch_id })}>{new Date(s.starts_at).toLocaleString()} · {s.branch_name} · LKR {Number(s.fee).toLocaleString()} · Book</button>
  return <div className="public-shell">
    <header className="public-header">
      <div className="public-brand"><img className="public-logo" src={smartcareLogo} alt="SmartCare E-Channeling"/></div>
      <div className="public-header-contact">
        <a className="public-hotline" href="tel:+94705501456" aria-label="Call SmartCare hotline on 0705501456">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6 19.8 19.8 0 0 1-3.1-8.7A2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .3 1.9.7 2.8a2 2 0 0 1-.5 2.1L8 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.5c.9.4 1.8.6 2.8.7a2 2 0 0 1 1.8 2.1Z"/></svg>
          <span>Hotline: <strong>0705501456</strong></span>
        </a>
        <div className="actions"><button className="btn btn-outline-primary" onClick={() => onLogin('PATIENT')}>Patient sign in</button><button className="btn btn-outline-primary" onClick={() => onLogin('STAFF')}>Staff & doctor sign in</button><button className="btn btn-primary" onClick={onRegister}>Create patient account</button></div>
      </div>
    </header>
    <section className="public-hero"><p className="eyebrow">E-CHANNELING MADE SIMPLE</p><h1>Find the right doctor.<br/>Choose a time that works.</h1><p>Browse our doctors and available consultation times before creating an account. You only need to sign in when you book.</p><button className="btn btn-light" onClick={() => document.getElementById('public-doctors')?.scrollIntoView({ behavior: 'smooth' })}>Explore doctors</button></section>
    <main id="public-doctors" className="public-content">
      <div className="page-heading"><div><p className="eyebrow">OUR DOCTORS</p><h2>Who is available today?</h2><p>Choose a hospital branch and see its doctors and open consultation times.</p></div><div><select className="form-select specialty-filter mb-2" value={branchId} onChange={e => { setBranchId(e.target.value); setSelected(null); setSlots([]) }}><option value="">All branches</option>{branches.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}</select><select className="form-select specialty-filter" value={filter} onChange={e => setFilter(e.target.value)}><option value="">All specialties</option>{specialties.map(s => <option key={s.id} value={s.name}>{s.name}</option>)}</select></div></div>
      {error && <div className="alert alert-danger">{error}</div>}
      {loaded && doctors.length > 0 && (todayDoctors.length ? <div className="doctor-grid">{todayDoctors.map(doctorCard)}</div> : <section className="panel"><p className="muted mb-0">No doctors have open times left today. You can still choose a future appointment below.</p></section>)}
      {loaded && laterDoctors.length > 0 && <><h3 className="mt-5 mb-3">Other doctors</h3><div className="doctor-grid">{laterDoctors.map(doctorCard)}</div></>}
      {loaded && doctors.length === 0 && <p className="muted mt-4">No doctors match this specialty yet.</p>}
      {doctor && <section className="panel mt-4"><h3>Available times with {doctor.full_name}</h3>{todaySlots.length > 0 && <><h4>Today</h4><div className="slot-grid">{todaySlots.map(slotButton)}</div></>}{laterSlots.length > 0 && <><h4 className="mt-4">Future dates</h4><div className="slot-grid">{laterSlots.slice(0, 40).map(slotButton)}</div></>}{slots.length === 0 && <p className="muted">No open times are available right now.</p>}<p className="muted mt-3 mb-0">Booking asks you to sign in or create a patient account.</p></section>}
    </main>
  </div>
}
