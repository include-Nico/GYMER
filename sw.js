/* GYMER service worker: offline shell + notifiche timer */
const CACHE = "gymer-v8";
const SHELL = ["/", "/manifest.webmanifest", "/icons/icon-192.png", "/icons/icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
  );
  self.clients.claim();
});

// Stale-while-revalidate per le richieste GET della stessa origine
self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET" || new URL(req.url).origin !== location.origin) return;
  e.respondWith(
    caches.match(req).then((cached) => {
      const net = fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy));
          return res;
        })
        .catch(() => cached || caches.match("/"));
      return cached || net;
    })
  );
});

/* ---------- Timer di recupero: notifica "in corso" con azioni + avviso di fine ----------
   Stato inviato dalla pagina ("T"), aggiornato dal SW ogni 10s e dai pulsanti della notifica.
   Lo stato vive anche nei dati della notifica, cosi' i pulsanti funzionano se il SW viene riavviato. */
const TAG = "gymer-timer", ICON = "/icons/icon-192.png";
let S = null, loopT = null, loopRes = null, shownAt = 0;
const fm = (s) => { const c = Math.ceil(s); return Math.floor(c / 60) + ":" + String(c % 60).padStart(2, "0"); };
const hm = (t) => new Date(t).toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const left = () => (S.run ? Math.max(0, (S.end - Date.now()) / 1000) : S.left);
const st = () => ({ run: S.run, end: S.end, left: S.left, total: S.total, u: S.u });

function stopLoop() {
  if (loopT) clearTimeout(loopT);
  loopT = null;
  if (loopRes) { loopRes(); loopRes = null; }
}
function showRun() {
  if (!S) return Promise.resolve();
  if (!S.hid) return closeRun();            // app in primo piano: nessuna notifica
  const run = S.run, l = left();
  return self.registration.showNotification(run ? "Recupero in corso" : "Recupero in pausa", {
    body: run ? "Finisce alle " + hm(S.end) : "In pausa · " + fm(l) + " rimanenti",
    tag: TAG, renotify: false, silent: true, vibrate: [], requireInteraction: true,
    icon: ICON, badge: ICON,
    data: { k: run ? "run" : "pause", st: st(), o: S.o },
    actions: run
      ? [{ action: "pause", title: "Pausa" }, { action: "add", title: "+30s" }, { action: "stop", title: "Stop" }]
      : [{ action: "resume", title: "Riprendi" }, { action: "add", title: "+30s" }, { action: "stop", title: "Stop" }],
  }).catch(() => {});
}
function showDone(at, o) {
  if (at && shownAt === at) return Promise.resolve();
  shownAt = at || Date.now();
  return self.registration.showNotification("GYMER", Object.assign({
    body: "Recupero finito: tocca a te!", icon: ICON, badge: ICON, tag: TAG, renotify: true, data: { k: "done" },
  }, o || {})).catch(() => {});
}
function startLoop() {
  stopLoop();
  return new Promise((res) => {
    loopRes = res;
    let shownRun = false;
    const step = () => {
      if (!S || !S.run) { stopLoop(); return; }
      const l = left();
      if (l <= 0) {
        S.run = false; S.left = 0;
        (S.hid ? showDone(S.end, S.o) : Promise.resolve()).then(() => stopLoop());
        return;
      }
      if (!shownRun) { shownRun = true; showRun(); }
      loopT = setTimeout(step, l * 1000 + 30);
    };
    step();
  });
}
function closeRun() {
  return self.registration.getNotifications({ tag: TAG }).then((l) =>
    l.forEach((n) => { const k = n.data && n.data.k; if (k === "run" || k === "pause") n.close(); })
  ).catch(() => {});
}
function broadcast(except) {
  return self.clients.matchAll({ includeUncontrolled: true, type: "window" }).then((l) =>
    l.forEach((c) => c.postMessage(Object.assign({ type: "TS" }, st())))
  );
}
function restore() {
  if (S) return Promise.resolve();
  return self.registration.getNotifications({ tag: TAG }).then((l) => {
    const n = l.find((x) => x.data && x.data.st && (x.data.k === "run" || x.data.k === "pause"));
    if (n) S = Object.assign({}, n.data.st, { o: n.data.o });
  }).catch(() => {});
}

self.addEventListener("message", (e) => {
  const d = e.data || {};
  if (d.type === "T") {
    if (S && S.u && d.u < S.u) return;
    S = { run: d.run, end: d.end, left: d.left, total: d.total, u: d.u, o: d.o, hid: !!d.hid };
    if (S.run) e.waitUntil(S.hid ? startLoop() : closeRun().then(startLoop));
    else {
      stopLoop();
      if (S.left >= S.total - 0.05) e.waitUntil(closeRun());       // reset: toglie la notifica
      else if (S.left > 0) e.waitUntil(showRun());                  // pausa (o chiude se in primo piano)
    }
  } else if (d.type === "TD") {
    stopLoop();
    if (S) { S.run = false; S.left = 0; }
    e.waitUntil(showDone(d.at, d.o));
  } else if (d.type === "V") {
    if (S) { S.hid = !!d.hid; e.waitUntil(S.hid ? (S.run ? startLoop() : showRun()) : closeRun()); }
  } else if (d.type === "TG") {
    e.waitUntil(restore().then(() => { if (S && e.source) e.source.postMessage(Object.assign({ type: "TS" }, st())); }));
  }
});

self.addEventListener("notificationclick", (e) => {
  const n = e.notification, a = e.action, data = n.data || {};
  if (a && data.st) {
    const now = Date.now();
    S = Object.assign({}, data.st, { o: (S && S.o) || data.o });
    if (a === "pause") { S.left = Math.max(0, (S.end - now) / 1000); S.run = false; }
    else if (a === "resume") { S.end = now + S.left * 1000; S.run = true; }
    else if (a === "add") { if (S.run) S.end += 30000; else S.left += 30; S.total += 30; }
    else if (a === "stop") { S.run = false; S.left = S.total; }
    S.u = now; S.hid = true;
    n.close();
    e.waitUntil(broadcast().then(() => {
      if (a === "stop") { stopLoop(); return; }
      return S.run ? startLoop() : (stopLoop(), showRun());
    }));
    return;
  }
  n.close();
  e.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      const c = list.find((x) => "focus" in x);
      return c ? c.focus() : self.clients.openWindow("/");
    })
  );
});
