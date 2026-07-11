# jdesk-cli

The JDesk project generator. Build the distribution with:

```bash
./gradlew :modules:jdesk-cli:installDist
modules/jdesk-cli/build/install/jdesk/bin/jdesk --help
```

Create a project from published artifacts:

```bash
jdesk create my-app --package com.example.myapp
jdesk create my-suite --template structured --package com.example.mysuite
```

While developing JDesk itself, pass `--jdesk-source /path/to/JDesk`. This adds the
checkout as both a plugin and dependency composite build, so the generated project can be
compiled before framework artifacts are published.
