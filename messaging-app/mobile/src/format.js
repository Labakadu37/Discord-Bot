/** "+33612345678" -> "+33 6 12 34 56 78" (format lisible pour les numéros français, sinon inchangé). */
export function formatPhone(phone) {
  const fr = /^\+33(\d)(\d{2})(\d{2})(\d{2})(\d{2})$/.exec(phone);
  return fr ? `+33 ${fr.slice(1).join(' ')}` : phone;
}

export function formatTime(iso) {
  const date = new Date(iso);
  const now = new Date();
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }
  return date.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' });
}

export const STATUS_LABELS = {
  sending: 'Envoi…',
  accepted: 'Envoi…',
  queued: 'En attente',
  sent: 'Envoyé',
  delivered: 'Distribué',
  undelivered: 'Non distribué',
  failed: 'Échec',
};

export const colors = {
  primary: '#2563eb',
  bubbleOut: '#2563eb',
  bubbleIn: '#e5e7eb',
  text: '#111827',
  muted: '#6b7280',
  danger: '#dc2626',
  border: '#e5e7eb',
  background: '#ffffff',
};
