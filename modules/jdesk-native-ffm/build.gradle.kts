plugins {
    id("jdesk.library-conventions")
    id("jdesk.coverage-conventions")
}
description = "Shared FFM support: arenas, callback registry, handle state machine."
dependencies { api(project(":modules:jdesk-api")) }
