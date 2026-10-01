import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { clearConfig, createClient, loadConfig, saveConfig } from './api';

const AppContext = createContext(null);

export function AppProvider({ children }) {
  const [config, setConfig] = useState(undefined); // undefined = chargement, null = pas configuré
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    loadConfig().then(setConfig).catch(() => setConfig(null));
  }, []);

  const client = useMemo(() => (config ? createClient(config) : null), [config]);

  const socket = useMemo(() => client?.connectSocket() ?? null, [client]);

  useEffect(() => {
    if (!socket) return undefined;
    const onConnect = () => setConnected(true);
    const onDisconnect = () => setConnected(false);
    socket.on('connect', onConnect);
    socket.on('disconnect', onDisconnect);
    socket.on('connect_error', onDisconnect);
    return () => {
      socket.disconnect();
      setConnected(false);
    };
  }, [socket]);

  const value = useMemo(
    () => ({
      config,
      client,
      socket,
      connected,
      async login(newConfig) {
        // Vérifie que le serveur répond et que le token est bon avant d'enregistrer
        const me = await createClient(newConfig).me();
        await saveConfig({ ...newConfig, phoneNumber: me.phoneNumber });
        setConfig({ ...newConfig, phoneNumber: me.phoneNumber });
      },
      async logout() {
        await clearConfig();
        setConfig(null);
      },
    }),
    [config, client, socket, connected],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp() {
  return useContext(AppContext);
}

/** Abonne un composant à un événement temps réel du serveur ("message" ou "status"). */
export function useSocketEvent(event, handler) {
  const { socket } = useApp();
  useEffect(() => {
    if (!socket) return undefined;
    socket.on(event, handler);
    return () => socket.off(event, handler);
  }, [socket, event, handler]);
}
