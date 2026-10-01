import { useState } from 'react';
import { Alert, KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from 'react-native';
import { useHeaderHeight } from '@react-navigation/elements';
import { useApp } from '../AppContext';
import Composer from '../components/Composer';
import { colors } from '../format';

export default function NewMessageScreen({ navigation }) {
  const { client } = useApp();
  const headerHeight = useHeaderHeight();
  const [to, setTo] = useState('');

  async function send(text) {
    try {
      const message = await client.send(to, text);
      navigation.replace('Chat', { phone: message.contact });
      return true;
    } catch (err) {
      Alert.alert('SMS non envoyé', err.message);
      // Le numéro était valide mais Twilio a refusé : on ouvre quand même la conversation
      if (err.data?.message) navigation.replace('Chat', { phone: err.data.message.contact });
      return false;
    }
  }

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={headerHeight}
    >
      <View style={styles.toRow}>
        <Text style={styles.toLabel}>À :</Text>
        <TextInput
          style={styles.toInput}
          value={to}
          onChangeText={setTo}
          placeholder="06 12 34 56 78 ou +33612345678"
          keyboardType="phone-pad"
          autoFocus
        />
      </View>
      <View style={{ flex: 1 }} />
      <Composer onSend={send} disabled={to.replace(/\D/g, '').length < 6} />
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  toRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  toLabel: { fontSize: 16, color: colors.muted, marginRight: 8 },
  toInput: { flex: 1, fontSize: 16, paddingVertical: 14 },
});
