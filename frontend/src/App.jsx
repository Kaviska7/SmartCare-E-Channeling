import React, { useEffect, useState } from 'react'
import { api } from './api'
import Patient from './features/Patient'
import Doctor from './features/Doctor'
import Appointment from './features/Appointment'
import Queue from './features/Queue'
import Notification, { alertCategory } from './features/Notification'
import Report from './features/Report'
import Admin from './features/Admin'
import Account from './features/Account'
import PublicBrowse from './features/PublicBrowse'
import Feedback from './features/Feedback'
import smartcareMark from './assets/smartcare-mark.png'
import { GMAIL_PATTERN, GMAIL_TITLE } from './emailPolicy'

export default function App() {
  const [session, setSession] = useState(undefined)
  const [page, setPage] = useState('appointments')
  const [authMode, setAuthMode] = useState('browse')
  const [loginPortal, setLoginPortal] = useState('PATIENT')
  const [visiblePasswords, setVisiblePasswords] = useState({})
  useEffect(() => { setVisiblePasswords({}) }, [authMode, loginPortal])
  const [pendingBooking, setPendingBooking] = useState(null)
  const [feedbackTarget, setFeedbackTarget] = useState(null)
  const [alerts, setAlerts] = useState([])
  const [alertsOpen, setAlertsOpen] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [authBusy, setAuthBusy] = useState(false)
  const [form, setForm] = useState({ email: '', password: '', fullName: '', nic: '', dateOfBirth: '', phone: '', address: '', contactPreference: 'IN_APP', rawCode: '', newPassword: '' })
  useEffect(() => { api.get('/auth/session').then(current => { setSession(current); setPage(current.role === 'RECEPTION' ? 'queue' : 'appointments') }).catch(e => { setSession({}); setError(e.message) }) }, [])
  const refreshAlerts = () => api.get('/notifications').then(setAlerts).catch(() => {})
  useEffect(() => {
    if (!session?.id || !['PATIENT', 'DOCTOR'].includes(session.role)) { setAlerts([]); setAlertsOpen(false); return }
    refreshAlerts()
    const timer = setInterval(refreshAlerts, 30000)
    return () => clearInterval(timer)
  }, [session?.id, session?.role])
  useEffect(() => { if (notice && session?.id && ['PATIENT','DOCTOR'].includes(session.role)) refreshAlerts() }, [notice])
  const flash = message => { setNotice(message); setError('') }
  const fail = e => { setError(e.message); setNotice('') }
  const field = (name, label, type = 'text') => {
    const isPassword = type === 'password'
    const visible = Boolean(visiblePasswords[name])
    const inputId = `auth-${name}`
    return <div className="form-label w-100" key={name}>
      <label htmlFor={inputId}>{label}{name === 'nic' ? ' (12 digits)' : name === 'phone' ? ' (10 digits)' : ''}</label>
      <div className={isPassword ? 'auth-password-field' : undefined}>
        <input id={inputId} className="form-control" type={name === 'phone' ? 'tel' : isPassword && visible ? 'text' : type} inputMode={['nic','phone'].includes(name) ? 'numeric' : undefined} pattern={name === 'email' && authMode === 'register' ? GMAIL_PATTERN : name === 'nic' ? '[0-9]{12}' : name === 'phone' ? '[0-9]{10}' : undefined} title={name === 'email' && authMode === 'register' ? GMAIL_TITLE : name === 'nic' ? 'Enter exactly 12 digits' : name === 'phone' ? 'Enter exactly 10 digits' : undefined} maxLength={name === 'nic' ? 12 : name === 'phone' ? 10 : undefined} autoComplete={isPassword ? (authMode === 'login' ? 'current-password' : 'new-password') : undefined} minLength={name === 'newPassword' || (name === 'password' && authMode === 'register') ? 8 : undefined} required={name !== 'address'} value={form[name] || ''} onChange={e => setForm({ ...form, [name]: e.target.value })}/>
        {isPassword && <button type="button" className="auth-password-toggle" aria-label={visible ? 'Hide password' : 'Show password'} aria-controls={inputId} aria-pressed={visible} onClick={() => setVisiblePasswords(current => ({ ...current, [name]: !current[name] }))}>{visible ? 'Hide' : 'Show'}</button>}
      </div>
    </div>
  }
  const submit = async e => {
    e.preventDefault()
    if (authBusy) return
    setAuthBusy(true)
    try {
      if (authMode === 'register') {
        await api.send('POST', '/auth/register', form)
        flash('Account created. Please sign in to book.')
        setForm({ ...form, password: '', fullName: '', nic: '', dateOfBirth: '', phone: '', address: '', contactPreference: 'IN_APP' })
        setAuthMode('login')
      } else if (authMode === 'forgot') {
        const res = await api.send('POST', '/auth/forgot-password', { email: form.email })
        flash(res.message)
        setForm(current => ({ ...current, email: current.email.trim().toLowerCase(), rawCode: '', newPassword: '' }))
        setAuthMode('reset')
      } else if (authMode === 'reset') {
        const res = await api.send('POST', '/auth/reset-password', { email: form.email, rawCode: form.rawCode, newPassword: form.newPassword })
        flash(res.message || 'Password reset successfully.')
        setForm({ ...form, password: '', rawCode: '', newPassword: '' })
        setAuthMode('login')
      } else {
        await api.login(form.email, form.password, loginPortal)
        const current = await api.get('/auth/session')
        setSession(current)
        setPage(current.role === 'RECEPTION' ? 'queue' : 'appointments')
        flash('Welcome to SmartCare')
      }
    } catch (e) { fail(e) }
    finally { setAuthBusy(false) }
  }
  if (session === undefined) return <div className="loading">Loading SmartCare…</div>
  if (!session.id && authMode === 'browse') return <PublicBrowse
    onLogin={portal => { setLoginPortal(portal); setAuthMode('login'); setError('') }}
    onRegister={() => { setLoginPortal('PATIENT'); setAuthMode('register'); setError('') }}
    onBook={choice => { setPendingBooking(choice); setLoginPortal('PATIENT'); setAuthMode('login'); setError(''); setNotice('') }}
  />
  if (!session.id) return <main className="auth-layout">
    <section className="auth-hero"><div className="brand-mark"><img src={smartcareMark} alt="" aria-hidden="true"/></div><p className="eyebrow">SUNSHINE HEALTHCARE LANKA</p><h1>Care begins<br/>with a better<br/>appointment.</h1><p>Find a doctor, choose a time, and keep your visit organized in one place.</p><div className="hero-pills"><span>Simple booking</span><span>Clear queue updates</span><span>Private account</span></div><button className="btn btn-outline-light mt-4" onClick={() => setAuthMode('browse')}>Browse doctors without signing in</button></section>
    <section className="auth-card"><p className="eyebrow">SMARTCARE E-CHANNELING</p><h2>{authMode === 'register' ? 'Create patient account' : authMode === 'forgot' ? 'Reset your password' : authMode === 'reset' ? 'Set new password' : loginPortal === 'PATIENT' ? 'Patient sign in' : 'Staff & doctor sign in'}</h2>
      {authMode === 'login' && <div className="actions mb-3"><button className={'btn btn-sm ' + (loginPortal === 'PATIENT' ? 'btn-primary' : 'btn-outline-primary')} onClick={() => { setLoginPortal('PATIENT'); setError('') }}>Patient</button><button className={'btn btn-sm ' + (loginPortal === 'STAFF' ? 'btn-primary' : 'btn-outline-primary')} onClick={() => { setLoginPortal('STAFF'); setPendingBooking(null); setError('') }}>Staff & doctor</button></div>}
      <p className="text-secondary">{authMode === 'register' ? 'Register as a patient to book your visit.' : authMode === 'forgot' ? 'Enter your registered email address to receive a password reset code.' : authMode === 'reset' ? 'Enter the six-digit code sent to your email and set a new password.' : pendingBooking ? 'Sign in to finish booking your selected time.' : 'Sign in to continue to your dashboard.'}</p>
      {error && <div className="alert alert-danger">{error}</div>}{notice && <div className="alert alert-success">{notice}</div>}
      {authMode === 'reset' && <div className="mb-3"><p className="text-secondary mb-1">Resetting password for <strong>{form.email}</strong>. The OTP expires after 10 minutes.</p><button type="button" className="btn btn-link p-0" disabled={authBusy} onClick={() => { setAuthMode('forgot'); setError(''); setNotice(''); setForm(current => ({ ...current, rawCode: '', newPassword: '' })) }}>Change email or request a new OTP</button></div>}
      <fieldset disabled={authBusy} style={{ border: 0, padding: 0, margin: 0 }}>
      <form onSubmit={submit}>{authMode === 'register' && <>{field('fullName', 'Full name')}{field('nic', 'NIC')}{field('dateOfBirth', 'Date of birth', 'date')}{field('phone', 'Phone')}{field('address', 'Address')}<label className="form-check my-3"><input className="form-check-input" type="checkbox" checked={form.contactPreference === 'IN_APP_AND_TEXT'} onChange={e => setForm({ ...form, contactPreference: e.target.checked ? 'IN_APP_AND_TEXT' : 'IN_APP' })}/><span className="form-check-label">Create local text-file copies of my appointment alerts and reminders (demo only)</span></label></>}{['login', 'register', 'forgot'].includes(authMode) && field('email', 'Email', 'email')}{['login', 'register'].includes(authMode) && field('password', authMode === 'register' ? 'Create password (at least 8 characters)' : 'Password', 'password')}{authMode === 'reset' && <><label className="form-label w-100">Six-digit code<input className="form-control" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength="6" required value={form.rawCode} onChange={e => setForm({ ...form, rawCode: e.target.value.replace(/[^0-9]/g, '').slice(0, 6) })}/></label>{field('newPassword', 'New password', 'password')}</>}<button className="btn btn-primary w-100 mt-2">{authMode === 'register' ? 'Create account' : authMode === 'forgot' ? 'Request reset code' : authMode === 'reset' ? 'Reset password' : 'Sign in'}</button></form>
      </fieldset>
      {authBusy && <p role="status" className="text-secondary mt-2">Please wait…</p>}
      {authMode === 'login' && <div className="d-flex justify-content-between align-items-center mt-3"><button className="btn btn-link p-0 text-decoration-none" onClick={() => { setAuthMode('forgot'); setError(''); setNotice('') }}>Forgot password?</button><button className="btn btn-link p-0 text-decoration-none" onClick={() => { setAuthMode('register'); setLoginPortal('PATIENT'); setError(''); setNotice('') }}>New patient? Create account</button></div>}
      {authMode === 'register' && <button className="btn btn-link p-0 mt-3" onClick={() => { setAuthMode('login'); setError(''); setNotice('') }}>Already have an account? Patient sign in</button>}
      {['forgot', 'reset'].includes(authMode) && <button className="btn btn-link p-0 mt-3" onClick={() => { setAuthMode('login'); setError(''); setNotice('') }}>← Back to Sign in</button>}
    </section>
  </main>

  const role = session.role
  const tabs = [
    ['appointments', 'Appointments', ['PATIENT','DOCTOR','RECEPTION','ADMIN']],
    ['doctors', role === 'DOCTOR' ? 'My schedule' : 'Doctors', ['PATIENT','DOCTOR','RECEPTION','ADMIN']],
    ['patient', 'My profile', ['PATIENT']],
    ['queue', 'Queue', ['RECEPTION','ADMIN','MANAGER']],
    ['notifications', 'Alerts', ['PATIENT','DOCTOR']],
    ['reports', 'Reports', ['DOCTOR','RECEPTION','ADMIN','MANAGER']],
    ['feedback', 'Feedback', ['PATIENT','ADMIN']],
    ['admin', 'Admin panel', ['ADMIN']],
    ['account', 'My account', ['PATIENT','RECEPTION','MANAGER','ADMIN']]
  ].filter(tab => tab[2].includes(role))
  const selected = tabs.some(tab => tab[0] === page) ? page : tabs[0]?.[0]
  const views = { appointments: Appointment, doctors: Doctor, patient: Patient, queue: Queue, notifications: Notification, reports: Report, feedback: Feedback, admin: Admin, account: Account }
  const SelectedView = views[selected]
  const unreadAlerts = alerts.filter(item => !item.read_at).length
  const logout = async () => { try { await api.send('POST', '/auth/logout'); setSession({}); setAuthMode('browse'); setPage('appointments'); setPendingBooking(null) } catch (e) { fail(e) } }
  const accountChanged = () => { setSession({}); setAuthMode('login'); setPage('appointments'); flash('Account saved. Sign in with your new email.') }
  return <div className="app-shell"><aside className="sidebar"><div className="brand"><div className="brand-mark"><img src={smartcareMark} alt="" aria-hidden="true"/></div><div><strong>SmartCare</strong><small>E-Channeling</small></div></div><nav>{tabs.map(tab => <button key={tab[0]} className={'nav-item ' + (selected === tab[0] ? 'active' : '')} onClick={() => { setPage(tab[0]); setAlertsOpen(false); setError(''); setNotice(''); if (tab[0] === 'notifications') refreshAlerts() }}>{tab[1]}{tab[0] === 'notifications' && unreadAlerts > 0 && <span className="alert-count ms-2">{unreadAlerts > 99 ? '99+' : unreadAlerts}</span>}</button>)}</nav><div className="sidebar-foot"><span>{session.full_name}</span><small>{role.toLowerCase()}</small><button className="btn btn-outline-light btn-sm mt-3" onClick={logout}>Sign out</button></div></aside><main className="content"><header className="topbar"><span>Sunshine Healthcare Lanka Ltd</span><div className="topbar-actions">{['PATIENT','DOCTOR'].includes(role) && <div className="alert-menu"><button type="button" className="alert-bell" aria-label={'Alerts, '+unreadAlerts+' unread'} aria-expanded={alertsOpen} onClick={() => { setAlertsOpen(!alertsOpen); refreshAlerts() }}>🔔 <span>Alerts</span>{unreadAlerts > 0 && <span className="alert-count">{unreadAlerts > 99 ? '99+' : unreadAlerts}</span>}</button>{alertsOpen && <div className="alert-popover" role="dialog" aria-label="Recent alerts"><div className="alert-popover-head"><strong>Recent alerts</strong><button className="btn btn-sm btn-link" onClick={() => setAlertsOpen(false)}>Close</button></div>{alerts.length === 0 ? <p className="muted mb-2">No alerts yet.</p> : alerts.slice(0,4).map(item => <div key={item.id} className={'alert-popover-item '+(item.read_at ? '' : 'unread')}><strong>{alertCategory(item.type)}</strong><p>{item.message}</p><small>{new Date(item.created_at).toLocaleString()}</small></div>)}<button className="btn btn-sm btn-outline-primary w-100 mt-2" onClick={() => { setPage('notifications'); setAlertsOpen(false) }}>View all alerts</button></div>}</div>}<span className="role-badge">{role}</span></div></header>{error && <div className="alert alert-danger alert-dismissible" role="alert">{error}<button className="btn-close" onClick={() => setError('')}/></div>}{notice && <div className="alert alert-success alert-dismissible" role="status">{notice}<button className="btn-close" onClick={() => setNotice('')}/></div>}<SelectedView session={session} flash={flash} fail={fail} pendingBooking={pendingBooking} onPendingHandled={() => setPendingBooking(null)} onEmailChanged={accountChanged} onRefreshAlerts={refreshAlerts} initialAppointmentId={feedbackTarget} onTargetHandled={() => setFeedbackTarget(null)} onLeaveFeedback={id => { setFeedbackTarget(id); setPage('feedback') }}/></main></div>
}
