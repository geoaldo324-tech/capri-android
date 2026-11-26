// New things to ask her (1.10.0), out loud ("Capri, …", listen.js) or in her chat:
// - "juguemos ajedrez", "play with me": her games screen opens (games.html).
// - "mi historial", "chat history": your chat history (chatlog.html).
// - "mándale a Ana por WhatsApp que ya voy": WhatsApp (or Telegram, Messenger, SMS…) opens with the
//   message written; you pick who and tap send. Android doesn't let an app send a message for you
//   (and she never would without you seeing it).
// - "ve atrás", "ve al inicio", "baja", "toca Ajustes", "escribe aquí hola": she uses your phone, if you
//   turned it on in Accessibility (control.js, ControlService.kt).

import { call, send } from './native.js';
import { SPLIT_ASK, MERGE_ASK } from './twin.js';
import { controlAsk, wire as wireControl } from './control.js';

// (1.13.0) "platicar con otra IA": she starts the talk with the guest set in Su mente (duo.js)
const DUO_ASK = /platicar con otra ia|hablar con otra ia|platica con otra ia|conversa con otra ia|talk to another ai|chat with another ai/iu;
// (1.13.0) "mira esto", "qué ves", "tómame una foto": a photo with the phone's camera, only
// when you ask (CameraActivity.kt; Android asks its permission the first time)
const CAMERA_ASK = /mira esto|qu[eé] ves|mira lo que|mira con la c[aá]mara|t[oó]ma(?:me)? (?:una )?foto|haz(?:me)? (?:una )?foto|saca (?:una )?foto|look at this|take a (?:photo|picture)/iu;
const CAM_T = {
  es: { denied: 'No me diste permiso de usar la cámara. Sin eso no puedo tomar la foto.', failed: 'No pude tomar la foto. Intenta otra vez.', noVision: 'Ya tomé la foto — está en el chat. Mi IA de ahora no puede ver imágenes, pero ahí la tienes.', said: '¡Foto tomada!' },
  en: { denied: 'You didn’t give me permission to use the camera. Without it I can’t take the photo.', failed: 'I couldn’t take the photo. Try again.', noVision: 'Photo taken — it’s in the chat. My current AI can’t see pictures, but there it is.', said: 'Photo taken!' },
};

/**
 * (1.13.0) The camera, when you ask: one photo (the native side puts it in the app's chat too),
 * then her look at it — with a brain that sees, she describes it her way (lookSay, the same as
 * a picture shared with her); without one, the photo is the answer and she says so.
 * { line, face } or null when it wasn't a camera ask.
 */
export async function cameraAsk(text, lang, chat) {
  const said = String(text || '').trim();
  if (!said || said.split(/\s+/).length > 10 || !CAMERA_ASK.test(said)) return null;
  const t = CAM_T[lang] || CAM_T.en;
  let shot;
  try {
    shot = await call('camera:shot');
  } catch (err) {
    return { line: /denied|permission|permiso/iu.test(String(err?.message || '')) ? t.denied : t.failed, face: 'aside' };
  }
  if (!shot?.image) return { line: t.failed, face: 'aside' };
  if (!window.host?.lookSay || !chat?.lookPersona) return { line: t.said, face: 'look' };
  const res = await window.host.lookSay({
    id: window.host.lookPut(shot.image),
    persona: chat.lookPersona(),
    history: (chat.history || []).slice(-12),
    prompt: `${said}\n\n(Note for you, never repeat it: this picture is a photo you just took with the phone's camera because they asked you to look at something with it. React to what's in it as yourself, in a few sentences: what it is, what you think, a joke if it fits; if they asked something about it, answer that.)`,
  }).catch(() => null);
  if (!res?.ok) return { line: res?.code === 'novision' ? t.noVision : t.said, face: 'look' };
  const line = String(res.text || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();
  return { line: line || t.said, face: 'look' };
}

// (1.14.0) her surroundings, when you ask: the screen's brightness and Do Not Disturb
// (Actions.kt's pcDo, behind Android's own special accesses — its answer says «needs»
// when one is missing, and its settings page is already open by then)
const ENV_T = {
  es: { up: 'Te subí el brillo a {n}%.', down: 'Te bajé el brillo a {n}%.', set: 'Brillo a {n}%.', needWrite: 'Para tocar el brillo, Android me pide un permiso especial: te abrí su pantalla — actívalo y pídeme otra vez.', dndOn: 'No molestar activado: solo tus alarmas y tu gente.', dndOff: 'No molestar quitado.', needDnd: 'Para tocar No molestar, Android me pide su acceso especial: te abrí su pantalla — actívalo y pídeme otra vez.', failed: 'No pude hacerlo.' },
  en: { up: 'Brightness up to {n}%.', down: 'Brightness down to {n}%.', set: 'Brightness at {n}%.', needWrite: 'To touch the brightness, Android asks me for a special permission: I opened its screen — turn it on and ask me again.', dndOn: 'Do Not Disturb on: only your alarms and your people.', dndOff: 'Do Not Disturb off.', needDnd: 'To touch Do Not Disturb, Android asks me for its special access: I opened its screen — turn it on and ask me again.', failed: 'I couldn’t do that.' },
};
const BRIGHT_UP = /sube(?:le)? (?:el )?brillo|más brillo|mas brillo|brillo (?:más|mas) alto|brightness up|raise the brightness|brighter/iu;
const BRIGHT_DOWN = /baja(?:le)? (?:el )?brillo|menos brillo|brillo (?:más|mas) bajo|brightness down|lower the brightness|dimmer/iu;
const BRIGHT_SET = /brillo (?:al?|en) (\d{1,3})\s?%|brightness (?:to|at) (\d{1,3})\s?%/iu;
const DND_ON = /activa(?:r)? (?:el )?(?:modo )?no molestar|pon(?:er)? (?:el )?(?:modo )?no molestar|do not disturb on|turn on do not disturb/iu;
const DND_OFF = /quita(?:r)? (?:el )?(?:modo )?no molestar|desactiva(?:r)? (?:el )?(?:modo )?no molestar|do not disturb off|turn off do not disturb/iu;

/**
 * (1.14.0) Brightness and Do Not Disturb, done — not asked to her AI (which would pretend).
 * Async like cameraAsk: the native side answers { ok, level | on, needs? }.
 * { line, face } or null when it wasn't an environment ask.
 */
export async function envAsk(text, lang) {
  const said = String(text || '').trim();
  if (!said || said.split(/\s+/).length > 10) return null;
  const t = ENV_T[lang] || ENV_T.en;
  const done = (res, okLine) => {
    if (res?.needs === 'write_settings') return { line: t.needWrite, face: 'aside' };
    if (res?.needs === 'dnd_access') return { line: t.needDnd, face: 'aside' };
    return res?.ok ? { line: okLine(res), face: 'look' } : { line: t.failed, face: 'aside' };
  };
  const set = BRIGHT_SET.exec(said);
  if (set) {
    const res = await call('pc:do', { action: 'brightness', level: Number(set[1] ?? set[2]) }).catch(() => null);
    return done(res, (r) => t.set.replace('{n}', r.level));
  }
  const up = BRIGHT_UP.test(said);
  if (up || BRIGHT_DOWN.test(said)) {
    const now = await call('pc:do', { action: 'brightness' }).catch(() => null);
    if (!now?.ok) return done(now, () => '');
    const level = Math.min(100, Math.max(0, (now.level ?? 50) + (up ? 25 : -25)));
    const res = await call('pc:do', { action: 'brightness', level }).catch(() => null);
    return done(res, (r) => (up ? t.up : t.down).replace('{n}', r.level));
  }
  if (DND_ON.test(said)) {
    const res = await call('pc:do', { action: 'dnd', on: true }).catch(() => null);
    return done(res, () => t.dndOn);
  }
  if (DND_OFF.test(said)) {
    const res = await call('pc:do', { action: 'dnd', on: false }).catch(() => null);
    return done(res, () => t.dndOff);
  }
  return null;
}

// (1.14.0) "busca X": her browser opens with the search — just that (no page reading, no
// summaries: out of this series, as the proposal itself said). Music is not this: a song
// is asked to her music, as always
const BUSCA_ASK = /^(?:busca|búscame|buscar|search(?: for)?|googlea)\s+(.+)$/iu;
const BUSCA_NOT = /canción|cancion|música|musica|\bsong\b|reproduce|ponme|play/iu;

const GAMES_ASK = /juguemos|jugar (?:algo|contigo|conmigo|ajedrez|buscaminas|cartas|al? (?:21|gato|ajedrez|buscaminas|blackjack|tres en raya|ping ?pong|piedra,? papel))|jugar (?:ping ?pong|piedra,? papel)|juega(?:s)? conmigo|quiero jugar (?:contigo|algo|ajedrez|buscaminas|cartas)|tus juegos|let'?s play|play (?:a game|with me|chess|minesweeper|cards|blackjack|tic.?tac.?toe|ping ?pong|rock,? paper)|your games|jouons|on joue|joue avec moi|jouer aux? (?:échecs|cartes|démineur|morpion)|vamos jogar|bora jogar|joga comigo|jogar (?:xadrez|cartas|campo minado|jogo da velha)|давай (?:сыграем|поиграем)|поиграй со мной|сыграем в/iu;
const HISTORY_ASK = /(?:mi|el|nuestro|tu) historial|historial (?:de|del) chat|chat history|our (?:chat|history)|historique|hist[oó]rico (?:do|de) chat|meu hist[oó]rico|история (?:чата|переписки)/iu;
const APPS = [
  ['whatsapp', /whats ?app|wasap|wsp|guasap|ватсап|вотсап/iu, 'com.whatsapp'],
  ['telegram', /telegram|телеграм/iu, 'org.telegram.messenger'],
  ['messenger', /messenger|мессенджер/iu, 'com.facebook.orca'],
  ['instagram', /instagram|insta\b|инстаграм/iu, 'com.instagram.android'],
  ['signal', /\bsignal\b/iu, 'org.thoughtcrime.securesms'],
  ['sms', /\bsms\b|mensaje de texto|text message|texto|смс/iu, null],
];
const MSG_VERB = /m[aá]nd(?:a|ale|ales)|env[ií]a(?:le|les)?|escr[ií]be(?:le|les)?|dile|send|text|message|tell|envoie|[ée]cris|dis(?:-lui)?|envia|escreve|fala|manda|напиши|отправь|скажи|передай/iu;
const MSG_BODY = /(?:\bque\b|diciendo|saying|that|\bque\b|qu['’]|dizendo|falando|что|:)\s*(.+)$/iu;
const MSG_TO = /(?:^|\s)(?:a|to|à|para|pra)\s+((?:(?:mi|my|ma|mon|minha|meu)\s+)?[\p{L}'’-]+)/giu;
const NOT_WHO = /^(?:un|una|a|an|um|uma|un|une|mensaje|message|mensagem|whats ?app|telegram|sms|texto|text)$/iu;
const who = (said) => [...said.matchAll(MSG_TO)].map((m) => m[1]).find((w) => !NOT_WHO.test(w.split(/\s+/).pop())) || '';

const T = {
  es: { games: '¡Va! Elige el juego.', history: 'Aquí está todo lo que hemos hablado.', msg: 'Listo: te abrí {app} con el mensaje. Elige a {to}y toca enviar.', msgNoText: 'Te abrí {app}. ¿Qué le quieres decir? Escríbelo tú, yo no envío nada sin ti.', anyone: '', duo: '¡Va! Que platique con mi invitado. La conversación sale en el chat de la app.', busca: 'Buscando «{q}» en tu navegador.' },
  en: { games: 'Okay! Pick a game.', history: 'Here’s everything we’ve talked about.', msg: 'Done: I opened {app} with the message. Pick {to}and tap send.', msgNoText: 'I opened {app}. Write what you want to say: I never send anything without you.', anyone: '', duo: 'Okay! Let me talk with my guest. The conversation shows in the app’s chat.', busca: 'Searching for “{q}” in your browser.' },
  fr: { games: 'D’accord ! Choisis un jeu.', history: 'Voilà tout ce qu’on s’est dit.', msg: 'C’est prêt : j’ai ouvert {app} avec le message. Choisis {to}et touche envoyer.', msgNoText: 'J’ai ouvert {app}. Écris ce que tu veux dire : je n’envoie jamais rien sans toi.', anyone: '' },
  pt: { games: 'Bora! Escolhe o jogo.', history: 'Aqui está tudo o que a gente conversou.', msg: 'Pronto: abri o {app} com a mensagem. Escolhe {to}e toca em enviar.', msgNoText: 'Abri o {app}. Escreve o que quer dizer: eu nunca envio nada sem você.', anyone: '' },
  ru: { games: 'Давай! Выбирай игру.', history: 'Вот всё, о чём мы говорили.', msg: 'Готово: я открыла {app} с сообщением. Выбери {to}и нажми «отправить».', msgNoText: 'Я открыла {app}. Напиши, что хочешь сказать: я ничего не отправляю без тебя.', anyone: '' },
  vi: { games: 'Được thôi! Chọn một trò đi.', history: 'Đây là mọi thứ chúng ta đã nói.', msg: 'Xong: mình đã mở {app} với tin nhắn. Chọn {to}rồi chạm gửi.', msgNoText: 'Mình đã mở {app}. Viết điều bạn muốn nói: mình không bao giờ gửi gì mà không có bạn.', anyone: '' },
  ja: { games: 'いいよ！ゲームを選んで。', history: 'これが今まで話したこと全部だよ。', msg: '{app}をメッセージ付きで開いたよ。{to}を選んで送信をタップして。', msgNoText: '{app}を開いたよ。言いたいことを書いて。あなたなしで何かを送ることは絶対にないから。', anyone: '' },
  de: { games: 'Okay! Such dir ein Spiel aus.', history: 'Hier ist alles, worüber wir geredet haben.', msg: 'Erledigt: Ich habe {app} mit der Nachricht geöffnet. Wähl {to}und tipp auf Senden.', msgNoText: 'Ich habe {app} geöffnet. Schreib, was du sagen willst: Ich schicke nie etwas ohne dich.', anyone: '' },
  it: { games: 'Va bene! Scegli un gioco.', history: 'Ecco tutto quello che ci siamo detti.', msg: 'Fatto: ho aperto {app} con il messaggio. Scegli {to}e tocca invia.', msgNoText: 'Ho aperto {app}. Scrivi quello che vuoi dire: non invio mai niente senza di te.', anyone: '' },
};
const NAMES = { whatsapp: 'WhatsApp', telegram: 'Telegram', messenger: 'Messenger', instagram: 'Instagram', signal: 'Signal', sms: 'SMS' };

/** What she does for this, or null: { line, face }. */
export function extraAsk(text, lang) {
  const t = T[lang] || T.en;
  const said = String(text || '').trim();
  if (!said) return null;
  const words = said.split(/\s+/).length;
  // (1.10.0) "Capri, divídete" / "júntense": two of her, or one again (twin.js)
  const twin = window.dev?.twin;
  if (twin && words <= 8 && SPLIT_ASK.test(said)) {
    const was = twin.on;
    twin.split();
    return { line: was ? twin.lines().already : twin.lines().split[2], face: 'smug' };
  }
  if (twin && words <= 8 && MERGE_ASK.test(said)) {
    return twin.merge(false) ? { line: twin.lines().merge[0], face: 'laugh' } : { line: twin.lines().alone, face: 'look' };
  }
  // (1.11.0) "Capri, modo reacción": she watches your videos with you (reactmode.js)
  if (words <= 12) {
    const reacting = window.dev?.reactMode?.ask(said);
    if (reacting) return reacting;
  }
  // (1.11.0) "Capri, dame una neurodanza" / "mis neurodanzas" (neuro.js)
  if (words <= 12) {
    const bd = window.dev?.neuro?.ask(said);
    if (bd) return bd;
  }
  // (1.11.0) "Capri, modo sueño", "despiértame a las 7", "ya desperté": her sleep mode (sleep.js)
  if (words <= 14) {
    const slept = window.dev?.sleep?.ask(said);
    if (slept) return slept;
  }
  // (1.12.0) "estoy leyendo Dune", "voy en la página 120", "modo lectura" (reading.js)
  if (words <= 16) {
    const read = window.dev?.reading?.ask(said, lang);
    if (read) return read;
  }
  // (1.13.0) "platicar con otra IA": the talk starts with the guest from Su mente (duo.js)
  if (words <= 10 && DUO_ASK.test(said) && window.capriDuoStart) {
    window.capriDuoStart({});
    return { line: t.duo || T.en.duo, face: 'laugh' };
  }
  // (1.14.0) "busca X": your browser, with the search (open:url, the same as her weather)
  if (words <= 12 && !BUSCA_NOT.test(said)) {
    const q = BUSCA_ASK.exec(said)?.[1]?.trim();
    if (q) {
      send('open:url', { url: `https://www.google.com/search?q=${encodeURIComponent(q)}` });
      return { line: (t.busca || T.en.busca).replace('{q}', q), face: 'look' };
    }
  }
  // (1.10.0) "ve atrás", "baja", "toca Ajustes", "escribe aquí hola": she uses your phone (control.js)
  const acted = controlAsk(said, lang);
  if (acted) return acted;
  if (GAMES_ASK.test(said) && words <= 10) {
    send('page:open', { page: 'games' });
    return { line: t.games, face: 'laugh' };
  }
  if (HISTORY_ASK.test(said) && words <= 10) {
    send('page:open', { page: 'chatlog' });
    return { line: t.history, face: 'look' };
  }
  const app = APPS.find(([, re]) => re.test(said));
  if (app && MSG_VERB.test(said)) {
    const [id, re, pkg] = app;
    // what to say: after "que", "diciendo", ":"…; else what's after the app's name
    let body = MSG_BODY.exec(said.replace(re, ' '))?.[1] || said.split(re)[1] || '';
    body = body.replace(/^[\s,.:;-]+/, '').replace(/^(?:que|diciendo|saying|that|dizendo|что)\s+/iu, '').trim();
    const to = who(said.split(MSG_BODY)[0] || said);
    send('msg:share', { app: id, pkg, text: body });
    const line = (body ? t.msg : t.msgNoText).replace('{app}', NAMES[id]).replace('{to}', to ? `${to} ` : '');
    return { line, face: 'smug' };
  }
  return null;
}

// her chat: these happen here, not as a question to her AI (which would pretend it sent it)
export function chatAsks({ lang }) {
  wireControl();
  const chat = window.dev?.chat;
  if (!chat) return;
  const ask = chat.send.bind(chat);
  chat.send = async function (text, ...rest) {
    if (this.turn || !this.open || this.agent) return ask(text, ...rest);
    // (1.13.0) a photo with the camera, when you ask her ("mira esto"): async, like her looks
    // (1.14.0) …or her surroundings (brightness, Do Not Disturb): async the same way
    const did = extraAsk(text, lang()) || await cameraAsk(text, lang(), this) || await envAsk(text, lang());
    if (!did) {
      // (1.13.0) an order of several steps: she does them and tells it on her panel as she goes
      if (await window.capriSteps?.tryTask(text).catch(() => false)) {
        this.history.push({ role: 'user', content: text });
        return undefined;
      }
      return ask(text, ...rest);
    }
    this.history.push({ role: 'user', content: text });
    this.panel.chatTurn?.({ quote: text, face: did.face });
    this.panel.stream(did.line, { done: true });
    this.panel.setFace(did.face);
    this.remember({ role: 'assistant', content: did.line });
    return undefined;
  };
}
