# @PROJECT_NAME@

Generated with JDesk's `structured` template. The project separates domain, application,
infrastructure, desktop composition, and frontend code.

```bash
./gradlew classes
npm install --prefix ui
./gradlew :desktop:jdeskDev
```

Vite supplies frontend HMR. Java and resource changes under `desktop` are rebuilt and
restart the desktop process after a successful compile.
