// (1.14.0) Her adjustable personality and her simulated mood — two light layers over how she
// talks, nothing more. The personality is six sliders of yours in the app (Su mente), kept in
// her config (personaWarmth…personaInitiative, personaOn): this module turns them into ONE
// brief tone guide in her prompt. Her personality, her lines and her memory are not touched;
// at the sliders' defaults the guide describes how she already talks, and with the switch
// off nothing is added at all.
//
// Her mood is a SIMULATED state (moodSimOn): three numbers (energy, confidence, curiosity)
// and a label, nudged by simple heuristics of the conversation — a thank-you lifts her
// energy, laughter too, long careful answers settle her confidence, dry one-word answers
// lower her energy — and always drifting back toward neutral. It is kept in memory.json
// (moodSim), shown in Su mente next to her neurons, and added to her prompt as one short
// line of tone. It is a program state, never feelings: the line itself says so, and the app
// says so where it shows it.
//
// Same pattern as memory.js: wraps chat.personal (what her brain is told) and chat.remember
// (each message, as it lands). Pure enough to test with Node and a fake chat.

const NEUTRAL = { energy: 60, confidence: 50, curiosity: 55 };

const band = (v, low, mid, high) => (v < 34 ? low : v > 66 ? high : mid);

/** The sliders as words — ranges, never the raw numbers, so the guide reads like a tone. */
export function personaGuide(c) {
  const n = (k, d) => Math.min(100, Math.max(0, Number(c?.[k] ?? d) || 0));
  const parts = [
    band(n('personaWarmth', 70), 'un poco más distante y directa', 'cercana', 'muy cálida y cercana'),
    band(n('personaCuriosity', 65), 'poco preguntona', 'curiosa', 'muy curiosa: pregunta y explora'),
    band(n('personaCreativity', 60), 'práctica y literal', 'con ideas propias', 'muy creativa y juguetona con las ideas'),
    band(n('personaFormality', 30), 'informal y suelta', 'natural', 'más formal y cuidada'),
    band(n('personaHumor', 45), 'seria, casi sin bromas', 'con humor ligero', 'bromista: busca el chiste sano'),
    band(n('personaInitiative', 50), 'espera a que le pidan', 'propone a veces', 'propone y sugiere mucho por su cuenta'),
  ];
  return `(Guía de tono que te puso la persona: sé ${parts.join(', ')}. Es solo un acento, una inclinación: sigue siendo tú, con tu personalidad y tus frases de siempre.)`;
}

/** The label her numbers wear in Su mente: plain words, from the strongest of the three. */
export function moodLabel(m) {
  if (m.energy >= 68 && m.confidence >= 55) return 'animada';
  if (m.curiosity >= 68) return 'curiosa';
  if (m.energy <= 38) return 'tranquila';
  if (m.confidence <= 32) return 'tímida';
  if (m.confidence >= 70) return 'segura';
  return 'serena';
}

const clamp = (v) => Math.min(100, Math.max(0, Math.round(v)));

/**
 * One message's nudge to her simulated mood. Pure: (mood, message) → mood.
 * message: { role: 'user' | 'assistant', content } — the same chat.remember sees.
 */
export function nudgeMood(mood, message) {
  const m = { ...NEUTRAL, ...(mood || {}) };
  // everything drifts a little toward neutral each message: no mood sticks forever
  for (const k of Object.keys(NEUTRAL)) m[k] += (NEUTRAL[k] - m[k]) * 0.08;
  const text = String(message?.content || '');
  const low = text.toLowerCase();
  if (message?.role === 'user') {
    if (/gracias|thank|merci|obrigad/.test(low)) { m.energy += 6; m.confidence += 3; }
    if (/jaja|jeje|😂|🤣|haha/.test(low)) { m.energy += 4; m.curiosity += 1; }
    if (/\?|¿/.test(text)) m.curiosity += 2;                       // they ask: she leans in
    if (text.length > 220) { m.confidence += 2; m.curiosity += 2; } // a long, told-with-care message
    if (text.trim().length > 0 && text.trim().length <= 3) m.energy -= 3; // a dry «ok» cools it
    if (/aburrid|qué mal|que mal|triste|enojad|molest/.test(low)) { m.energy -= 4; m.confidence -= 2; }
    if (/genial|increíble|increible|me encanta|excelente|perfecto/.test(low)) { m.energy += 5; m.confidence += 2; }
  } else if (message?.role === 'assistant') {
    if (text.length > 400) { m.confidence += 2; m.curiosity += 1; } // a long answer of hers: focus
  }
  m.energy = clamp(m.energy); m.confidence = clamp(m.confidence); m.curiosity = clamp(m.curiosity);
  m.label = moodLabel(m);
  return m;
}

export function personaTuning({ cfg, readMemory, saveMemory }) {
  const dev = window.dev;
  const chat = dev?.chat;
  if (!chat) return;

  // her tone guide and her simulated mood, in what her brain is told (chat.personal)
  const personal = chat.personal.bind(chat);
  chat.personal = () => {
    const lines = personal();
    const c = cfg();
    if (c.personaOn !== false) lines.push(personaGuide(c));
    if (c.moodSimOn !== false) {
      const m = readMemory().moodSim;
      if (m && typeof m.energy === 'number') {
        lines.push(`(Tu estado de ánimo simulado ahora — un estado del programa, no sentimientos: ${m.label || moodLabel(m)}, energía ${m.energy}/100, confianza ${m.confidence}/100, curiosidad ${m.curiosity}/100. Que tiña tu tono apenas un poco, nada más.)`);
      }
    }
    return lines;
  };

  // her simulated mood, nudged by each message as it lands (chat.remember)
  const remember = chat.remember.bind(chat);
  chat.remember = (message) => {
    const out = remember(message);
    try {
      if (cfg().moodSimOn !== false && (message?.role === 'user' || message?.role === 'assistant')) {
        saveMemory({ moodSim: { ...nudgeMood(readMemory().moodSim, message), at: Date.now() } });
      }
    } catch {}
    return out;
  };

  return { personaGuide, nudgeMood };
}
