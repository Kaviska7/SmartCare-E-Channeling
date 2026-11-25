import React, { useEffect, useState } from 'react'
import { api } from '../api'
import { GMAIL_PATTERN, GMAIL_TITLE, sameEmail } from '../emailPolicy'

export default function Patient({ flash, fail, onEmailChanged }) {
  const [profile, setProfile] = useState(null)
  const [originalEmail, setOriginalEmail] = useState('')
  const [contacts, setContacts] = useState([])
  const [contact, setContact] = useState({ fullName: '', relationship: '', phone: '' })
  const load = () => Promise.all([api.get('/patient/profile'), api.get('/patient/contacts')]).then(([p,c]) => { setProfile(p); setOriginalEmail(p.email); setContacts(c) }).catch(fail)
  useEffect(() => { load() }, [])
  const save = async e => { e.preventDefault(); try { const result = await api.send('PUT','/patient/profile',{ fullName: profile.full_name, email: profile.email, nic: profile.nic, phone: profile.phone, address: profile.address, contactPreference: profile.contact_preference }); if (result.emailChanged) { await api.send('POST','/auth/logout'); onEmailChanged() } else { flash('Profile saved'); load() } } catch(e) { fail(e) } }
  const add = async e => { e.preventDefault(); try { await api.send('POST','/patient/contacts',contact); setContact({ fullName:'',relationship:'',phone:'' }); flash('Emergency contact added'); load() } catch(e) { fail(e) } }
  const remove = async id => { if (!confirm('Remove this contact?')) return; try { await api.send('DELETE','/patient/contacts/'+id); flash('Emergency contact removed'); load() } catch(e) { fail(e) } }
  if (!profile) return <p>Loading profile…</p>
  return <>
    <div className="page-heading"><div><p className="eyebrow">PATIENT MANAGEMENT</p><h1>My profile</h1><p>Keep your contact and emergency details up to date.</p></div></div>
    <div className="grid-two">
      <section className="panel"><h3>Personal details</h3><form onSubmit={save}>
        <label className="form-label w-100">Full name<input className="form-control" value={profile.full_name||''} onChange={e=>setProfile({...profile,full_name:e.target.value})} required/></label>
        <label className="form-label w-100">Email<input className="form-control" type="email" pattern={sameEmail(profile.email, originalEmail) ? undefined : GMAIL_PATTERN} title={sameEmail(profile.email, originalEmail) ? undefined : GMAIL_TITLE} value={profile.email||''} onChange={e=>setProfile({...profile,email:e.target.value})} required/></label>
        <label className="form-label w-100">NIC (12 digits)<input className="form-control" inputMode="numeric" pattern="[0-9]{12}" title="Enter exactly 12 digits" maxLength={12} value={profile.nic||''} onChange={e=>setProfile({...profile,nic:e.target.value})} required/></label>
        <label className="form-label w-100">Phone (10 digits)<input className="form-control" type="tel" inputMode="numeric" pattern="[0-9]{10}" title="Enter exactly 10 digits" maxLength={10} value={profile.phone||''} onChange={e=>setProfile({...profile,phone:e.target.value})} required/></label>
        <label className="form-label w-100">Address<input className="form-control" value={profile.address||''} onChange={e=>setProfile({...profile,address:e.target.value})}/></label>
        <label className="form-check my-3"><input className="form-check-input" type="checkbox" checked={profile.contact_preference === 'IN_APP_AND_TEXT'} onChange={e=>setProfile({...profile,contact_preference:e.target.checked ? 'IN_APP_AND_TEXT' : 'IN_APP'})}/><span className="form-check-label">Create local text-file copies of my appointment alerts and reminders (demo only)</span></label>
        <p className="muted">If you change your email, use a Gmail address and sign in again.</p><button className="btn btn-primary">Save profile</button>
      </form></section>
      <section className="panel"><h3>Emergency contacts</h3>{contacts.length===0 && <p className="muted">No contacts added yet.</p>}{contacts.map(c=><div className="list-row" key={c.id}><div><strong>{c.full_name}</strong><small>{c.relationship} · {c.phone}</small></div><button className="btn btn-sm btn-outline-danger" onClick={()=>remove(c.id)}>Remove</button></div>)}<hr/><h4>Add a contact</h4><form onSubmit={add}>{[['fullName','Full name'],['relationship','Relationship'],['phone','Phone']].map(([name,label])=><label className="form-label w-100" key={name}>{label}<input className="form-control" required={true} type={name === 'phone' ? 'tel' : 'text'} inputMode={name === 'phone' ? 'numeric' : undefined} pattern={name === 'phone' ? '[0-9]{10}' : undefined} title={name === 'phone' ? 'Enter exactly 10 digits' : undefined} maxLength={name === 'phone' ? 10 : undefined} value={contact[name]} onChange={e=>setContact({...contact,[name]:e.target.value})}/></label>)}<button className="btn btn-primary">Add contact</button></form></section>
    </div>
  </>
}
