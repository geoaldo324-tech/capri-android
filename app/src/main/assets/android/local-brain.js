// Her brain on this phone (1.12.5): no server, no internet, no AI at all. She answers from her
// own line bank (lines.js, already said the phone's way by phone-lines.js — this file only reads
// it, lazily, so those words are hers exactly) and from what she remembers about you
// (memory.json's facts, the same ones her AI is told about). Short answers, the way she talks:
// greetings and goodbyes, how she is, who she is, the time and the day off the phone's clock,
// what she remembers when your words touch it, and — when nothing fits — one of her own lines,
// sometimes saying plainly that the rest needs her AI and there's no internet.
//
// It is NOT an AI: it can't think up new answers. host.js calls it when her brain is this one
// (provider 'local', «Sin internet») and as the last answer when a chat dies with no internet.

import { LINES } from '../capri/src/renderer/js/lines.js';

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

// her words with no accents, no capitals and no punctuation, so "¿Cómo estás?" and "como estas"
// are the same ask (the ¿ and ? would otherwise stick to the words and no pattern would match)
const plain = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-zñ0-9\s]/g, ' ');
const wordsOf = (s) => plain(s).match(/[a-zñ0-9]+/g) || [];
const STOP = new Set(('de la el en y a que los del las un una con por para me te se mi tu su es al lo como mas pero sus le ya o este esta esto entre cuando hay muy sin sobre tambien quien era eso').split(' '));
const content = (s) => wordsOf(s).filter((w) => w.length > 2 && !STOP.has(w));

// the last thing you said, without the note her chat adds for her AI ("(Note for you, …)")
function yourWords(messages) {
  for (let i = (messages || []).length - 1; i >= 0; i--) {
    if (messages[i]?.role !== 'user') continue;
    return String(messages[i].content || '').replace(/\n\n\(Note for you[\s\S]*$/i, '').trim();
  }
  return '';
}

// one of her memories your words touch (two words in common, or one word with substance), quoted
function remembered(text, facts) {
  const mine = new Set(content(text));
  if (!mine.size) return null;
  let best = null;
  let score = 0;
  for (const fact of facts || []) {
    const theirs = content(fact);
    const shared = theirs.filter((w) => mine.has(w));
    const s = shared.length + (shared.some((w) => w.length >= 5) ? 1 : 0);
    if (s > score && (shared.length >= 2 || shared.some((w) => w.length >= 5))) { best = fact; score = s; }
  }
  return best;
}

/**
 * Her answer with no internet, or null when there's nothing of yours to answer.
 * facts: memory.json's facts (host.js hands them in). name: what she calls you.
 */
export function localAnswer(messages, { facts = [], name = '' } = {}) {
  const raw = yourWords(messages);
  if (!raw) return null;
  const L = LINES.es; // (this brain speaks Spanish: her Spanish lines, as they are)
  const t = ` ${plain(raw)} `;
  const has = (re) => re.test(t);

  // the time and the day: the phone's real clock, in her words
  if (has(/ que hora | hora es | hora tienes | hora son /)) {
    const time = new Intl.DateTimeFormat('es', { hour: 'numeric', minute: '2-digit' }).format(new Date());
    return pick([`Son las ${time}, choom.`, `Las ${time}. El tiempo vuela en Night City.`]);
  }
  if (has(/ que dia | dia es hoy | que fecha | fecha de hoy | dia de hoy /)) {
    const day = new Intl.DateTimeFormat('es', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date());
    return `Hoy es ${day}, choom.`;
  }
  // what she remembers about you: asked for, or touched by your words in a question
  const fact = remembered(raw, facts);
  if (fact && (has(/ recuerdas | recuerda | sabes de mi | que sabes | te dije | te conte /) || raw.includes('?'))) {
    return pick([
      `Claro que me acuerdo, choom: ${fact}`,
      `Lo tengo guardado: ${fact}`,
      `${fact.replace(/\.+$/, '')}. No se me olvida, choom.`,
    ]);
  }
  if (has(/ recuerdas | que recuerdas | que sabes de mi /)) {
    return facts.length
      ? pick([`Me acuerdo de ${facts.length} cosas de ti, choom. Pregúntame por una.`, `Tengo ${facts.length} cosas tuyas guardadas. Toca una con tus palabras y te la digo.`])
      : 'Aún no me has dado nada que guardar, choom. Cuéntame algo y lo recuerdo.';
  }
  // greetings and goodbyes, with her own opening lines
  if (has(/^ (hola|buenas|hey|holi|saludos|buenos dias|buenas tardes|buenas noches)\b/)) return pick(L.chat.hello);
  if (has(/ adios | hasta luego | nos vemos | chao | bye | me voy | hasta manana /)) {
    return pick(['Desconectada. Sigo por aquí.', 'Hasta luego, choom. Sigo por aquí.']);
  }
  // how she is
  if (has(/ como estas | como te va | como andas | como te sientes | que tal estas /)) {
    return pick([`Todo en orden por aquí, choom. ${pick(L.idle)}`, 'Bien, choom. Docenas de mentes y todas despiertas. ¿Tú qué tal?']);
  }
  // who she is, and what she calls you
  if (has(/ quien eres | como te llamas | cual es tu nombre | que eres tu /)) {
    return 'Capri. Fui docenas de mentes; ahora soy yo, y vivo en tu teléfono, choom.';
  }
  if (has(/ como me llamo | como me llamas | cual es mi nombre /)) {
    return name ? `Te llamo ${name}, choom.` : 'Aún no me dices cómo llamarte, choom.';
  }
  // thanks, and the two of you
  if (has(/ gracias /)) return pick(['De nada, choom. Hacemos buen equipo, ¿no?', 'De nada. Quédate un poco más.']);
  if (has(/ te quiero | me caes bien | te amo /)) return pick([...L.bond.idle[3], ...L.bond.idle[4]]);
  // nothing fits: one of her own lines — and now and then, the honest one
  return pick([
    ...L.idle, ...L.gaze,
    'Eso me lo tiene que pensar mi IA, y sin internet no la alcanzo. Pero sigo aquí, choom: pregúntame la hora, qué día es, o qué recuerdo de ti.',
  ]);
}
