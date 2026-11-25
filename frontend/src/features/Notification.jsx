import React, { useEffect, useState } from 'react'
import { api } from '../api'
import './Notification.css'
import './NotificationReminders.css'

export const alertCategory = type => ['BOOKED','CONFIRMED'].includes(type) ? 'Booking confirmations' : type === 'REMINDER' ? 'Upcoming appointments' : ['RESCHEDULED', 'SCHEDULE_CHANGED'].includes(type) ? 'Schedule changes' : type === 'CANCELLED' ? 'Cancellations' : 'Queue updates'
export const alertIcon = type => ['BOOKED','CONFIRMED'].includes(type) ? '✓' : type === 'REMINDER' ? '◷' : ['RESCHEDULED', 'SCHEDULE_CHANGED'].includes(type) ? '↻' : type === 'CANCELLED' ? '×' : '•'

export default function Notification({ fail, onRefreshAlerts }) {
  const [messages, setMessages] = useState([])
  const [scheduled, setScheduled] = useState([])
  const [filter, setFilter] = useState('All')
  const [busy, setBusy] = useState(null)
  const load = async () => { try { const [items, upcoming] = await Promise.all([api.get('/notifications'), api.get('/notifications/upcoming')]); setMessages(items); setScheduled(upcoming); onRefreshAlerts?.() } catch (e) { fail(e) } }
  useEffect(() => { load() }, [])
  const read = async id => { try { setBusy(id); await api.send('POST', '/notifications/' + id + '/read'); await load() } catch (e) { fail(e) } finally { setBusy(null) } }
  const categories = ['All', 'Booking confirmations', 'Upcoming appointments', 'Schedule changes', 'Cancellations', 'Queue updates']
  const shown = filter === 'All' ? messages : messages.filter(m => alertCategory(m.type) === filter)
  const unread = messages.filter(m => !m.read_at).length
  return <>
    <div className="page-heading"><div><p className="eyebrow">NOTIFICATIONS & ALERTS</p><h1>Alerts & reminders</h1><p>Booking confirmations, appointment reminders, schedule changes, and cancellations.</p></div><button className="btn btn-outline-primary" onClick={load}>Refresh</button></div>
    {(filter === 'All' || filter === 'Upcoming appointments') && scheduled.length > 0 && <section className="panel scheduled-reminders"><div className="section-head"><div><p className="eyebrow">COMING UP</p><h3>Scheduled reminders</h3></div><span className="muted">These alerts will appear in your inbox when due.</span></div>{scheduled.map(item => <div className="scheduled-reminder" key={item.appointment_id}><span className="alert-icon type-reminder" aria-hidden="true">◷</span><div><strong>Appointment #{item.appointment_id}</strong><p>{item.message}</p><small>Next reminder: {new Date(item.due_at).toLocaleString()}</small></div></div>)}</section>}
    <section className="panel"><div className="alert-summary"><strong>{unread} unread</strong><span>{messages.length} total alerts</span></div><div className="alert-filters" role="group" aria-label="Filter alerts">{categories.map(category => <button key={category} type="button" className={'alert-filter '+(filter === category ? 'selected' : '')} onClick={() => setFilter(category)}>{category}</button>)}</div>
      {shown.length === 0 ? <p className="muted alert-empty">No {filter === 'All' ? 'alerts yet' : filter.toLowerCase() + ' alerts'}.</p> : <div className="alert-list">{shown.map(m => <article className={'alert-item '+(m.read_at ? 'read' : 'unread')} key={m.id}><span className={'alert-icon type-'+m.type.toLowerCase()} aria-hidden="true">{alertIcon(m.type)}</span><div className="alert-item-body"><div className="alert-item-head"><strong>{alertCategory(m.type)}</strong>{!m.read_at && <span className="alert-new">New</span>}</div><p>{m.message}</p><small>{new Date(m.created_at).toLocaleString()}</small></div>{!m.read_at && <button className="btn btn-sm btn-outline-primary" disabled={busy === m.id} onClick={() => read(m.id)}>Mark read</button>}</article>)}</div>}
    </section>
  </>
}
