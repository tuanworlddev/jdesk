# @PROJECT_NAME@

Generated with JDesk's `structured` template. The project separates domain, application,
infrastructure, desktop composition, and frontend code. The UI is plain HTML/CSS/JS —
no Node or bundler required.

```bash
./gradlew :desktop:run       # build the UI and launch the app
./gradlew :desktop:jdeskDev  # dev loop: reloads the page on UI changes
```

In the dev loop, edits under `ui/` are rebuilt and the window reloads automatically.
Java and resource changes under the listed reload sources are rebuilt and restart the
desktop process after a successful compile.
