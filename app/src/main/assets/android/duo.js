// (1.13.0) «Platicar con otra IA»: a second brain talks WITH Capri, in her chat, while you watch
// (and jump in). You pick the guest — any AI you already have set up, her brain on this phone
// (LiteRT) or her own offline lines — plus a short who-it-is (a name and what it's like; a neutral,
// curious someone by default). They take turns, at most MAX_TURNS answers between them, and the
// stop button (the app's chat) ends it at once. Capri talks exactly as she always does: her own
// persona goes as her system prompt, untouched; only the guest gets the persona you wrote.
//
// This module is the conversation itself, with everything else handed in (host.js wires it):
//   askCapri(messages) / askGuest(messages) → her chat path, the guest's (host.js brainCall)
//   turn({ who, name, text }) → each answer, as it lands (to the app's chat, via HostBridge)
//   state(on) → the conversation started/ended (the app shows its stop chip while it's on)
// Pure logic, no DOM and no bridge: tested with Node and two fake brains.

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
  let live = null; // { guest, history, turns, stopped, userName }

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
    live = { guest, history: [], turns: 0, stopped: false, userName: String(guestIn?.userName || '').trim() || 'Tú' };
    try { deps.state(true); } catch {}
    const capriName = deps.capriName || 'Capri';
    // she opens: her persona + the kickoff note, like any other message of hers
    try {
      const first = clean(await deps.askCapri([
        { role: 'system', content: deps.personaCapri() },
        { role: 'user', content: duoKickoff(guest) },
      ]));
      if (!live || live.stopped) return api;
      if (first) { tell('capri', capriName, first); live.turns++; }
    } catch (err) {
      fail(err);
      return api;
    }
    // then they take turns until the cap, a stop, or a failure
    let speaker = 'guest';
    while (live && !live.stopped && live.turns < maxTurns) {
      await new Promise((r) => setTimeout(r, 500));
      if (!live || live.stopped) break;
      try {
        const text = clean(await (speaker === 'guest'
          ? deps.askGuest(viewFor('guest', guestSystem(guest.name, guest.about, capriName)))
          : deps.askCapri(viewFor('capri', deps.personaCapri()))));
        if (!live || live.stopped) break;
        if (!text) break;
        tell(speaker, speaker === 'guest' ? guest.name : capriName, text);
        live.turns++;
      } catch (err) {
        fail(err);
        break;
      }
      speaker = speaker === 'guest' ? 'capri' : 'guest';
    }
    if (live) {
      live = null;
      try { deps.state(false); } catch {}
    }
    return api;
  }

  function fail(err) {
    if (!live) return;
    const why = String(err?.message || err || '…').slice(0, 140);
    try { deps.turn({ who: 'note', name: '', text: `Se cortó la plática: ${why}` }); } catch {}
    live.stopped = true;
  }

  /** You, jumping in: what you write goes to both of them, and the talk goes on from there. */
  function say(text) {
    const cleanText = String(text || '').trim();
    if (!live || !cleanText) return false;
    tell('user', live.userName, cleanText);
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
