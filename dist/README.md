# dist/

The published APK lands here, committed by the `apk` job in
`.github/workflows/ci.yml` once per `versionName` — so the README can link
straight at a file rather than sending people through the Actions UI.

`latest.json` is written by the same job that produced the APK, so the two
cannot disagree about which version is published.

Nothing here is edited by hand.
