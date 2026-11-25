import React, { useEffect, useState } from 'react'
import { api } from '../api'
import Queue from './Queue'

export default function Appointment({ session, flash, fail, pendingBooking, onPendingHandled, onLeaveFeedback }) {
  const [appointments, setAppointments] = useState([])
  const [doctors, setDoctors] = useState([])
  const [branches, setBranches] = useState([])
  const [branchId, setBranchId] = useState('')
  const [doctorId, setDoctorId] = useState('')
  const [slots, setSlots] = useState([])
  const [slotId, setSlotId] = useState('')
  const [changing, setChanging] = useState(null)
  const [receipt, setReceipt] = useState(null)
  const [paymentMethod, setPaymentMethod] = useState('DEMO_CARD')
  const [paymentTarget, setPaymentTarget] = useState(null)
  const [reviews, setReviews] = useState([])
  const [now, setNow] = useState(Date.now())
  const [patientSearch, setPatientSearch] = useState('')
  const [patientMatches, setPatientMatches] = useState([])
  const [selectedPatient, setSelectedPatient] = useState('')
  const [adminDoctorTab, setAdminDoctorTab] = useState('all')
  const patient = session.role === 'PATIENT'
  const doctor = session.role === 'DOCTOR'
  const reception = session.role === 'RECEPTION' || session.role === 'ADMIN'
  const load = () => api.get('/appointments').then(setAppointments).catch(fail)
  useEffect(() => { load(); if (patient || reception) { api.get('/doctors').then(setDoctors).catch(fail); api.get('/branches').then(setBranches).catch(fail) } if (doctor) api.get('/doctors/me').then(d => setDoctors([d])).catch(fail); if (patient) api.get('/reviews').then(setReviews).catch(fail) }, [])
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 30000); return () => clearInterval(timer) }, [])
  useEffect(() => { if (!doctor) return; const timer = setInterval(load, 15000); return () => clearInterval(timer) }, [])
  useEffect(() => {
    if (patient && pendingBooking?.doctorId) {
      if (pendingBooking.branchId) setBranchId(String(pendingBooking.branchId))
      setDoctorId(String(pendingBooking.doctorId))
      api.get('/doctors/' + pendingBooking.doctorId + '/slots' + (pendingBooking.branchId ? '?branchId=' + pendingBooking.branchId : '')).then(open => {
        setSlots(open)
        setSlotId(open.some(s => s.id === pendingBooking.slotId) ? String(pendingBooking.slotId) : '')
        onPendingHandled?.()
      }).catch(fail)
    }
  }, [pendingBooking?.slotId])
  const chooseDoctor = async (id, chosenBranch = branchId) => {
    setDoctorId(id); setSlotId('')
    try { setSlots(id ? await api.get('/doctors/' + id + '/slots' + (chosenBranch ? '?branchId=' + chosenBranch : '')) : []) } catch (e) { fail(e) }
  }
  const openDemoPayment = id => {
    setPaymentTarget(id); setReceipt(null)
    setTimeout(() => document.getElementById('demo-payment')?.scrollIntoView({ behavior: 'smooth' }), 100)
  }
  const book = async e => {
    e.preventDefault()
    try {
      const result = await api.send('POST', '/appointments', { slotId: Number(slotId), branchId: Number(branchId), paymentMethod })
      flash('Appointment #' + result.id + ' is pending administrator confirmation' + (paymentMethod === 'DEMO_CARD' ? '. You may submit the demo payment below.' : '. Pay at hospital selected.'))
      if (paymentMethod === 'DEMO_CARD') openDemoPayment(result.id)
      setSlotId(''); chooseDoctor(doctorId); load()
    } catch (e) { fail(e) }
  }
  const findPatients = async e => {
    e.preventDefault()
    try { setPatientMatches(await api.get('/appointments/patients?search=' + encodeURIComponent(patientSearch.trim()))); setSelectedPatient('') }
    catch (e) { fail(e) }
  }
  const staffBook = async e => {
    e.preventDefault()
    try {
      const result = await api.send('POST', '/appointments/staff-book', { patientId: Number(selectedPatient), slotId: Number(slotId) })
      flash('Appointment #' + result.id + ' booked and pending administrator confirmation')
      setSlotId(''); setSelectedPatient(''); setPatientMatches([]); setPatientSearch('')
      chooseDoctor(doctorId); load()
    } catch (e) { fail(e) }
  }
  const cancel = async id => {
    if (!confirm('Cancel this appointment?')) return
    try { await api.send('POST', '/appointments/' + id + '/cancel'); flash('Appointment cancelled'); load() } catch (e) { fail(e) }
  }
  const confirmAppointment = async id => {
    try { await api.send('POST', '/appointments/' + id + '/confirm'); flash('Appointment confirmed'); load() }
    catch (error) { fail(error) }
  }
  const reschedule = async id => {
    try {
      await api.send('PUT', '/appointments/' + id + '/slot', { slotId: Number(slotId) })
      flash('Appointment rescheduled'); setChanging(null); setSlotId(''); load(); chooseDoctor(doctorId)
    } catch (e) { fail(e) }
  }
  const pay = async (id, choice) => {
    try { setReceipt(await api.send('POST', '/appointments/' + id + '/payment', { choice })); setPaymentTarget(null); flash(choice === 'DEMO_PAID' ? 'Demo payment submitted' : 'Pay at hospital selected'); load() } catch (e) { fail(e) }
  }
  const submitPayment = e => { e.preventDefault(); pay(paymentTarget, 'DEMO_PAID') }
  const title = patient ? 'Your appointments' : doctor ? 'Your appointments and queue' : session.role === 'ADMIN' ? 'Upcoming appointments' : 'Today’s appointments'
  const canChange = a => ['PENDING','CONFIRMED'].includes(a.status) && new Date(a.starts_at).getTime() > now
  const changeTarget = appointments.find(a => a.id === changing && canChange(a))
  const branchDoctors = branchId ? doctors.filter(d => (d.branches || []).some(b => String(b.branch_id) === branchId && b.active !== false)) : []
  const assignmentFor = d => (d.branches || []).find(b => String(b.branch_id) === branchId)
  const adminUpcomingAppointments = session.role === 'ADMIN'
    ? appointments.filter(a => ['PENDING', 'CONFIRMED'].includes(a.status) && new Date(a.starts_at).getTime() > now)
      .sort((a, b) => new Date(a.starts_at) - new Date(b.starts_at))
    : []
  const adminDoctorTabs = session.role === 'ADMIN'
    ? Array.from(new Map([
        ...doctors.map(d => [String(d.id), { id: String(d.id), name: d.full_name || d.doctor_name || 'Unknown doctor' }]),
        ...appointments.map(a => [String(a.doctor_id), { id: String(a.doctor_id), name: a.doctor_name || 'Unknown doctor' }])
      ]).values()).sort((a, b) => a.name.localeCompare(b.name))
    : []
  const adminVisibleAppointments = adminDoctorTab === 'all'
    ? adminUpcomingAppointments
    : adminUpcomingAppointments.filter(a => String(a.doctor_id) === adminDoctorTab)
  useEffect(() => {
    if (session.role === 'ADMIN' && adminDoctorTab !== 'all' && !adminDoctorTabs.some(tab => tab.id === adminDoctorTab)) setAdminDoctorTab('all')
  }, [session.role, adminDoctorTab, doctors, appointments])
  const appointmentRow = a => <div className="appointment-row" key={a.id}>
    <div><strong>#{a.id} · {patient ? a.doctor_name : a.patient_name}</strong><span>{new Date(a.starts_at).toLocaleString()} · {a.specialty || a.doctor_name || ''}</span>
      <small className={'status status-' + String(a.status).toLowerCase()}>{a.status.replace('_', ' ')}</small>
      {patient && <><small>Branch: {a.branch_name}</small><small>Fee at booking: LKR {Number(a.fee).toLocaleString()}</small><small>Payment: {a.payment_status === 'PENDING_DEMO' ? 'Demo card · awaiting submission' : a.payment_status === 'DEMO_PAID' ? 'Demo card · submitted' : a.payment_status === 'PAY_AT_HOSPITAL' ? 'Pay at hospital' : a.payment_status || 'Not selected'}</small></>}
      {patient && reviews.find(r => Number(r.appointment_id) === Number(a.id)) && <small>Your rating: {reviews.find(r => Number(r.appointment_id) === Number(a.id)).rating} / 5</small>}
      {doctor && <small>Patient details: phone {a.phone || 'not provided'} · born {a.date_of_birth || 'not provided'}</small>}
      {reception && <><small>Confirm identity: NIC {a.nic || 'not provided'} · phone {a.phone || 'not provided'}</small>{session.role === 'ADMIN' && <small>{a.branch_name} · LKR {Number(a.fee).toLocaleString()} · Payment: {a.payment_status || 'Not selected'}</small>}</>}
    </div>
    {(patient || doctor || session.role === 'ADMIN') && canChange(a) && <div className="actions">{session.role === 'ADMIN' && a.status === 'PENDING' && <button className="btn btn-sm btn-primary" onClick={() => confirmAppointment(a.id)}>Confirm</button>}<button className="btn btn-sm btn-outline-primary" onClick={() => { setChanging(a.id); setBranchId(String(a.branch_id)); chooseDoctor(a.doctor_id, String(a.branch_id)) }}>Reschedule</button><button className="btn btn-sm btn-outline-danger" onClick={() => cancel(a.id)}>Cancel</button>{patient && a.payment_status !== 'DEMO_PAID' && <><button className="btn btn-sm btn-primary" onClick={() => openDemoPayment(a.id)}>{a.payment_status === 'PENDING_DEMO' ? 'Complete demo payment' : 'Demo pay'}</button><button className="btn btn-sm btn-outline-secondary" onClick={() => pay(a.id, 'PAY_AT_HOSPITAL')}>Pay at hospital</button></>}</div>}
    {patient && ['DEMO_PAID', 'PAY_AT_HOSPITAL', 'DEMO_REFUNDED'].includes(a.payment_status) && <button className="btn btn-sm btn-outline-primary" onClick={() => api.get('/appointments/' + a.id + '/payment').then(setReceipt).catch(fail)}>View receipt</button>}
    {patient && a.status === 'COMPLETED' && !reviews.some(r => Number(r.appointment_id) === Number(a.id)) && <button className="btn btn-sm btn-outline-primary" onClick={() => onLeaveFeedback?.(a.id)}>Leave feedback</button>}
  </div>
  return <>
    <div className="page-heading"><div><p className="eyebrow">APPOINTMENT MANAGEMENT</p><h1>{title}</h1><p>{patient ? 'Book and manage your doctor visits.' : doctor ? 'See your assigned visits, manage upcoming appointments, and follow today’s queue.' : session.role === 'ADMIN' ? 'Review every booking, including future visits and payment updates.' : 'Review today’s booked visits and patient check-in details.'}</p></div></div>
    {patient && <section className="panel"><h3>Book a consultation</h3><form className="form-grid" onSubmit={book}><label>Hospital branch<select className="form-select" required value={branchId} onChange={e => { setBranchId(e.target.value); chooseDoctor('') }}><option value="">Choose a branch</option>{branches.map(b => <option value={b.id} key={b.id}>{b.name}</option>)}</select></label><label>Doctor<select className="form-select" required value={doctorId} onChange={e => chooseDoctor(e.target.value)}><option value="">Choose a doctor</option>{branchDoctors.map(d => <option value={d.id} key={d.id}>{d.full_name} · {d.specialty} · LKR {assignmentFor(d)?.fee}</option>)}</select></label><label>Available time<select className="form-select" required value={slotId} onChange={e => setSlotId(e.target.value)}><option value="">Choose a slot</option>{slots.map(s => <option key={s.id} value={s.id}>{new Date(s.starts_at).toLocaleString()} · Room {s.room || 'TBA'}</option>)}</select></label><label>Payment method<select className="form-select" value={paymentMethod} onChange={e => setPaymentMethod(e.target.value)}><option value="DEMO_CARD">Demo card</option><option value="PAY_AT_HOSPITAL">Pay at hospital</option></select></label><button className="btn btn-primary align-self-end">Confirm booking</button></form><small className="muted">The selected method is saved with your booking. Demo card payment is completed on the next screen.</small></section>}
    {reception && <section className="panel"><h3>Book for a registered patient</h3><form className="form-grid" onSubmit={findPatients}><label>Search by patient name, NIC, or phone<input className="form-control" minLength="2" required value={patientSearch} onChange={e => setPatientSearch(e.target.value)}/></label><button className="btn btn-outline-primary align-self-end">Find patient</button></form>{patientMatches.length > 0 && <form className="form-grid mt-3" onSubmit={staffBook}><label>Patient<select className="form-select" required value={selectedPatient} onChange={e => setSelectedPatient(e.target.value)}><option value="">Choose patient</option>{patientMatches.map(p => <option key={p.id} value={p.id}>{p.full_name} · {p.nic} · {p.phone}</option>)}</select></label><label>Hospital branch<select className="form-select" required value={branchId} onChange={e => { setBranchId(e.target.value); chooseDoctor('') }}><option value="">Choose branch</option>{branches.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label><label>Doctor<select className="form-select" required value={doctorId} onChange={e => chooseDoctor(e.target.value)}><option value="">Choose doctor</option>{branchDoctors.map(d => <option key={d.id} value={d.id}>{d.full_name} · {d.specialty} · LKR {assignmentFor(d)?.fee}</option>)}</select></label><label>Available time<select className="form-select" required value={slotId} onChange={e => setSlotId(e.target.value)}><option value="">Choose slot</option>{slots.map(s => <option key={s.id} value={s.id}>{new Date(s.starts_at).toLocaleString()} · Room {s.room || 'TBA'}</option>)}</select></label><button className="btn btn-primary align-self-end">Book appointment</button></form>}{patientSearch && patientMatches.length === 0 && <small className="muted">Search for a patient who already has an account.</small>}</section>}
    <section className="panel mt-4"><div className="section-head appointment-list-head"><h3>{patient ? 'Appointment history' : title}</h3>{doctor && <button className="btn btn-outline-primary btn-sm" onClick={load}>Refresh</button>}</div>
      {session.role === 'ADMIN'
        ? <><div className="doctor-appointment-tabs" role="tablist" aria-label="Appointments by doctor">
            <button type="button" role="tab" aria-selected={adminDoctorTab === 'all'} aria-controls="admin-appointment-panel" className={'doctor-appointment-tab ' + (adminDoctorTab === 'all' ? 'selected' : '')} onClick={() => setAdminDoctorTab('all')}>All doctors <span>{adminUpcomingAppointments.length}</span></button>
            {adminDoctorTabs.map(tab => <button type="button" role="tab" aria-selected={adminDoctorTab === tab.id} aria-controls="admin-appointment-panel" className={'doctor-appointment-tab ' + (adminDoctorTab === tab.id ? 'selected' : '')} key={tab.id} onClick={() => setAdminDoctorTab(tab.id)}>{tab.name} <span>{adminUpcomingAppointments.filter(a => String(a.doctor_id) === tab.id).length}</span></button>)}
          </div><div id="admin-appointment-panel" className="doctor-appointment-panel" role="tabpanel">
            {adminVisibleAppointments.length === 0
              ? <p className="muted">{adminDoctorTab === 'all' ? 'No upcoming appointments to display.' : 'No upcoming appointments for this doctor.'}</p>
              : adminVisibleAppointments.map(appointmentRow)}
          </div></>
        : appointments.length === 0 ? <p className="muted">No appointments to display.</p> : appointments.map(appointmentRow)}
      {changeTarget && <div className="inline-form"><h4>Reschedule appointment #{changing}</h4><p className="muted">Choose a new time with the same doctor.</p><select className="form-select" value={slotId} onChange={e => setSlotId(e.target.value)}><option value="">Choose new slot</option>{slots.map(s => <option key={s.id} value={s.id}>{new Date(s.starts_at).toLocaleString()}</option>)}</select><button className="btn btn-primary mt-2" disabled={!slotId} onClick={() => reschedule(changing)}>Save new time</button><button className="btn btn-link mt-2" onClick={() => setChanging(null)}>Close</button></div>}
    </section>
    {patient && paymentTarget && <section className="panel mt-4" id="demo-payment"><h3>Demo card payment · appointment #{paymentTarget}</h3><p className="muted">These are prefilled test details. Click Submit payment to record a simulated payment. No card information is sent to the server and no money is charged.</p><form className="form-grid" onSubmit={submitPayment}><label>Cardholder<input className="form-control" value="SmartCare Demo" readOnly/></label><label>Test card number<input className="form-control" value="4242 4242 4242 4242" readOnly/></label><label>Expiry<input className="form-control" value="12/30" readOnly/></label><label>Security code<input className="form-control" value="123" readOnly/></label><button className="btn btn-primary align-self-end">Submit payment</button><button className="btn btn-outline-secondary align-self-end" type="button" onClick={() => setPaymentTarget(null)}>Close</button></form></section>}
    {receipt && <section className="panel mt-4"><h3>{receipt.status === 'DEMO_PAID' ? 'Demo payment receipt' : 'Payment choice receipt'}</h3><p>Appointment #{receipt.appointment_id} · LKR {Number(receipt.amount).toLocaleString()} · {receipt.status.replaceAll('_', ' ')}</p>{receipt.choice === 'DEMO_CARD' && <p>Test card ending 4242</p>}<small className="muted">This is a simulation. No card details were sent and no real money was processed.</small></section>}
    {doctor && <Queue session={session} flash={flash} fail={fail} embedded/>}
  </>
}
