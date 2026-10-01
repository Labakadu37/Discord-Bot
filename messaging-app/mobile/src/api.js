import * as SecureStore from 'expo-secure-store';
import { io } from 'socket.io-client';

const CONFIG_KEY = 'smsapp.config';

export async function loadConfig() {
  const raw = await SecureStore.getItemAsync(CONFIG_KEY);
  return raw ? JSON.parse(raw) : null;
}

export async function saveConfig(config) {
  await SecureStore.setItemAsync(CONFIG_KEY, JSON.stringify(config));
}

export async function clearConfig() {
  await SecureStore.deleteItemAsync(CONFIG_KEY);
}

export class ApiError extends Error {
  constructor(message, status, data) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

export function createClient({ url, token }) {
  const base = url.trim().replace(/\/+$/, '');
  const headers = {
    Authorization: `Bearer ${token}`,
    'ngrok-skip-browser-warning': '1',
  };

  async function request(path, { method = 'GET', body } = {}) {
    let res;
    try {
      res = await fetch(`${base}/api${path}`, {
        method,
        headers: body ? { ...headers, 'Content-Type': 'application/json' } : headers,
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch {
      throw new ApiError('Impossible de joindre le serveur. Vérifie l’adresse et ta connexion.', 0);
    }
    if (res.status === 204) return null;
    const data = await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(data?.error || `Erreur ${res.status}`, res.status, data);
    return data;
  }

  const enc = encodeURIComponent;

  return {
    me: () => request('/me'),
    conversations: () => request('/conversations'),
    messages: (phone, before) => request(`/conversations/${enc(phone)}/messages${before ? `?before=${before}` : ''}`),
    markRead: (phone) => request(`/conversations/${enc(phone)}/read`, { method: 'POST' }),
    deleteConversation: (phone) => request(`/conversations/${enc(phone)}`, { method: 'DELETE' }),
    send: (to, body) => request('/messages', { method: 'POST', body: { to, body } }),
    connectSocket: () =>
      io(base, {
        transports: ['websocket'],
        auth: { token },
        extraHeaders: { 'ngrok-skip-browser-warning': '1' },
      }),
  };
}
