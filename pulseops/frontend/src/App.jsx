import { Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout.jsx'
import { DashboardPage } from './pages/DashboardPage.jsx'
import { IncidentDetailPage } from './pages/IncidentDetailPage.jsx'
import { NotFoundPage } from './pages/NotFoundPage.jsx'

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/incidents/:incidentId" element={<IncidentDetailPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default App
