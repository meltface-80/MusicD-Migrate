/*
 * The only thing this lint is for is no-undef.
 *
 * public/app.js has no build step, so nothing else in the repository would
 * notice a call to a function that no longer exists — `node --check` parses
 * the file, it does not resolve names. MusicD Remote Lite shipped that bug
 * twice, and the second time it took the settings navigation with it and
 * Settings stopped opening. The file parsed cleanly both times.
 *
 * The browser globals are listed here rather than pulled from the `globals`
 * package, so this config needs no dependency of its own beyond eslint itself
 * — and a config that resolves an import is a config that has to live next to
 * a node_modules, which is how the first version of this failed in CI.
 */
export default [
  {
    files: ["public/*.js"],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "script",
      globals: Object.fromEntries([
        "window", "document", "navigator", "location", "console",
        "fetch", "Headers", "Request", "Response", "URL", "URLSearchParams",
        "localStorage", "sessionStorage",
        "setTimeout", "clearTimeout", "setInterval", "clearInterval",
        "requestAnimationFrame", "cancelAnimationFrame",
        "alert", "confirm", "prompt",
      ].map((name) => [name, "readonly"])),
    },
    rules: {
      "no-undef": "error",
      // A caught error this file deliberately ignores is the normal case
      // here — localStorage throwing in a private window, a clipboard write
      // refused on a plain-http origin — and each one carries a comment
      // saying so. Warning about them would train the reader to ignore the
      // output, which is worse than not warning.
      "no-unused-vars": ["warn", { args: "none", caughtErrors: "none" }],
    },
  },
];
