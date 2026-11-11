// Troupe page bridge, served as vitrine://troupe.<resource>/troupe.js:
//   await troupe.call(name, data)  runs the Lua view:handle(name, fn) and resolves with what fn returns
//   troupe.emit(name, data)        the same, without waiting
//   troupe.on(name, fn)            receives view:send(name, data) from Lua
(() => {
  const listeners = {};
  const post = (name, data) =>
    fetch(`vitrine://${location.host}/${name}`, { method: 'POST', body: JSON.stringify(data === undefined ? null : data) });

  window.troupe = {
    async call(name, data) {
      const r = await post(name, data);
      if (!r.ok) throw new Error(await r.text());
      return r.json();
    },
    emit(name, data) {
      post(name, data).catch(() => {});
    },
    on(name, fn) {
      (listeners[name] = listeners[name] || []).push(fn);
    },
  };

  addEventListener('message', (e) => {
    const m = e.data;
    if (m && typeof m === 'object' && typeof m.troupe === 'string') {
      for (const fn of listeners[m.troupe] || []) fn(m.data);
    }
  });
})();
