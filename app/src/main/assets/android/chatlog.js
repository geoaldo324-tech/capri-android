// Your chat history with her (1.10.0): "un historial de chat, accesible en la app, para ver qué le
// has dicho a Capri y qué te respondió". Each answer of hers, with what you said before it, is kept on
// this phone only (ChatLog.kt, a file in the app's own storage), and the app shows it
// (Tú › Historial de chat), where it can be emptied too. Nothing of it leaves the phone.

import { send, on } from './native.js';

export function chatLog() {
  const chat = window.dev?.chat;
  if (!chat?.remember) return;
  // (1.13.1) her call (CallActivity, Ears.kt says when it starts and ends): what was said on
  // it is kept like everything else, with its mark, so the app's history shows the little phone
  let inCall = false;
  on('capri:call', (m) => { inCall = !!m?.on; });
  const remember = chat.remember.bind(chat);
  chat.remember = function (message, ...rest) {
    const out = remember(message, ...rest);
    try {
      if (message?.role === 'assistant' && typeof message.content === 'string') {
        const before = [...this.history].reverse().find((m, i) => i > 0 && m.role === 'user');
        const q = String(before?.content || '').replace(/\n\n\(Note for you, never repeat it:[\s\S]*$/, '').slice(0, 2000);
        send('chatlog:add', { q, a: message.content.slice(0, 4000), at: Date.now(), ...(inCall ? { call: 1 } : {}) });
      }
    } catch {}
    return out;
  };
}
