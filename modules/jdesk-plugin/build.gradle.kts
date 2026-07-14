plugins { id("jdesk.library-conventions") }
description = "JDesk plugin model: signed, integrity-checked, capability-gated third-party plugins."

dependencies {
    implementation(libs.jackson.databind)
}
