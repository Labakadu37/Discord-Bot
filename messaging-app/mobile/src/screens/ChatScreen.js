import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Alert, FlatList, KeyboardAvoidingView, Platform, StyleSheet, Text, View } from 'react-native';
import { useHeaderHeight } from '@react-navigation/elements';
import { useApp, useSocketEvent } from '../AppContext';
import Composer from '../components/Composer';
import { STATUS_LABELS, colors, formatPhone, formatTime } from '../format';

function upsert(list, message) {
  const index = list.findIndex((m) => m.id === message.id);
  if (index === -1) return [...list, message].sort((a, b) => a.id - b.id);
  const copy = list.slice();
  copy[index] = message;
  return copy;
}

export default function ChatScreen({ navigation, route }) {
  const { phone } = route.params;
  const { client } = useApp();
  const headerHeight = useHeaderHeight();
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(true);
  const [hasMore, setHasMore] = useState(true);

  useLayoutEffect(() => {
    navigation.setOptions({ title: formatPhone(phone) });
  }, [navigation, phone]);

  useEffect(() => {
    let cancelled = false;
    client
      .messages(phone)
      .then((list) => {
        if (cancelled) return;
        setMessages(list);
        setHasMore(list.length >= 50);
        client.markRead(phone).catch(() => {});
      })
      .catch((err) => Alert.alert('Erreur', err.message))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [client, phone]);

  async function loadOlder() {
    if (!hasMore || loading || messages.length === 0) return;
    const older = await client.messages(phone, messages[0].id).catch(() => []);
    setHasMore(older.length >= 50);
    setMessages((list) => older.reduce(upsert, list));
  }

  const onMessage = useCallback(
    (message) => {
      if (message.contact !== phone) return;
      setMessages((list) => upsert(list, message));
      if (message.direction === 'in') client.markRead(phone).catch(() => {});
    },
    [client, phone],
  );
  useSocketEvent('message', onMessage);
  useSocketEvent('status', onMessage);

  async function send(text) {
    try {
      const message = await client.send(phone, text);
      setMessages((list) => upsert(list, message));
      return true;
    } catch (err) {
      if (err.data?.message) setMessages((list) => upsert(list, err.data.message));
      Alert.alert('SMS non envoyé', err.message);
      return Boolean(err.data?.message); // le message échoué est affiché, on vide le champ
    }
  }

  // FlatList inversée : le plus récent en bas
  const data = useMemo(() => messages.slice().reverse(), [messages]);

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={headerHeight}
    >
      {loading ? (
        <ActivityIndicator style={{ flex: 1 }} />
      ) : (
        <FlatList
          inverted
          data={data}
          keyExtractor={(m) => String(m.id)}
          contentContainerStyle={styles.list}
          onEndReached={loadOlder}
          onEndReachedThreshold={0.3}
          renderItem={({ item }) => <Bubble message={item} />}
        />
      )}
      <Composer onSend={send} disabled={loading} />
    </KeyboardAvoidingView>
  );
}

function Bubble({ message }) {
  const out = message.direction === 'out';
  const failed = message.status === 'failed' || message.status === 'undelivered';
  return (
    <View style={[styles.bubbleRow, out ? styles.right : styles.left]}>
      <View style={[styles.bubble, out ? styles.bubbleOut : styles.bubbleIn, failed && styles.bubbleFailed]}>
        <Text style={[styles.body, out && { color: '#fff' }]}>{message.body}</Text>
      </View>
      <Text style={[styles.meta, failed && { color: colors.danger }]}>
        {formatTime(message.created_at)}
        {out && ` · ${STATUS_LABELS[message.status] ?? message.status}`}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  list: { padding: 12 },
  bubbleRow: { marginVertical: 3, maxWidth: '80%' },
  left: { alignSelf: 'flex-start' },
  right: { alignSelf: 'flex-end', alignItems: 'flex-end' },
  bubble: { borderRadius: 18, paddingHorizontal: 14, paddingVertical: 9 },
  bubbleIn: { backgroundColor: colors.bubbleIn, borderBottomLeftRadius: 4 },
  bubbleOut: { backgroundColor: colors.bubbleOut, borderBottomRightRadius: 4 },
  bubbleFailed: { backgroundColor: colors.danger },
  body: { fontSize: 16, color: colors.text },
  meta: { fontSize: 11, color: colors.muted, marginTop: 2, marginHorizontal: 4 },
});
