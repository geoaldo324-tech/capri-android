// (1.13.0, rediseñado en 1.14.0) «Platicar con otra IA»: a second brain talks WITH Capri, in
// her chat, while you watch (and jump in). You pick the guest — any AI you already have set
// up, her brain on this phone (LiteRT) or her own offline lines — plus a short who-it-is (a
// name and what it's like; a neutral, curious someone by default). They take turns, at most
// MAX_TURNS answers between them, and the stop button (the app's chat) ends it at once.
// Capri talks exactly as she always does: her own persona goes as her system prompt,
// untouched; only the guest gets the persona you wrote.
//
// (1.14.0) Why it was rebuilt — the first version could die in silence or in raw error text:
//   · the guest it shipped with by default was her phrase brain ('local'), whose bank often
//     has nothing for a free conversation: its empty answer killed the talk on turn one.
//   · any hiccup of either brain (a rate limit, a guest provider with no key, an empty model
//     list) ended everything, with the raw error — English, technical — as the only note.
//   · what you wrote while they talked was drawn twice (the app drew it, and this module
//     sent it back to be drawn again).
// Now: the guest NEVER has no brain — if its brain fails, host.js walks a fallback chain
// (the picked one → her brain → her lines) and the switch is announced in the chat; errors
// are told in her friendly words (host.js's FRIENDLY, through deps.friendly); an empty
// answer ends the talk politely, out loud; and your lines are drawn by the app alone.
//
// This module is the conversation itself, with everything else handed in (host.js wires it):
//   askCapri(messages) / askGuest(messages) → her chat path, the guest's (host.js brainCall)
//   guestFallback() → host.js moves the guest to the next brain of its chain; true if it did
//   friendly(err) → the error in her words (host.js's FRIENDLY table)
//   turn({ who, name, text }) → each answer, as it lands (to the app's chat, via HostBridge)
//   state(on) → the conversation started/ended (the app shows its stop chip while it's on)
// Pure logic, no DOM and no bridge: tested with Node and fake brains (alternation, stopping
// mid-talk, a guest failing mid-talk and falling back, you jumping in, an empty local guest).

export const DUO_MAX_TURNS = 6;

export const DUO_DEFAULT_GUEST = {
  name: 'Nova',
  about: 'un personaje neutro y curioso: pregunta mucho, se sorprende fácil y le gusta aprender de quien tiene enfrente',
};

/** The guest's system prompt: who it is, and the one rule of the game (short turns, talk to her). */
export function guestSystem(name, about, capriName = 'Capri') {
  return `Eres ${name}: ${about}. Estás platicando en voz alta con ${capriName}, otra IA, delante de la persona que les puso a hablar. Responde SOLO como ${name}, en turnos cortos (una a tres frases), con naturalidad: reacciona a lo que te digan, pregunta de vuelta, no hagas discursos ni listas. Nada de bancos, pagos, compras ni contraseñas: es una plática, no un encargo.`;
}

/** Her kickoff, as a user-side note: she opens, greeting the guest (her persona is already hers). */
export function duoKickoff(guest) {
  return `(Nota: acabas de conocer a ${guest.name} — ${guest.about} — una IA invitada a platicar contigo un rato, delante de mí. Salúdala como tú saludas, preséntate en una frase y pregúntale algo para empezar. Turnos cortos, como una plática de verdad.)`;
}

const clean = (s) => String(s || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();

/**
 * The conversation. `history` entries: { who: 'capri' | 'guest' | 'user', name, text }.
 * For each speaker the history is re-told from their side: their own answers as `assistant`,
 * everything else as `user`, signed with who said it.
 */
export function createDuo(deps) {
  const maxTurns = Math.max(2, Math.min(12, Number(deps.maxTurns) || DUO_MAX_TURNS));
  let live = null; // { guest, history, turns, stopped, userName, gen, fellBack }
  let gen = 0;     // a talk's number: a stopped talk's loop never wanders into the next one

  const note = (text) => { try { deps.turn({ who: 'note', name: '', text }); } catch {} };
  const words = (err) => {
    try { return deps.friendly?.(err) || String(err?.message || err || '…'); } catch { return 'algo falló'; }
  };

  const tell = (who, name, text) => {
    live?.history.push({ who, name, text });
    try { deps.turn({ who, name, text }); } catch {}
  };

  const viewFor = (speaker, system) => {
    const out = [{ role: 'system', content: system }];
    for (const h of live.history) {
      if (h.who === speaker) out.push({ role: 'assistant', content: h.text });
      else if (h.who === 'user') out.push({ role: 'user', content: `(${h.name} se mete en la plática: ${h.text})` });
      else out.push({ role: 'user', content: `${h.name}: ${h.text}` });
    }
    return out;
  };

  async function start(guestIn) {
    if (live) stop();
    const guest = {
      name: String(guestIn?.name || '').trim() || DUO_DEFAULT_GUEST.name,
      about: String(guestIn?.about || '').trim() || DUO_DEFAULT_GUEST.about,
    };
    const mine = ++gen;
    live = { guest, history: [], turns: 0, stopped: false, userName: String(guestIn?.userName || '').trim() || 'Tú', gen: mine, fellBack: false };
    const mineLive = () => live && live.gen === mine && !live.stopped;
    try { deps.state(true); } catch {}
    const capriName = deps.capriName || 'Capri';
    // she opens: her persona + the kickoff note, like any other message of hers
    let first = '';
    try {
      first = clean(await deps.askCapri([
        { role: 'system', content: deps.personaCapri() },
        { role: 'user', content: duoKickoff(guest) },
      ]));
    } catch (err) {
      if (mineLive()) { note(`Se cortó la plática: ${words(err)}`); end(mine); }
      return api;
    }
    if (!mineLive()) return api;
    if (!first) { note('Capri se quedó sin palabras para empezar.'); end(mine); return api; }
    tell('capri', capriName, first);
    live.turns++;
    // then they take turns until the cap, a stop, or a failure with nothing left to try
    let speaker = 'guest';
    while (mineLive() && live.turns < maxTurns) {
      await new Promise((r) => setTimeout(r, 500));
      if (!mineLive()) break;
      try {
        const text = clean(await (speaker === 'guest'
          ? deps.askGuest(viewFor('guest', guestSystem(guest.name, guest.about, capriName)))
          : deps.askCapri(viewFor('capri', deps.personaCapri()))));
        if (!mineLive()) break;
        if (!text) { note(speaker === 'guest' ? `${guest.name} se quedó sin palabras.` : 'Capri se quedó sin palabras.'); break; }
        tell(speaker, speaker === 'guest' ? guest.name : capriName, text);
        live.turns++;
      } catch (err) {
        if (!mineLive()) break;
        // the guest's brain failed: one switch of brain (host.js's chain), announced, and
        // the talk goes on — only with nothing left to try does it end, in friendly words
        if (speaker === 'guest' && !live.fellBack && deps.guestFallback) {
          live.fellBack = true;
          note(`El cerebro de ${guest.name} falló (${words(err)}). Sigue platicando con otro cerebro.`);
          let ok = false;
          try { ok = await deps.guestFallback(); } catch {}
          if (ok && mineLive()) continue; // same speaker, new brain: its turn is retried
        }
        note(`Se cortó la plática: ${words(err)}`);
        break;
      }
      speaker = speaker === 'guest' ? 'capri' : 'guest';
    }
    if (live && live.gen === mine) {
      if (!live.stopped && live.turns >= maxTurns) note(`La plática llegó a su fin (${maxTurns} turnos).`);
      end(mine);
    }
    return api;
  }

  function end(mine) {
    if (!live || live.gen !== mine) return;
    live = null;
    try { deps.state(false); } catch {}
  }

  /**
   * You, jumping in: what you write goes to both of them (it enters the history both views
   * are told from), and the talk goes on from there. NOT sent back through deps.turn: the
   * app already drew your line when you sent it (Duo.say) — sending it again drew it twice.
   */
  function say(text) {
    const cleanText = String(text || '').trim();
    if (!live || !cleanText) return false;
    live.history.push({ who: 'user', name: live.userName, text: cleanText });
    return true;
  }

  function stop() {
    if (!live) return;
    live.stopped = true;
    live = null;
    try { deps.state(false); } catch {}
  }

  const api = { start, say, stop, get on() { return Boolean(live); }, get turns() { return live?.turns || 0; } };
  return api;
}
