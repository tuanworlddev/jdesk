/**
 * Shared FFM support: handle lifecycle, callback pinning, arena ownership. Only this
 * module and platform modules receive {@code --enable-native-access}.
 */
module dev.jdesk.ffm {
    requires transitive dev.jdesk.api;
    exports dev.jdesk.ffm;
}
