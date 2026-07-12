# Enterprise managed policy

Set the JVM property **jdesk.policy.file** to an absolute managed JSON file. The policy
applies restrictions that an application cannot widen with another JDesk property.

Example:

    {
      "version": 1,
      "devToolsAllowed": false,
      "automationAllowed": false,
      "consoleForwardingAllowed": false,
      "externalBrowserAllowed": true
    }

All fields are optional except version; omitted booleans default to true. A policy can only
disable a requested feature. It cannot turn on DevTools, automation, console capture or
external navigation by itself.

The parser rejects unknown fields, wrong types, symlinks, non-regular files and files over
64 KiB. Malformed policy fails application startup. On managed machines, deploy the file
read-only using Intune, SCCM, Jamf, configuration management or the Linux package and pass
the JVM property through the packaged launcher.

Update policy is separate because it is also used by a bootstrap process. Configure it
with the jdesk.update properties documented in
[Updating applications](updating-applications.md).
