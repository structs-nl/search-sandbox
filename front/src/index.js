

if (DEV) {
    new EventSource('/esbuild').addEventListener('change', () => location.reload())
}
