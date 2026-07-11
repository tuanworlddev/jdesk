plugins {
    id("jdesk.library-conventions")
    id("jdesk.coverage-conventions")
}
description = "JDesk platform SPI: window, WebView, dispatcher contracts."
dependencies { api(project(":modules:jdesk-api")) }
