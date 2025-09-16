(() => {
  // src/index.js
  if (false) {
    new EventSource("/esbuild").addEventListener("change", () => location.reload());
  }
})();
