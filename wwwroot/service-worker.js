const CACHE_NAME = "slide-show-shell-20260922-workflows";
const SHELL_URLS = [
  "/show",
  "/manifest.webmanifest",
  "/assets/playback-state.js?v=20260922-workflows",
  "/assets/slideshow.css?v=20260922-workflows",
  "/assets/slideshow.js?v=20260922-workflows",
  "/assets/offline-crypto.js?v=20260922-workflows",
  "/assets/offline-store.js?v=20260922-workflows",
  "/assets/offline-sync.js?v=20260922-workflows",
  "/assets/darkroom-stage.png"
];

self.addEventListener("install", event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(SHELL_URLS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys
        .filter(key => key !== CACHE_NAME)
        .map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", event => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) {
    return;
  }

  if (url.pathname.startsWith("/api/")) {
    event.respondWith(
      fetch(event.request).catch(() => new Response(
        JSON.stringify({ offline: true }),
        { headers: { "Content-Type": "application/json" } }
      ))
    );
    return;
  }

  if (url.pathname.startsWith("/image/")) {
    return;
  }

  if (url.pathname === "/show") {
    event.respondWith(
      fetch(event.request)
        .then(response => {
          const copy = response.clone();
          caches.open(CACHE_NAME).then(cache => cache.put("/show", copy));
          return response;
        })
        .catch(() => caches.match("/show"))
    );
    return;
  }

  if (event.request.mode === 'navigate') return;
  event.respondWith(
    caches.match(event.request)
      .then(cached => cached || fetch(event.request).then(response => {
        const copy = response.clone();
        caches.open(CACHE_NAME).then(cache => cache.put(event.request, copy));
        return response;
      }))
  );
});
