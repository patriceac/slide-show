(() => {
  const key = "slideshow.theme";
  const system = window.matchMedia("(prefers-color-scheme: dark)");
  const normalize = value => value === "light" || value === "dark" ? value : "system";
  let preference = "system";
  try { preference = normalize(localStorage.getItem(key)); } catch {}

  function apply() {
    const theme = preference === "system" ? (system.matches ? "dark" : "light") : preference;
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", theme === "dark" ? "#171c24" : "#fafaf9");
    for (const select of document.querySelectorAll("[data-theme-select]")) select.value = preference;
  }

  apply();
  document.addEventListener("DOMContentLoaded", () => {
    apply();
    for (const select of document.querySelectorAll("[data-theme-select]")) {
      select.addEventListener("change", () => {
        preference = normalize(select.value);
        try { localStorage.setItem(key, preference); } catch {}
        apply();
      });
    }
  });
  system.addEventListener("change", apply);
  window.addEventListener("storage", event => {
    if (event.key === key || event.key === null) {
      preference = normalize(event.newValue);
      apply();
    }
  });
})();
