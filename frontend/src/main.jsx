import React from 'react'
import { createRoot } from 'react-dom/client'
import 'bootstrap/dist/css/bootstrap.min.css'
import './style.css'
import './brand.css'
import './appointment-groups.css'
import './watermark.css'
import './admin-management.css'
import App from './App.jsx'

createRoot(document.getElementById('root')).render(<App />)
