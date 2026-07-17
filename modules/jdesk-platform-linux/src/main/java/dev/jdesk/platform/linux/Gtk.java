package dev.jdesk.platform.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * GTK 3 / GLib / WebKitGTK 4.1 access through FFM. Public, documented C APIs only.
 *
 * <p>ABI notes (x86_64/aarch64 Linux, glibc): {@code gboolean}/{@code gint} are 32-bit
 * ({@link java.lang.foreign.ValueLayout#JAVA_INT}); {@code gulong} (GLib signal handler
 * ids), {@code gsize}, {@code gssize} and {@code gint64} are 64-bit
 * ({@link java.lang.foreign.ValueLayout#JAVA_LONG}); all object, string, and callback
 * parameters are pointers ({@link java.lang.foreign.ValueLayout#ADDRESS}). Enum values
 * are hard-coded from the WebKitGTK 4.1 / GTK 3 headers with the declaration cited at
 * each constant.
 */
final class Gtk {
    // WebKitWebsiteDataTypes (public flags in WebKitWebsiteData.h).
    static final int WEBKIT_WEBSITE_DATA_MEMORY_CACHE = 1 << 0;
    static final int WEBKIT_WEBSITE_DATA_DISK_CACHE = 1 << 1;
    static final int WEBKIT_WEBSITE_DATA_LOCAL_STORAGE = 1 << 4;
    static final int WEBKIT_WEBSITE_DATA_COOKIES = 1 << 8;
    static final Linker LINKER = Linker.nativeLinker();

    /** Process-lifetime arena owning the library lookups (never closed by design). */
    private static final Arena RUNTIME_ARENA = Arena.ofShared();

    /**
     * Process-lifetime arena for upcall stubs shared across windows (signal trampolines,
     * GSourceFunc/GAsyncReadyCallback stubs). The stubs are class-level singletons that
     * dispatch through peer/token maps, so this arena is never closed — GLib may hold a
     * queued source or in-flight async result at any teardown point.
     */
    static final Arena CALLBACK_ARENA = Arena.ofShared();

    private static final SymbolLookup LOOKUP =
            SymbolLookup.libraryLookup("libglib-2.0.so.0", RUNTIME_ARENA)
                    .or(SymbolLookup.libraryLookup("libgobject-2.0.so.0", RUNTIME_ARENA))
                    .or(SymbolLookup.libraryLookup("libgio-2.0.so.0", RUNTIME_ARENA))
                    .or(SymbolLookup.libraryLookup("libgtk-3.so.0", RUNTIME_ARENA))
                    .or(SymbolLookup.libraryLookup("libgdk-3.so.0", RUNTIME_ARENA))
                    .or(SymbolLookup.libraryLookup("libsoup-3.0.so.0", RUNTIME_ARENA))
                    .or(SymbolLookup.libraryLookup("libjavascriptcoregtk-4.1.so.0",
                            RUNTIME_ARENA))
                    .or(SymbolLookup.libraryLookup("libwebkit2gtk-4.1.so.0", RUNTIME_ARENA))
                    .or(SymbolLookup.libraryLookup("libcairo.so.2", RUNTIME_ARENA));

    // ---- GTK 3 enums (gtk/gtkwindow.h) ----
    static final int GTK_WINDOW_TOPLEVEL = 0;

    // ---- WebKitGTK 4.1 enums ----
    // webkit2/WebKitWebView.h: WebKitLoadEvent
    static final int WEBKIT_LOAD_COMMITTED = 2;
    // webkit2/WebKitPolicyDecision.h: WebKitPolicyDecisionType
    static final int WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION = 0;
    static final int WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION = 1;
    static final int WEBKIT_POLICY_DECISION_TYPE_RESPONSE = 2;
    // webkit2/WebKitUserContent.h: WebKitUserContentInjectedFrames
    static final int WEBKIT_USER_CONTENT_INJECT_TOP_FRAME = 1;
    // webkit2/WebKitUserContent.h: WebKitUserScriptInjectionTime
    static final int WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START = 0;
    // webkit2/WebKitWebView.h: WebKitSnapshotRegion / WebKitSnapshotOptions
    static final int WEBKIT_SNAPSHOT_REGION_VISIBLE = 0;
    static final int WEBKIT_SNAPSHOT_OPTIONS_NONE = 0;
    // libsoup/soup-message-headers.h: SoupMessageHeadersType
    static final int SOUP_MESSAGE_HEADERS_RESPONSE = 1;
    // glib/gmain.h
    static final int G_PRIORITY_DEFAULT = 0;
    static final int G_SOURCE_REMOVE = 0;
    // cairo/cairo.h: cairo_status_t
    static final int CAIRO_STATUS_SUCCESS = 0;
    static final int CAIRO_STATUS_WRITE_ERROR = 11;

    // ---- GLib ----
    static final MethodHandle GTK_SETTINGS_GET_DEFAULT = dl("gtk_settings_get_default",
            FunctionDescriptor.of(ADDRESS));
    // gdk-pixbuf + window icon
    static final MethodHandle GDK_PIXBUF_NEW_FROM_STREAM = dl("gdk_pixbuf_new_from_stream",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle GTK_WINDOW_SET_DEFAULT_ICON = dl("gtk_window_set_default_icon",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WINDOW_SET_ICON = dl("gtk_window_set_icon",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    // GtkStatusIcon (tray) — deprecated but functional on X11 desktops
    static final MethodHandle GTK_STATUS_ICON_NEW = dl("gtk_status_icon_new",
            FunctionDescriptor.of(ADDRESS));
    static final MethodHandle GTK_STATUS_ICON_SET_FROM_PIXBUF = dl(
            "gtk_status_icon_set_from_pixbuf", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GTK_STATUS_ICON_SET_TITLE = dl("gtk_status_icon_set_title",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GTK_STATUS_ICON_SET_TOOLTIP_TEXT = dl(
            "gtk_status_icon_set_tooltip_text", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GTK_STATUS_ICON_SET_VISIBLE = dl("gtk_status_icon_set_visible",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    // GtkMenu (context / tray menus)
    static final MethodHandle GTK_MENU_NEW = dl("gtk_menu_new", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle GTK_MENU_ITEM_NEW_WITH_LABEL = dl(
            "gtk_menu_item_new_with_label", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle GTK_SEPARATOR_MENU_ITEM_NEW = dl(
            "gtk_separator_menu_item_new", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle GTK_MENU_SHELL_APPEND = dl("gtk_menu_shell_append",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GTK_MENU_ITEM_SET_SUBMENU = dl("gtk_menu_item_set_submenu",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GTK_CHECK_MENU_ITEM_NEW_WITH_LABEL = dl(
            "gtk_check_menu_item_new_with_label", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle GTK_CHECK_MENU_ITEM_SET_ACTIVE = dl(
            "gtk_check_menu_item_set_active", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle GTK_WIDGET_SET_SENSITIVE = dl("gtk_widget_set_sensitive",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle GTK_MENU_POPUP_AT_POINTER = dl("gtk_menu_popup_at_pointer",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GTK_WIDGET_SHOW_ALL2 = dl("gtk_widget_show_all",
            FunctionDescriptor.ofVoid(ADDRESS));
    // drag-and-drop destination
    static final MethodHandle GTK_DRAG_DEST_SET = dl("gtk_drag_dest_set",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle GTK_DRAG_DEST_ADD_URI_TARGETS = dl(
            "gtk_drag_dest_add_uri_targets", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_SELECTION_DATA_GET_URIS = dl(
            "gtk_selection_data_get_uris", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle G_STRFREEV = dl("g_strfreev", FunctionDescriptor.ofVoid(ADDRESS));
    // void g_object_get(gpointer, const gchar* first_property, ..., NULL) — variadic.
    static final MethodHandle G_OBJECT_GET = LINKER.downcallHandle(
            LOOKUP.findOrThrow("g_object_get"),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS),
            Linker.Option.firstVariadicArg(1));
    static final MethodHandle G_FREE = dl("g_free",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle G_LIST_FREE = dl("g_list_free",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle G_DATE_TIME_NEW_FROM_UNIX_UTC = dl("g_date_time_new_from_unix_utc",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    static final MethodHandle G_DATE_TIME_TO_UNIX = dl("g_date_time_to_unix",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle G_DATE_TIME_UNREF = dl("g_date_time_unref",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle G_MAIN_CONTEXT_INVOKE_FULL = dl("g_main_context_invoke_full",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle G_BYTES_NEW = dl("g_bytes_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle G_BYTES_UNREF = dl("g_bytes_unref",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle G_QUARK_FROM_STRING = dl("g_quark_from_string",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle G_ERROR_NEW_LITERAL = dl("g_error_new_literal",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle G_ERROR_FREE = dl("g_error_free",
            FunctionDescriptor.ofVoid(ADDRESS));

    // ---- GObject ----
    // gulong g_signal_connect_data(gpointer, const gchar*, GCallback, gpointer,
    //                              GClosureNotify, GConnectFlags)
    static final MethodHandle G_SIGNAL_CONNECT_DATA = dl("g_signal_connect_data",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                    JAVA_INT));
    static final MethodHandle G_SIGNAL_HANDLER_DISCONNECT = dl("g_signal_handler_disconnect",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
    static final MethodHandle G_OBJECT_REF = dl("g_object_ref",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle G_OBJECT_REF_SINK = dl("g_object_ref_sink",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle G_OBJECT_UNREF = dl("g_object_unref",
            FunctionDescriptor.ofVoid(ADDRESS));

    // ---- GIO ----
    static final MethodHandle G_MEMORY_INPUT_STREAM_NEW_FROM_BYTES =
            dl("g_memory_input_stream_new_from_bytes",
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle G_APP_INFO_LAUNCH_DEFAULT_FOR_URI = dl(
            "g_app_info_launch_default_for_uri",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    // ---- GTK 3 ----
    static final MethodHandle GTK_INIT_CHECK = dl("gtk_init_check",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle GTK_MAIN = dl("gtk_main", FunctionDescriptor.ofVoid());
    static final MethodHandle GTK_MAIN_QUIT = dl("gtk_main_quit", FunctionDescriptor.ofVoid());
    static final MethodHandle GTK_WINDOW_NEW = dl("gtk_window_new",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));
    static final MethodHandle GTK_WINDOW_SET_TITLE = dl("gtk_window_set_title",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GTK_WINDOW_SET_DEFAULT_SIZE = dl("gtk_window_set_default_size",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle GTK_WINDOW_GET_SIZE = dl("gtk_window_get_size",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle GTK_WINDOW_GET_POSITION = dl("gtk_window_get_position",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    // Size request acts as the minimum size for a toplevel window.
    static final MethodHandle GTK_WIDGET_SET_SIZE_REQUEST = dl("gtk_widget_set_size_request",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle GTK_WINDOW_RESIZE = dl("gtk_window_resize",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle GTK_WINDOW_MOVE = dl("gtk_window_move",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle GTK_WINDOW_SET_RESIZABLE = dl("gtk_window_set_resizable",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle GTK_WINDOW_PRESENT = dl("gtk_window_present", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WINDOW_ICONIFY = dl("gtk_window_iconify", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WINDOW_DEICONIFY = dl("gtk_window_deiconify", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WINDOW_MAXIMIZE = dl("gtk_window_maximize", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WINDOW_UNMAXIMIZE = dl("gtk_window_unmaximize", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WINDOW_FULLSCREEN = dl("gtk_window_fullscreen", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WINDOW_UNFULLSCREEN = dl("gtk_window_unfullscreen", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WINDOW_SET_KEEP_ABOVE = dl("gtk_window_set_keep_above",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle GTK_WIDGET_SHOW_ALL = dl("gtk_widget_show_all",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WIDGET_HIDE = dl("gtk_widget_hide",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_WIDGET_DESTROY = dl("gtk_widget_destroy",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GTK_MESSAGE_DIALOG_NEW = LINKER.downcallHandle(
            LOOKUP.findOrThrow("gtk_message_dialog_new"),
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
            Linker.Option.firstVariadicArg(5));
    static final MethodHandle GTK_DIALOG_RUN = dl("gtk_dialog_run",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    // gtk_file_chooser_dialog_new(title, parent, action, first_button_text, ...) variadic.
    static final MethodHandle GTK_FILE_CHOOSER_DIALOG_NEW = LINKER.downcallHandle(
            LOOKUP.findOrThrow("gtk_file_chooser_dialog_new"),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT,
                    ADDRESS, JAVA_INT),
            Linker.Option.firstVariadicArg(3));
    static final MethodHandle GTK_FILE_CHOOSER_GET_FILENAME = dl("gtk_file_chooser_get_filename",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle GTK_FILE_CHOOSER_GET_FILENAMES = dl("gtk_file_chooser_get_filenames",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle GTK_FILE_CHOOSER_SET_SELECT_MULTIPLE = dl(
            "gtk_file_chooser_set_select_multiple", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle GTK_FILE_CHOOSER_SET_CURRENT_NAME = dl(
            "gtk_file_chooser_set_current_name", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GTK_FILE_CHOOSER_SET_CURRENT_FOLDER = dl(
            "gtk_file_chooser_set_current_folder", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle GTK_FILE_CHOOSER_SET_DO_OVERWRITE_CONFIRMATION = dl(
            "gtk_file_chooser_set_do_overwrite_confirmation",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle G_SLIST_LENGTH = dl("g_slist_length",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle G_SLIST_NTH_DATA = dl("g_slist_nth_data",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle G_SLIST_FREE = dl("g_slist_free",
            FunctionDescriptor.ofVoid(ADDRESS));
    // WebKitGTK print operation.
    static final MethodHandle WEBKIT_PRINT_OPERATION_NEW = dl("webkit_print_operation_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_PRINT_OPERATION_RUN_DIALOG = dl(
            "webkit_print_operation_run_dialog", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle GTK_DIALOG_ADD_BUTTON = dl("gtk_dialog_add_button",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle GTK_WINDOW_SET_TITLE_DIALOG = GTK_WINDOW_SET_TITLE;
    static final MethodHandle GTK_CONTAINER_ADD = dl("gtk_container_add",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle GDK_ATOM_INTERN_STATIC_STRING = dl("gdk_atom_intern_static_string",
            FunctionDescriptor.of(ADDRESS,ADDRESS));
    static final MethodHandle GTK_CLIPBOARD_GET = dl("gtk_clipboard_get",FunctionDescriptor.of(ADDRESS,ADDRESS));
    static final MethodHandle GTK_CLIPBOARD_WAIT_FOR_TEXT = dl("gtk_clipboard_wait_for_text",FunctionDescriptor.of(ADDRESS,ADDRESS));
    static final MethodHandle GTK_CLIPBOARD_SET_TEXT = dl("gtk_clipboard_set_text",FunctionDescriptor.ofVoid(ADDRESS,ADDRESS,JAVA_INT));
    static final MethodHandle GTK_CLIPBOARD_STORE = dl("gtk_clipboard_store",FunctionDescriptor.ofVoid(ADDRESS));

    // ---- binary clipboard (custom target atoms) ----
    static final MethodHandle GDK_ATOM_INTERN = dl("gdk_atom_intern",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle GTK_CLIPBOARD_WAIT_FOR_CONTENTS = dl(
            "gtk_clipboard_wait_for_contents", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle GTK_CLIPBOARD_SET_WITH_DATA = dl("gtk_clipboard_set_with_data",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle GTK_SELECTION_DATA_GET_LENGTH = dl("gtk_selection_data_get_length",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GTK_SELECTION_DATA_GET_DATA = dl("gtk_selection_data_get_data",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle GTK_SELECTION_DATA_SET = dl("gtk_selection_data_set",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle GTK_SELECTION_DATA_FREE = dl("gtk_selection_data_free",
            FunctionDescriptor.ofVoid(ADDRESS));

    // ---- WebKitGTK 4.1 ----
    static final MethodHandle WEBKIT_WEB_CONTEXT_GET_DEFAULT = dl(
            "webkit_web_context_get_default", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle WEBKIT_WEB_CONTEXT_NEW_EPHEMERAL = dl(
            "webkit_web_context_new_ephemeral", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle WEBKIT_WEB_CONTEXT_REGISTER_URI_SCHEME = dl(
            "webkit_web_context_register_uri_scheme",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_CONTEXT_GET_SECURITY_MANAGER = dl(
            "webkit_web_context_get_security_manager", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_CONTEXT_GET_WEBSITE_DATA_MANAGER = dl(
            "webkit_web_context_get_website_data_manager",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_SECURITY_MANAGER_REGISTER_URI_SCHEME_AS_SECURE = dl(
            "webkit_security_manager_register_uri_scheme_as_secure",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_SECURITY_MANAGER_REGISTER_URI_SCHEME_AS_CORS_ENABLED = dl(
            "webkit_security_manager_register_uri_scheme_as_cors_enabled",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_NEW = dl("webkit_web_view_new",
            FunctionDescriptor.of(ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_NEW_WITH_CONTEXT = dl(
            "webkit_web_view_new_with_context", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_LOAD_URI = dl("webkit_web_view_load_uri",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_GET_URI = dl("webkit_web_view_get_uri",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_STOP_LOADING = dl("webkit_web_view_stop_loading",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER = dl(
            "webkit_web_view_get_user_content_manager", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_GET_SETTINGS = dl("webkit_web_view_get_settings",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_SETTINGS_SET_ENABLE_DEVELOPER_EXTRAS = dl(
            "webkit_settings_set_enable_developer_extras",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle WEBKIT_SETTINGS_GET_ENABLE_DEVELOPER_EXTRAS = dl(
            "webkit_settings_get_enable_developer_extras",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle WEBKIT_SETTINGS_SET_USER_AGENT = dl(
            "webkit_settings_set_user_agent", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_SETTINGS_GET_USER_AGENT = dl("webkit_settings_get_user_agent",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    // void webkit_web_view_evaluate_javascript(WebKitWebView*, const char* script,
    //     gssize length, const char* world_name, const char* source_uri,
    //     GCancellable*, GAsyncReadyCallback, gpointer)   [since 2.40]
    static final MethodHandle WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT = dl(
            "webkit_web_view_evaluate_javascript",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS,
                    ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT_FINISH = dl(
            "webkit_web_view_evaluate_javascript_finish",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_GET_SNAPSHOT = dl("webkit_web_view_get_snapshot",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_GET_SNAPSHOT_FINISH = dl(
            "webkit_web_view_get_snapshot_finish",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEBSITE_DATA_MANAGER_CLEAR = dl(
            "webkit_website_data_manager_clear",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEBSITE_DATA_MANAGER_CLEAR_FINISH = dl(
            "webkit_website_data_manager_clear_finish",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEBSITE_DATA_MANAGER_GET_COOKIE_MANAGER = dl(
            "webkit_website_data_manager_get_cookie_manager",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_COOKIE_MANAGER_GET_ALL_COOKIES = dl(
            "webkit_cookie_manager_get_all_cookies",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_COOKIE_MANAGER_GET_ALL_COOKIES_FINISH = dl(
            "webkit_cookie_manager_get_all_cookies_finish",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_COOKIE_MANAGER_ADD_COOKIE = dl(
            "webkit_cookie_manager_add_cookie",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_COOKIE_MANAGER_ADD_COOKIE_FINISH = dl(
            "webkit_cookie_manager_add_cookie_finish",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_COOKIE_MANAGER_DELETE_COOKIE = dl(
            "webkit_cookie_manager_delete_cookie",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_COOKIE_MANAGER_DELETE_COOKIE_FINISH = dl(
            "webkit_cookie_manager_delete_cookie_finish",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle SOUP_COOKIE_NEW = dl("soup_cookie_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle SOUP_COOKIE_COPY = dl("soup_cookie_copy",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle SOUP_COOKIE_FREE = dl("soup_cookie_free",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle SOUP_COOKIE_GET_NAME = dl("soup_cookie_get_name",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle SOUP_COOKIE_GET_VALUE = dl("soup_cookie_get_value",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle SOUP_COOKIE_GET_DOMAIN = dl("soup_cookie_get_domain",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle SOUP_COOKIE_GET_PATH = dl("soup_cookie_get_path",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle SOUP_COOKIE_GET_EXPIRES = dl("soup_cookie_get_expires",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle SOUP_COOKIE_SET_EXPIRES = dl("soup_cookie_set_expires",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle SOUP_COOKIE_GET_SECURE = dl("soup_cookie_get_secure",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle SOUP_COOKIE_SET_SECURE = dl("soup_cookie_set_secure",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle SOUP_COOKIE_GET_HTTP_ONLY = dl("soup_cookie_get_http_only",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle SOUP_COOKIE_SET_HTTP_ONLY = dl("soup_cookie_set_http_only",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle WEBKIT_USER_CONTENT_MANAGER_REGISTER_SCRIPT_MESSAGE_HANDLER = dl(
            "webkit_user_content_manager_register_script_message_handler",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_USER_CONTENT_MANAGER_UNREGISTER_SCRIPT_MESSAGE_HANDLER = dl(
            "webkit_user_content_manager_unregister_script_message_handler",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_USER_CONTENT_MANAGER_ADD_SCRIPT = dl(
            "webkit_user_content_manager_add_script",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_USER_CONTENT_MANAGER_REMOVE_ALL_SCRIPTS = dl(
            "webkit_user_content_manager_remove_all_scripts",
            FunctionDescriptor.ofVoid(ADDRESS));
    // WebKitUserScript* webkit_user_script_new(const gchar* source,
    //     WebKitUserContentInjectedFrames, WebKitUserScriptInjectionTime,
    //     const gchar* const* allow_list, const gchar* const* block_list)
    static final MethodHandle WEBKIT_USER_SCRIPT_NEW = dl("webkit_user_script_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_USER_SCRIPT_UNREF = dl("webkit_user_script_unref",
            FunctionDescriptor.ofVoid(ADDRESS));
    // The 4.1 "script-message-received" signal delivers a WebKitJavascriptResult*.
    static final MethodHandle WEBKIT_JAVASCRIPT_RESULT_GET_JS_VALUE = dl(
            "webkit_javascript_result_get_js_value", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle JSC_VALUE_TO_STRING = dl("jsc_value_to_string",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_NAVIGATION_POLICY_DECISION_GET_NAVIGATION_ACTION = dl(
            "webkit_navigation_policy_decision_get_navigation_action",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_NAVIGATION_ACTION_GET_REQUEST = dl(
            "webkit_navigation_action_get_request", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_NAVIGATION_ACTION_GET_NAVIGATION_TYPE = dl(
            "webkit_navigation_action_get_navigation_type",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle WEBKIT_URI_REQUEST_GET_URI = dl("webkit_uri_request_get_uri",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_POLICY_DECISION_USE = dl("webkit_policy_decision_use",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle WEBKIT_POLICY_DECISION_IGNORE = dl("webkit_policy_decision_ignore",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle WEBKIT_URI_SCHEME_REQUEST_GET_URI = dl(
            "webkit_uri_scheme_request_get_uri", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_URI_SCHEME_REQUEST_GET_HTTP_METHOD = dl(
            "webkit_uri_scheme_request_get_http_method",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    // Returns SoupMessageHeaders* (transfer none); available since WebKitGTK 2.36.
    static final MethodHandle WEBKIT_URI_SCHEME_REQUEST_GET_HTTP_HEADERS = dl(
            "webkit_uri_scheme_request_get_http_headers",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    // Returns the request body GInputStream* (transfer none); WebKitGTK 2.36+. Null handle
    // when the symbol is absent (older WebKitGTK) -> the adapter forwards an empty body.
    static final MethodHandle WEBKIT_URI_SCHEME_REQUEST_GET_HTTP_BODY =
            LOOKUP.find("webkit_uri_scheme_request_get_http_body")
                    .map(sym -> LINKER.downcallHandle(sym,
                            FunctionDescriptor.of(ADDRESS, ADDRESS)))
                    .orElse(null);
    // gssize g_input_stream_read(GInputStream*, void* buffer, gsize count, GCancellable*, GError**)
    static final MethodHandle G_INPUT_STREAM_READ = dl("g_input_stream_read",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_URI_SCHEME_REQUEST_FINISH_WITH_RESPONSE = dl(
            "webkit_uri_scheme_request_finish_with_response",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_URI_SCHEME_REQUEST_FINISH_ERROR = dl(
            "webkit_uri_scheme_request_finish_error",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_URI_SCHEME_RESPONSE_NEW = dl(
            "webkit_uri_scheme_response_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle WEBKIT_URI_SCHEME_RESPONSE_SET_STATUS = dl(
            "webkit_uri_scheme_response_set_status",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
    static final MethodHandle WEBKIT_URI_SCHEME_RESPONSE_SET_CONTENT_TYPE = dl(
            "webkit_uri_scheme_response_set_content_type",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    // Takes ownership (transfer full) of the SoupMessageHeaders.
    static final MethodHandle WEBKIT_URI_SCHEME_RESPONSE_SET_HTTP_HEADERS = dl(
            "webkit_uri_scheme_response_set_http_headers",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_GET_MAJOR_VERSION = dl("webkit_get_major_version",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle WEBKIT_GET_MINOR_VERSION = dl("webkit_get_minor_version",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle WEBKIT_GET_MICRO_VERSION = dl("webkit_get_micro_version",
            FunctionDescriptor.of(JAVA_INT));

    // ---- libsoup 3 ----
    static final MethodHandle SOUP_MESSAGE_HEADERS_NEW = dl("soup_message_headers_new",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));
    static final MethodHandle SOUP_MESSAGE_HEADERS_APPEND = dl("soup_message_headers_append",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    // Returns const char* (transfer none): do not free.
    static final MethodHandle SOUP_MESSAGE_HEADERS_GET_ONE = dl("soup_message_headers_get_one",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    // ---- cairo ----
    static final MethodHandle CAIRO_SURFACE_WRITE_TO_PNG_STREAM = dl(
            "cairo_surface_write_to_png_stream",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle CAIRO_IMAGE_SURFACE_GET_WIDTH = dl(
            "cairo_image_surface_get_width", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle CAIRO_IMAGE_SURFACE_GET_HEIGHT = dl(
            "cairo_image_surface_get_height", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle CAIRO_SURFACE_DESTROY = dl("cairo_surface_destroy",
            FunctionDescriptor.ofVoid(ADDRESS));

    private Gtk() {
    }

    private static MethodHandle dl(String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LOOKUP.findOrThrow(symbol), descriptor);
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException runtime) {
            return runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("GTK/WebKitGTK call failed", t);
    }

    /** Upcall stub in the process-lifetime callback arena. */
    static MemorySegment upcall(MethodHandle target, FunctionDescriptor descriptor) {
        return LINKER.upcallStub(target, descriptor, CALLBACK_ARENA);
    }

    /** Java string from a transfer-none C string (copied immediately). */
    static String javaString(MemorySegment cstr) {
        if (cstr == null || cstr.equals(MemorySegment.NULL)) {
            return null;
        }
        return cstr.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Java string from a transfer-full C string; frees it with {@code g_free}. */
    static String takeString(MemorySegment cstr) {
        if (cstr == null || cstr.equals(MemorySegment.NULL)) {
            return null;
        }
        try {
            return javaString(cstr);
        } finally {
            gFree(cstr);
        }
    }

    static void gFree(MemorySegment pointer) {
        try {
            G_FREE.invokeExact(pointer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static MemorySegment gObjectRef(MemorySegment object) {
        try {
            return (MemorySegment) G_OBJECT_REF.invokeExact(object);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static MemorySegment gObjectRefSink(MemorySegment object) {
        try {
            return (MemorySegment) G_OBJECT_REF_SINK.invokeExact(object);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void gObjectUnref(MemorySegment object) {
        if (object == null || object.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            G_OBJECT_UNREF.invokeExact(object);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code g_signal_connect_data(instance, signal, callback, NULL, NULL, 0)}. */
    static long signalConnect(MemorySegment instance, String signal, MemorySegment callback) {
        try (Arena confined = Arena.ofConfined()) {
            return (long) G_SIGNAL_CONNECT_DATA.invokeExact(instance,
                    confined.allocateFrom(signal), callback, MemorySegment.NULL,
                    MemorySegment.NULL, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void signalDisconnect(MemorySegment instance, long handlerId) {
        try {
            G_SIGNAL_HANDLER_DISCONNECT.invokeExact(instance, handlerId);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Reads a {@code GError*} out-slot: message text, then frees the error. */
    static String takeErrorMessage(MemorySegment errorSlot) {
        MemorySegment error = errorSlot.get(ADDRESS, 0);
        if (error.equals(MemorySegment.NULL)) {
            return "unknown error";
        }
        // struct GError { GQuark domain; gint code; gchar *message; } — glib/gerror.h;
        // message pointer at offset 8 on LP64.
        MemorySegment messagePtr = error.reinterpret(16).get(ADDRESS, 8);
        String message = javaString(messagePtr);
        try {
            G_ERROR_FREE.invokeExact(error);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        return message == null ? "unknown error" : message;
    }
}
