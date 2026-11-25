// (1.13.0) «Ella actúa sola» — Aldo's one button (Su mente in the app; config `actsAlone`, OFF by
// default). While it's ON, and only while it's on:
//   · her eyes stay open: one lasting screen-capture session (Eyes.kt live look; Android shows
//     its cast mark the whole time, and its own dialogs are how the permission is given — the
//     button's first turn-on walks you through Accessibility and the capture, in her words);
//   · every minute or two she looks, and her own brain may decide ONE thing to do on its own —
//     open an app, tap something, scroll, react to what she sees, run an errand she thought of
//     from what she knows of you — through the same ControlService as an order of yours.
// While it's OFF, nothing here runs at all: she only acts when you ask, one action at a time,
// exactly as before. Turning it off is also the emergency stop: this loop dies and her eyes
// close in the same moment.
//
// What she may do alone is yours to narrow in Settings (config: aloneApps / aloneTap /
// aloneScroll / aloneType). What she may NEVER do alone is not a setting: ControlService.kt
// still refuses bank and payment apps, password fields and a locked screen — and, acting
// alone, purchase-looking buttons too (Autonomy.kt / ControlService.actAlone). Everything she
// does alone is written down natively as she does it («Lo que hizo sola», in Su mente).
//
// Honest costs: the lasting capture and the looking use more battery than her usual self, and
// each look is one question to her AI (with her on-phone brain, none leaves the phone).
//
// Wired by host.js: isOn()/limits() read her config, lookLive(on) is Eyes' bridge, snap() a
// picture of the screen, decide(imageId) her brain's one JSON decision (vision first, words
// only when she can't see), act(step) through the native alone-gate (Autonomy.kt), say(line)
// her panel and voice.

const TICK_MIN_MS = 60_000;
const TICK_SPREAD_MS = 50_000;

export function createAutonomy(deps) {
  let running = false;
  let timer = null;
  let hour = []; // when her last alone actions happened (a lid on them: at most 10 an hour)

  const allowed = (what) => {
    const l = deps.limits();
    if (what === 'launch') return l.apps !== false;
    if (what === 'tap') return l.tap !== false;
    if (what === 'down' || what === 'up') return l.scroll !== false;
    if (what === 'type') return l.type === true;
    return false;
  };

  async function tick() {
    if (!running) return;
    try {
      if (!deps.busy()) {
        hour = hour.filter((t) => Date.now() - t < 3_600_000);
        if (hour.length < 10) {
          const image = await deps.snap().catch(() => null);
          if (!running) return;
          const d = await deps.decide(image).catch(() => null);
          if (!running || !d) return;
          if (d.say) deps.say(String(d.say).slice(0, 200));
          const act = String(d.act || 'none');
          if (act !== 'none' && allowed(act)) {
            const step = { what: act, text: String(d.text || '').slice(0, 80) };
            const res = await deps.act(step).catch(() => ({ ok: false }));
            if (res?.ok) hour.push(Date.now());
          }
        }
      }
    } catch {}
    if (running) timer = setTimeout(tick, TICK_MIN_MS + Math.random() * TICK_SPREAD_MS);
  }

  async function start() {
    if (running) return;
    running = true;
    // her eyes first: if you say no to Android's capture dialog, the whole mode turns back off
    const seeing = await deps.lookLive(true).catch(() => false);
    if (!seeing) {
      running = false;
      deps.off('look');
      return;
    }
    if (!deps.isOn()) { // turned off again while the dialog was up
      running = false;
      deps.lookLive(false).catch(() => {});
      return;
    }
    timer = setTimeout(tick, 20_000); // a first look soon, so you see it alive
  }

  function stop() {
    if (!running) return;
    running = false;
    if (timer) clearTimeout(timer);
    timer = null;
    deps.lookLive(false).catch(() => {});
  }

  /** Watches her switch (her config can change from the app, her menu, anywhere): on → start, off → stop. */
  function boot() {
    setInterval(() => {
      if (deps.isOn() && !running) start();
      else if (!deps.isOn() && running) stop();
    }, 3000);
    // you stopped the capture yourself (Android's cast mark): her acting alone ends with it
    deps.onLookStopped?.(() => { if (running) deps.off('look'); });
  }

  return { boot, start, stop, get running() { return running; } };
}
