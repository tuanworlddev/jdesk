plugins { id("jdesk.library-conventions") }
description = "JDesk annotation processor: command registry, metadata, TypeScript client generation."
dependencies { implementation(project(":modules:jdesk-api")) }
