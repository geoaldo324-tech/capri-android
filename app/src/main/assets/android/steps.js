// (1.13.0) Orders of several steps: "abre YouTube y busca música para estudiar", "ve al inicio y
// baja" — she splits the order into steps and does them one by one through the same
// ControlService as a single order (control.js), stopping at the first step that fails and
// telling you what she did. The safety doesn't move an inch: the steps run through
// ControlService.kt, which still refuses bank/payment apps, password fields and a locked
// screen, and Settings has a switch to turn this whole mode off (config `controlSteps`).
//
// Who splits: with a real AI, her own brain (host.js plans it as JSON, in the same small
// vocabulary); with her brain on this phone (no internet), only simple orders she can already
// recognise, split on "y" / "luego" / commas (splitLocally below) — anything she doesn't
// recognise stops the plan before it starts, she never guesses a tap.
//
// A step: { what: 'launch', text: <app name> } | { what: 'search', text: <what to look for> } |
// { what: <a control.js action>, text } — back, home, recents, notifications, quick,
// screenshot, down, up, tap (text = the label), type (text = what to type).

import { controlMatch } from './control.js';

const CONNECT = /\s+(?:y|e|luego|despu[eé]s|entonces|then|and then)\s+|,\s*/iu;
const LAUNCH = /^(?:abre|abrir|abr[ií]|open|entra(?:r)?(?: a| en)?|pon(?:me)?|lanza)\s+(?:la |el |los |las |una |un |the |a |an )?(.+)$/iu;
const SEARCH = /^(?:busca|buscar|search|look up|encuentra|find)\s+(?:me\s+)?(.+)$/iu;

/** Does this read like a several-step order at all? (One verb is control.js's, not this.) */
export function looksLikeTask(text) {
  const said = String(text || '').trim();
  if (!said || said.split(/\s+/).length > 24) return false;
  if (!LAUNCH.test(said) && !/^(?:busca|buscar)\b/iu.test(said)) return false;
  return CONNECT.test(said) || SEARCH.test(said);
}

/**
 * The no-brain splitter: an order → its steps, or null when any piece isn't one she already
 * knows how to do. "abre YouTube y busca jazz" → [launch YouTube, search jazz].
 */
export function splitLocally(text) {
  const said = String(text || '').trim().replace(/[.!¡¿?]+$/u, '');
  const chunks = said.split(CONNECT).map((c) => c.trim()).filter(Boolean);
  if (chunks.length < 2) return null;
  const steps = [];
  for (const chunk of chunks) {
    // something she already does alone ("abre las notificaciones") is a control step, not an app
    const known = controlMatch(chunk);
    if (known) { steps.push(known); continue; }
    const launch = LAUNCH.exec(chunk);
    if (launch && launch[1].split(/\s+/).length <= 4) { steps.push({ what: 'launch', text: launch[1].trim() }); continue; }
    const search = SEARCH.exec(chunk);
    if (search) { steps.push({ what: 'search', text: search[1].trim() }); continue; }
    return null; // a piece she doesn't know: the whole order stops here, untried
  }
  return steps.length >= 2 ? steps : null;
}

/** What a plan from her brain may contain — anything else in it is dropped, never run. */
export function cleanPlan(raw) {
  if (!Array.isArray(raw)) return null;
  const ok = new Set(['launch', 'search', 'back', 'home', 'recents', 'notifications', 'quick', 'screenshot', 'down', 'up', 'tap', 'type']);
  const steps = raw
    .filter((s) => s && ok.has(s.what) && (s.what === 'back' || s.what === 'home' || s.what === 'down' || s.what === 'up' || s.what === 'recents' || s.what === 'notifications' || s.what === 'quick' || s.what === 'screenshot' || String(s.text || '').trim()))
    .map((s) => ({ what: s.what, text: String(s.text || '').trim().slice(0, 80) }))
    .slice(0, 6);
  return steps.length ? steps : null;
}

const L = {
  es: {
    doing: 'Voy paso a paso: {n} cosas.',
    done: 'Listo, hice todo: {list}.',
    part: 'Hice {done} de {n}. Me detuve en «{step}»: {why}.',
    none: 'No supe partir eso en pasos que conozca. Pídeme una cosa a la vez.',
    off: 'Los pasos están apagados en Ajustes.',
    noControl: 'Para eso necesito permiso: actívame en Accesibilidad › Capri usa tu celular.',
    stepName: { launch: 'abrir {x}', search: 'buscar {x}', tap: 'tocar «{x}»', type: 'escribir', back: 'ir atrás', home: 'ir al inicio', down: 'bajar', up: 'subir', recents: 'apps recientes', notifications: 'notificaciones', quick: 'ajustes rápidos', screenshot: 'captura' },
  },
  en: {
    doing: 'Step by step: {n} things.',
    done: 'Done, all of it: {list}.',
    part: 'I did {done} of {n}. I stopped at “{step}”: {why}.',
    none: 'I couldn’t split that into steps I know. Ask me one thing at a time.',
    off: 'Steps are off in Settings.',
    noControl: 'I need permission for that: turn on Accessibility › Capri uses your phone.',
    stepName: { launch: 'open {x}', search: 'search {x}', tap: 'tap “{x}”', type: 'type', back: 'go back', home: 'go home', down: 'scroll down', up: 'scroll up', recents: 'recent apps', notifications: 'notifications', quick: 'quick settings', screenshot: 'screenshot' },
  },
};

/**
 * The runner, wired by host.js: plan() asks her brain (null with her offline brain),
 * launch(name) opens an app by name, doStep(step) runs one control step and answers
 * { ok, why } ('off' when her control is off), say(line) is her telling you.
 */
export function createSteps(deps) {
  const name = (l, s) => (l.stepName[s.what] || s.what).replace('{x}', s.text || '');

  async function run(steps, l, say = deps.say) {
    say(l.doing.replace('{n}', String(steps.length)));
    const doneNames = [];
    for (const step of steps) {
      let res;
      if (step.what === 'launch') {
        res = (await deps.launch(step.text)) ? { ok: true } : { ok: false, why: 'notFound' };
      } else if (step.what === 'search') {
        // the app's own search box first ("Buscar" / "Search"), then what you asked for
        res = await deps.doStep({ what: 'tap', text: 'Buscar' });
        if (!res.ok && res.why === 'notFound') res = await deps.doStep({ what: 'tap', text: 'Search' });
        if (res.ok) {
          await new Promise((r) => setTimeout(r, 600));
          res = await deps.doStep({ what: 'type', text: step.text });
        }
      } else {
        res = await deps.doStep(step);
      }
      if (!res.ok) {
        if (res.why === 'off') { say(l.noControl); return true; }
        say(l.part.replace('{done}', String(doneNames.length)).replace('{n}', String(steps.length)).replace('{step}', name(l, step)).replace('{why}', res.why || ''));
        return true;
      }
      doneNames.push(name(l, step));
      await new Promise((r) => setTimeout(r, 900)); // let the screen settle before the next one
    }
    say(l.done.replace('{list}', doneNames.join(', ')));
    return true;
  }

  /** Try this text as a several-step order. True when it was one (said or not).
   *  `sayOverride`: tell it somewhere else than her panel (the app's chat thread). */
  async function tryTask(text, sayOverride) {
    if (!deps.enabled()) return false;
    if (!looksLikeTask(text)) return false;
    const l = L[deps.lang?.()] || L.es;
    const say = sayOverride || deps.say;
    let steps = null;
    try { steps = cleanPlan(await deps.plan(text)); } catch { steps = null; }
    if (!steps) steps = splitLocally(text);
    if (!steps) { say(l.none); return true; }
    return run(steps, l, say);
  }

  return { tryTask, run };
}
