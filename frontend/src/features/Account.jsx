import React, { useEffect, useState } from 'react'
import { api } from '../api'
import { GMAIL_PATTERN, GMAIL_TITLE, sameEmail } from '../emailPolicy'

export default function Account({ flash, fail, onEmailChanged }) {
  const [details, setDetails] = useState(null)
  const [originalEmail, setOriginalEmail] = useState('')
  const [password, setPassword] = useState({ currentPassword: '', newPassword: '', repeatPassword: '' })
  useEffect(() => { api.get('/account').then(value => { setDetails(value); setOriginalEmail(value.email) }).catch(fail) }, [])
  const saveDetails = async e => {
    e.preventDefault()
    try {
      const result = await api.send('PUT', '/account', { fullName: details.full_name, email: details.email })
      if (result.emailChanged) { await api.send('POST', '/auth/logout'); onEmailChanged() }
      else flash('Account details saved')
    } catch (e) { fail(e) }
  }
  const savePassword = async e => {
    e.preventDefault()
    if (password.newPassword !== password.repeatPassword) { fail(new Error('New passwords do not match')); return }
    try {
      await api.send('PUT', '/account/password', { currentPassword: password.currentPassword, newPassword: password.newPassword })
      setPassword({ currentPassword: '', newPassword: '', repeatPassword: '' })
      flash('Password changed')
    } catch (e) { fail(e) }
  }
  if (!details) return <p>Loading account…</p>
  return <>
    <div className="page-heading"><div><p className="eyebrow">YOUR ACCOUNT</p><h1>Account details</h1><p>Update your login details and password.</p></div></div>
    <div className="grid-two">
      <section className="panel"><h3>Personal details</h3><form onSubmit={saveDetails}><label className="form-label w-100">Full name<input className="form-control" required value={details.full_name} onChange={e => setDetails({ ...details, full_name: e.target.value })}/></label><label className="form-label w-100">Login email<input className="form-control" type="email" pattern={sameEmail(details.email, originalEmail) ? undefined : GMAIL_PATTERN} title={sameEmail(details.email, originalEmail) ? undefined : GMAIL_TITLE} required value={details.email} onChange={e => setDetails({ ...details, email: e.target.value })}/></label><p className="muted">If you change your email, use a Gmail address and sign in again.</p><button className="btn btn-primary">Save details</button></form></section>
      <section className="panel"><h3>Change password</h3><form onSubmit={savePassword}><label className="form-label w-100">Current password<input className="form-control" type="password" required value={password.currentPassword} onChange={e => setPassword({ ...password, currentPassword: e.target.value })}/></label><label className="form-label w-100">New password<input className="form-control" type="password" required minLength="8" value={password.newPassword} onChange={e => setPassword({ ...password, newPassword: e.target.value })}/></label><label className="form-label w-100">Repeat new password<input className="form-control" type="password" required minLength="8" value={password.repeatPassword} onChange={e => setPassword({ ...password, repeatPassword: e.target.value })}/></label><button className="btn btn-primary">Change password</button></form></section>
    </div>
  </>
}
