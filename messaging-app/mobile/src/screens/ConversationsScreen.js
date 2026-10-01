import { useCallback, useLayoutEffect, useState } from 'react';
import { Alert, FlatList, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { useApp, useSocketEvent } from '../AppContext';
import { colors, formatPhone, formatTime } from '../format';

export default function ConversationsScreen({ navigation }) {
  const { client, connected, config, logout } = useApp();
  const [conversations, setConversations] = useState([]);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    try {
      setConversations(await client.conversations());
    } catch (err) {
      if (err.status === 401) logout();
    }
  }, [client, logout]);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  useSocketEvent('message', load);
  useSocketEvent('status', load);

  useLayoutEffect(() => {
    navigation.setOptions({
      headerTitle: () => (
        <View>
          <Text style={styles.headerTitle}>Messages</Text>
          <Text style={[styles.headerSub, !connected && { color: colors.danger }]}>
            {connected ? formatPhone(config.phoneNumber ?? '') : 'Hors ligne'}
          </Text>
        </View>
      ),
      headerRight: () => (
        <Pressable
          hitSlop={12}
          onPress={() =>
            Alert.alert('Se déconnecter ?', 'Tu devras ressaisir l’adresse et le token.', [
              { text: 'Annuler', style: 'cancel' },
              { text: 'Déconnexion', style: 'destructive', onPress: logout },
            ])
          }
        >
          <Text style={styles.headerButton}>Déconnexion</Text>
        </Pressable>
      ),
    });
  }, [navigation, connected, config, logout]);

  async function onRefresh() {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  }

  function confirmDelete(contact) {
    Alert.alert('Supprimer la conversation ?', formatPhone(contact), [
      { text: 'Annuler', style: 'cancel' },
      {
        text: 'Supprimer',
        style: 'destructive',
        onPress: async () => {
          await client.deleteConversation(contact);
          load();
        },
      },
    ]);
  }

  return (
    <View style={styles.container}>
      <FlatList
        data={conversations}
        keyExtractor={(item) => item.contact}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        ListEmptyComponent={
          <Text style={styles.empty}>Aucune conversation.{'\n'}Appuie sur ✏️ pour envoyer un SMS.</Text>
        }
        renderItem={({ item }) => (
          <Pressable
            style={({ pressed }) => [styles.row, pressed && { backgroundColor: '#f3f4f6' }]}
            onPress={() => navigation.navigate('Chat', { phone: item.contact })}
            onLongPress={() => confirmDelete(item.contact)}
          >
            <View style={styles.avatar}>
              <Text style={styles.avatarText}>#</Text>
            </View>
            <View style={styles.rowBody}>
              <View style={styles.rowTop}>
                <Text style={[styles.contact, item.unread > 0 && styles.bold]}>{formatPhone(item.contact)}</Text>
                <Text style={styles.time}>{formatTime(item.last_at)}</Text>
              </View>
              <View style={styles.rowTop}>
                <Text style={[styles.preview, item.unread > 0 && styles.bold]} numberOfLines={1}>
                  {item.last_direction === 'out' ? 'Toi : ' : ''}
                  {item.last_body}
                </Text>
                {item.unread > 0 && (
                  <View style={styles.badge}>
                    <Text style={styles.badgeText}>{item.unread}</Text>
                  </View>
                )}
              </View>
            </View>
          </Pressable>
        )}
      />

      <Pressable style={styles.fab} onPress={() => navigation.navigate('NewMessage')}>
        <Text style={styles.fabText}>✏️</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  headerTitle: { fontSize: 17, fontWeight: '700', color: colors.text },
  headerSub: { fontSize: 12, color: colors.muted },
  headerButton: { color: colors.primary, fontSize: 15 },
  empty: { textAlign: 'center', color: colors.muted, marginTop: 80, lineHeight: 22 },
  row: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  avatar: {
    width: 46,
    height: 46,
    borderRadius: 23,
    backgroundColor: '#dbeafe',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 12,
  },
  avatarText: { color: colors.primary, fontSize: 18, fontWeight: '700' },
  rowBody: { flex: 1, justifyContent: 'center' },
  rowTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  contact: { fontSize: 16, color: colors.text },
  time: { fontSize: 12, color: colors.muted },
  preview: { flex: 1, color: colors.muted, marginTop: 2, marginRight: 8 },
  bold: { fontWeight: '700', color: colors.text },
  badge: {
    backgroundColor: colors.primary,
    borderRadius: 10,
    minWidth: 20,
    paddingHorizontal: 6,
    alignItems: 'center',
  },
  badgeText: { color: '#fff', fontSize: 12, fontWeight: '700' },
  fab: {
    position: 'absolute',
    right: 20,
    bottom: 32,
    width: 58,
    height: 58,
    borderRadius: 29,
    backgroundColor: colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
    elevation: 4,
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
  },
  fabText: { fontSize: 24 },
});
