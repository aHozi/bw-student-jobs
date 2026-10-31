import { createContext, useContext, useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { api, auth, User } from './api';
import Login from './pages/Login';
import Jobs from './pages/Jobs';
import JobDetailPage from './pages/JobDetail';
import Documents from './pages/Documents';
import Applications from './pages/Applications';
import ApplicationReview from './pages/ApplicationReview';
import Companies from './pages/Companies';
import CompanyDetailPage from './pages/CompanyDetail';
import Settings from './pages/Settings';
import Admin from './pages/Admin';

type Ctx = { user: User | null; setUser: (u: User | null) => void };
const UserContext = createContext<Ctx>({ user: null, setUser: () => {} });
export const useUser = () => useContext(UserContext);

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(!!auth.token);
  const nav = useNavigate();

  useEffect(() => {
    if (!auth.token) return;
    api.get<User>('/me').then(setUser).catch(() => auth.set(null)).finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="page muted">Loading…</div>;

  const logout = () => { auth.set(null); setUser(null); nav('/login'); };

  return (
    <UserContext.Provider value={{ user, setUser }}>
      {user && (
        <nav className="nav">
          <NavLink to="/" className="brand">BW Student Jobs</NavLink>
          <NavLink to="/" end>Jobs</NavLink>
          <NavLink to="/applications">Applications</NavLink>
          <NavLink to="/documents">Documents</NavLink>
          <NavLink to="/companies">Companies</NavLink>
          <NavLink to="/settings">Settings</NavLink>
          {user.role === 'ADMIN' && <NavLink to="/admin">Admin</NavLink>}
          <span className="spacer" />
          <span className="muted small">{user.email}</span>
          <button onClick={logout}>Log out</button>
        </nav>
      )}
      <Routes>
        <Route path="/login" element={user ? <Navigate to="/" /> : <Login />} />
        {!user && <Route path="*" element={<Navigate to="/login" />} />}
        {user && (
          <>
            <Route path="/" element={<Jobs />} />
            <Route path="/jobs/:id" element={<JobDetailPage />} />
            <Route path="/documents" element={<Documents />} />
            <Route path="/applications" element={<Applications />} />
            <Route path="/applications/:id" element={<ApplicationReview />} />
            <Route path="/companies" element={<Companies />} />
            <Route path="/companies/:id" element={<CompanyDetailPage />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="/admin" element={<Admin />} />
            <Route path="*" element={<Navigate to="/" />} />
          </>
        )}
      </Routes>
    </UserContext.Provider>
  );
}
