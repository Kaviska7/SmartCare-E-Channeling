import React, { useEffect, useState } from 'react'
import { api } from '../api'
import { GMAIL_PATTERN, GMAIL_TITLE, sameEmail } from '../emailPolicy'

const roleTabs = [
  { role: 'ADMIN', label: 'Administrators', singular: 'administrator' },
  { role: 'MANAGER', label: 'Managers', singular: 'manager' },
  { role: 'RECEPTION', label: 'Receptionists', singular: 'receptionist' },
  { role: 'PATIENT', label: 'Patients', singular: 'patient' }
]
const blankStaff = () => ({ fullName: '', email: '', password: '' })
const blankPatient = { fullName: '', email: '', password: '', nic: '', dateOfBirth: '', phone: '', address: '' }

export default function Admin({ flash, fail, onEmailChanged }) {
  const [activeRole, setActiveRole] = useState('ADMIN')
  const [users, setUsers] = useState([])
  const [patients, setPatients] = useState([])
  const [staffForms, setStaffForms] = useState({ ADMIN: blankStaff(), MANAGER: blankStaff(), RECEPTION: blankStaff() })
  const [patient, setPatient] = useState(blankPatient)
  const [editing, setEditing] = useState(null)
  const [editForm, setEditForm] = useState(null)

  const loadUsers = () => api.get('/admin/users').then(setUsers).catch(fail)
  const loadPatients = () => api.get('/admin/patients').then(setPatients).catch(fail)

  useEffect(() => { loadUsers(); loadPatients() }, [])

  const selectRole = role => {
    setActiveRole(role)
    setEditing(null)
    setEditForm(null)
  }

  const createStaff = async e => {
    e.preventDefault()
    const role = activeRole
    try {
      await api.send('POST', '/admin/users', { ...staffForms[role], role })
      const label = roleTabs.find(tab => tab.role === role).singular
      flash(`${label.charAt(0).toUpperCase() + label.slice(1)} account created`)
      setStaffForms(current => ({ ...current, [role]: blankStaff() }))
      loadUsers()
    } catch (error) { fail(error) }
  }

  const createPatient = async e => {
    e.preventDefault()
    try {
      await api.send('POST', '/admin/patients', patient)
      flash('Patient account created')
      setPatient(blankPatient)
      loadPatients()
    } catch (error) { fail(error) }
  }

  const updateStaffField = (name, value) => setStaffForms(current => ({
    ...current,
    [activeRole]: { ...current[activeRole], [name]: value }
  }))

  const beginStaffEdit = user => {
    setEditing({ type: 'staff', id: user.id, wasActive: Boolean(user.active), originalEmail: user.email })
    setEditForm({ fullName: user.full_name, email: user.email, active: Boolean(user.active), password: '' })
  }

  const beginPatientEdit = item => {
    setEditing({ type: 'patient', id: item.id, wasActive: Boolean(item.active), originalEmail: item.email })
    setEditForm({
      fullName: item.full_name,
      email: item.email,
      nic: item.nic,
      dateOfBirth: String(item.date_of_birth).slice(0, 10),
      phone: item.phone,
      address: item.address || '',
      active: Boolean(item.active),
      password: ''
    })
  }

  const saveEdit = async e => {
    e.preventDefault()
    if (editing.wasActive && !editForm.active && !window.confirm('Deactivate this account? The user will no longer be able to sign in.')) return
    try {
      const path = editing.type === 'patient' ? `/admin/patients/${editing.id}` : `/admin/users/${editing.id}`
      const result = await api.send('PUT', path, { ...editForm, password: editForm.password || null })
      flash(result.message || 'Account updated')
      if (result.emailChanged) {
        await api.send('POST', '/auth/logout')
        onEmailChanged?.()
        return
      }
      setEditing(null)
      setEditForm(null)
      if (path.includes('/patients/')) loadPatients()
      else loadUsers()
    } catch (error) { fail(error) }
  }

  const deactivate = async (type, item) => {
    if (!window.confirm(`Deactivate ${item.full_name}? They will no longer be able to sign in.`)) return
    try {
      const path = type === 'patient' ? `/admin/patients/${item.id}` : `/admin/users/${item.id}`
      const result = await api.send('DELETE', path)
      flash(result.message || 'Account deactivated')
      if (editing?.type === type && editing.id === item.id) {
        setEditing(null)
        setEditForm(null)
      }
      if (type === 'patient') loadPatients()
      else loadUsers()
    } catch (error) { fail(error) }
  }

  const permanentlyDelete = async (type, item) => {
    const details = type === 'patient' ? ' Reviews and notifications will be removed; audit and moderation history will retain anonymous references. Appointment history still prevents deletion.' : ''
    if (!window.confirm(`Permanently delete ${item.full_name}? This cannot be undone.${details}`)) return
    const typedEmail = window.prompt(`Type ${item.email} to confirm permanent deletion.`)
    if (typedEmail === null) return
    if (typedEmail.trim().toLowerCase() !== item.email.trim().toLowerCase()) {
      fail(new Error('Email confirmation did not match. The account was not deleted.'))
      return
    }
    try {
      const path = type === 'patient' ? `/admin/patients/${item.id}/permanent` : `/admin/users/${item.id}/permanent`
      const result = await api.send('DELETE', path)
      flash(result.message || 'Account permanently deleted')
      if (editing?.type === type && editing.id === item.id) {
        setEditing(null)
        setEditForm(null)
      }
      if (type === 'patient') loadPatients()
      else loadUsers()
    } catch (error) { fail(error) }
  }

  const editField = (name, value) => setEditForm(current => ({ ...current, [name]: value }))

  const editPanel = type => <form className="admin-account-edit form-grid" onSubmit={saveEdit}>
    <label>Full name<input className="form-control" required value={editForm.fullName} onChange={e => editField('fullName', e.target.value)}/></label>
    <label>Email<input className="form-control" type="email" pattern={sameEmail(editForm.email, editing.originalEmail) ? undefined : GMAIL_PATTERN} title={sameEmail(editForm.email, editing.originalEmail) ? undefined : GMAIL_TITLE} required value={editForm.email} onChange={e => editField('email', e.target.value)}/></label>
    {type === 'patient' && <>
      <label>NIC (12 digits)<input className="form-control" inputMode="numeric" pattern="[0-9]{12}" maxLength="12" required value={editForm.nic} onChange={e => editField('nic', e.target.value)}/></label>
      <label>Date of birth<input className="form-control" type="date" required value={editForm.dateOfBirth} onChange={e => editField('dateOfBirth', e.target.value)}/></label>
      <label>Phone (10 digits)<input className="form-control" type="tel" inputMode="numeric" pattern="[0-9]{10}" maxLength="10" required value={editForm.phone} onChange={e => editField('phone', e.target.value)}/></label>
      <label>Address<input className="form-control" value={editForm.address} onChange={e => editField('address', e.target.value)}/></label>
    </>}
    <label>New password (optional)<input className="form-control" type="password" minLength="8" value={editForm.password} onChange={e => editField('password', e.target.value)} placeholder="Leave blank to keep current password"/></label>
    <label className="check admin-active-check"><input type="checkbox" checked={editForm.active} onChange={e => editField('active', e.target.checked)}/> Account active</label>
    <div className="actions admin-edit-actions">
      <button className="btn btn-primary">Save changes</button>
      <button className="btn btn-outline" type="button" onClick={() => { setEditing(null); setEditForm(null) }}>Cancel</button>
    </div>
  </form>

  const currentTab = roleTabs.find(tab => tab.role === activeRole)
  const currentStaff = activeRole === 'PATIENT' ? [] : users.filter(user => user.role === activeRole)
  const currentCount = activeRole === 'PATIENT' ? patients.length : currentStaff.length

  return <>
    <div className="page-heading"><div><p className="eyebrow">ADMINISTRATION</p><h1>Admin panel</h1><p>Create and manage hospital accounts by role.</p></div></div>
    <div className="admin-role-tabs" role="tablist" aria-label="Account roles">
      {roleTabs.map(tab => {
        const count = tab.role === 'PATIENT' ? patients.length : users.filter(user => user.role === tab.role).length
        return <button
          className={'admin-role-tab ' + (activeRole === tab.role ? 'selected' : '')}
          id={`admin-tab-${tab.role.toLowerCase()}`}
          key={tab.role}
          type="button"
          role="tab"
          aria-selected={activeRole === tab.role}
          aria-controls={`admin-panel-${tab.role.toLowerCase()}`}
          onClick={() => selectRole(tab.role)}
        >{tab.label}<span>{count}</span></button>
      })}
    </div>

    <section className="panel admin-role-panel" id={`admin-panel-${activeRole.toLowerCase()}`} role="tabpanel" aria-labelledby={`admin-tab-${activeRole.toLowerCase()}`}>
      <div className="section-head"><div><p className="eyebrow">{activeRole}</p><h3>{currentTab.label} ({currentCount})</h3></div></div>

      {activeRole === 'PATIENT' ? <>
        <h4>Register a patient</h4>
        <form className="form-grid" onSubmit={createPatient}>
          {[['fullName', 'Full name', 'text'], ['email', 'Email', 'email'], ['password', 'Initial password', 'password'], ['nic', 'NIC (12 digits)', 'text'], ['dateOfBirth', 'Date of birth', 'date'], ['phone', 'Phone (10 digits)', 'tel'], ['address', 'Address', 'text']].map(([name, label, type]) => <label key={name}>{label}<input className="form-control" type={type} inputMode={['nic','phone'].includes(name) ? 'numeric' : undefined} pattern={name === 'email' ? GMAIL_PATTERN : name === 'nic' ? '[0-9]{12}' : name === 'phone' ? '[0-9]{10}' : undefined} title={name === 'email' ? GMAIL_TITLE : name === 'nic' ? 'Enter exactly 12 digits' : name === 'phone' ? 'Enter exactly 10 digits' : undefined} maxLength={name === 'nic' ? 12 : name === 'phone' ? 10 : undefined} required={name !== 'address'} minLength={name === 'password' ? 8 : undefined} value={patient[name]} onChange={e => setPatient({ ...patient, [name]: e.target.value })}/></label>)}
          <button className="btn btn-primary align-self-end">Create patient account</button>
        </form>
        <div className="admin-account-list"><h4>Patient accounts</h4>
          {patients.length === 0 ? <p className="muted">No patients yet.</p> : patients.map(item => <div className="admin-account-entry" key={item.id}>
            <div className="list-row"><div><strong>{item.full_name}</strong><small>{item.email}</small><small>NIC {item.nic} · Born {String(item.date_of_birth).slice(0, 10)} · {item.phone}</small>{item.address && <small>{item.address}</small>}</div><div className="actions"><span className={'role-badge ' + (!item.active ? 'account-inactive' : '')}>{item.active ? 'ACTIVE' : 'INACTIVE'}</span><button className="btn btn-outline" type="button" onClick={() => beginPatientEdit(item)}>Edit</button>{item.active ? <button className="btn btn-danger" type="button" onClick={() => deactivate('patient', item)}>Deactivate</button> : <button className="btn btn-outline-danger" type="button" onClick={() => permanentlyDelete('patient', item)}>Permanently delete</button>}</div></div>
            {editing?.type === 'patient' && editing.id === item.id && editPanel('patient')}
          </div>)}
        </div>
      </> : <>
        <h4>Create {currentTab.singular} account</h4>
        <form className="form-grid" onSubmit={createStaff}>
          <label>Full name<input className="form-control" required value={staffForms[activeRole].fullName} onChange={e => updateStaffField('fullName', e.target.value)}/></label>
          <label>Email<input className="form-control" type="email" pattern={GMAIL_PATTERN} title={GMAIL_TITLE} required value={staffForms[activeRole].email} onChange={e => updateStaffField('email', e.target.value)}/></label>
          <label>Initial password<input className="form-control" type="password" minLength="8" required value={staffForms[activeRole].password} onChange={e => updateStaffField('password', e.target.value)}/></label>
          <button className="btn btn-primary align-self-end">Create {currentTab.singular} account</button>
        </form>
        <small className="muted">Staff can change their own password after signing in.</small>
        <div className="admin-account-list"><h4>{currentTab.label}</h4>
          {currentStaff.length === 0 ? <p className="muted">No {currentTab.label.toLowerCase()} yet.</p> : currentStaff.map(user => <div className="admin-account-entry" key={user.id}>
            <div className="list-row"><div><strong>{user.full_name}</strong><small>{user.email}</small></div><div className="actions"><span className={'role-badge ' + (!user.active ? 'account-inactive' : '')}>{user.active ? 'ACTIVE' : 'INACTIVE'}</span><button className="btn btn-outline" type="button" onClick={() => beginStaffEdit(user)}>Edit</button>{user.active ? <button className="btn btn-danger" type="button" onClick={() => deactivate('staff', user)}>Deactivate</button> : <button className="btn btn-outline-danger" type="button" onClick={() => permanentlyDelete('staff', user)}>Permanently delete</button>}</div></div>
            {editing?.type === 'staff' && editing.id === user.id && editPanel('staff')}
          </div>)}
        </div>
      </>}
    </section>
  </>
}
