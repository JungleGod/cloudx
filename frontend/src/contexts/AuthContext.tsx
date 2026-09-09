import { createContext, useContext, useState, useEffect, type ReactNode } from 'react';
import { getMe, type UserInfo } from '../api/auth';

interface AuthState {
  token: string | null;
  username: string | null;
  userId: number | null;
  role: string | null;
  loading: boolean;
  login: (token: string, username: string, role: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState>({
  token: null,
  username: null,
  userId: null,
  role: null,
  loading: true,
  login: () => {},
  logout: () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(localStorage.getItem('token'));
  const [username, setUsername] = useState<string | null>(localStorage.getItem('username'));
  const [role, setRole] = useState<string | null>(localStorage.getItem('role'));
  const [userId, setUserId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (token) {
      getMe()
        .then((res) => {
          setUserId(res.data.userId);
          setUsername(res.data.username);
          setRole(res.data.role || 'user');
          localStorage.setItem('role', res.data.role || 'user');
        })
        .catch(() => {
          // token 失效
          localStorage.removeItem('token');
          localStorage.removeItem('username');
          setToken(null);
          setUsername(null);
        })
        .finally(() => setLoading(false));
    } else {
      setLoading(false);
    }
  }, []);

  const login = (newToken: string, newUsername: string, newRole: string) => {
    localStorage.setItem('token', newToken);
    localStorage.setItem('username', newUsername);
    localStorage.setItem('role', newRole);
    setToken(newToken);
    setUsername(newUsername);
    setRole(newRole);
  };

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('role');
    setToken(null);
    setUsername(null);
    setRole(null);
    setUserId(null);
  };

  return (
    <AuthContext.Provider value={{ token, username, userId, role, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}