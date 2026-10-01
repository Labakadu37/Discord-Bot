import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../format';

const MAX_LENGTH = 1600;

/** Champ de saisie + bouton d'envoi. `onSend` reçoit le texte et doit renvoyer true si l'envoi a réussi. */
export default function Composer({ onSend, disabled }) {
  const insets = useSafeAreaInsets();
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);

  const canSend = text.trim().length > 0 && !sending && !disabled;
  // Un SMS = 160 caractères (70 avec emojis/accents spéciaux) : on indique le nombre de segments au-delà
  const segments = text.length > 160 ? Math.ceil(text.length / 153) : 1;

  async function send() {
    if (!canSend) return;
    setSending(true);
    const ok = await onSend(text.trim());
    setSending(false);
    if (ok) setText('');
  }

  return (
    <View style={[styles.container, { paddingBottom: Math.max(insets.bottom, 8) }]}>
      <View style={styles.inputWrap}>
        <TextInput
          style={styles.input}
          value={text}
          onChangeText={setText}
          placeholder="SMS"
          multiline
          maxLength={MAX_LENGTH}
        />
        {segments > 1 && <Text style={styles.counter}>{segments} SMS</Text>}
      </View>
      <Pressable style={[styles.send, !canSend && styles.sendDisabled]} onPress={send} disabled={!canSend}>
        {sending ? <ActivityIndicator color="#fff" /> : <Text style={styles.sendText}>➤</Text>}
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    paddingHorizontal: 8,
    paddingTop: 8,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
    backgroundColor: colors.background,
  },
  inputWrap: {
    flex: 1,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 20,
    paddingHorizontal: 14,
    paddingVertical: 8,
    marginRight: 8,
  },
  input: { fontSize: 16, maxHeight: 120, padding: 0 },
  counter: { fontSize: 11, color: colors.muted, textAlign: 'right' },
  send: {
    width: 42,
    height: 42,
    borderRadius: 21,
    backgroundColor: colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sendDisabled: { opacity: 0.4 },
  sendText: { color: '#fff', fontSize: 18 },
});
