package dev.jdesk.platform.windows;

/**
 * IIDs and vtable slots for the WebView2 Win32 COM API.
 * Source: Microsoft.Web.WebView2 SDK 1.0.2903.40, WebView2.h; the full generated
 * reference lives in docs/verification/webview2-vtables-1.0.2903.40.txt (spec 6.5).
 */
final class WebView2 {
    private WebView2() {
    }

    static final String TARGET_COMPATIBLE_BROWSER_VERSION = "131.0.2903.40";

    // ---- interface IIDs ----
    static final String IID_ENVIRONMENT = "b96d755e-0319-4e92-a296-23436f46a1fc";
    static final String IID_ENVIRONMENT_COMPLETED_HANDLER = "4e8a3389-c9d8-4bd2-b6b5-124fee6cc14d";
    static final String IID_CONTROLLER_COMPLETED_HANDLER = "6c4819f3-c9b7-4260-8127-c9f5bde7f68c";
    static final String IID_CONTROLLER = "4d00c0d1-9434-4eb6-8078-8697a560334f";
    static final String IID_WEBVIEW2 = "76eceacb-0462-4d94-ac83-423a6793775e";
    static final String IID_SETTINGS = "e562e4f0-d7fa-43ac-8d71-c05150499f00";
    static final String IID_WEB_MESSAGE_RECEIVED_HANDLER = "57213f19-00e6-49fa-8e07-898ea01ecbd2";
    static final String IID_NAVIGATION_STARTING_HANDLER = "9adbe429-f36d-432b-9ddc-f8881fbd76e3";
    static final String IID_NAVIGATION_COMPLETED_HANDLER = "d33a35bf-1c49-4f98-93ab-006e0533fe1c";
    static final String IID_CONTENT_LOADING_HANDLER = "364471e7-f2be-4910-bdba-d72077d51c4b";
    static final String IID_PROCESS_FAILED_HANDLER = "79e0aea4-990b-42d9-aa1d-0fcc2e5bc7f1";
    static final String IID_CAPTURE_PREVIEW_COMPLETED_HANDLER = "697e05e9-3d8f-45fa-96f4-8ffe1ededaf5";
    static final String IID_EXECUTE_SCRIPT_COMPLETED_HANDLER = "49511172-cc67-4bca-9923-137112f4c4cc";
    static final String IID_ADD_SCRIPT_COMPLETED_HANDLER = "b99369f3-9b11-47b5-bc6f-8e7895fcea17";
    static final String IID_WEB_RESOURCE_REQUESTED_HANDLER = "ab00b74c-15f1-4646-80e8-e76341d25d71";
    static final String IID_NEW_WINDOW_REQUESTED_HANDLER = "d4c185fe-c81c-4989-97af-2d3fa7ab5651";
    static final String IID_ENVIRONMENT_OPTIONS = "2fde08a8-1e9a-4766-8c05-95a9ceb9d1c5";
    static final String IID_ENVIRONMENT_OPTIONS4 = "ac52d13f-0d38-475a-9dca-876580d6793e";
    static final String IID_CUSTOM_SCHEME_REGISTRATION = "d60ac92c-37a6-4b26-a39e-95cfe59047bb";

    // ---- ICoreWebView2Environment ----
    static final int ENV_CREATE_CONTROLLER = 3;
    static final int ENV_CREATE_WEB_RESOURCE_RESPONSE = 4;
    static final int ENV_GET_BROWSER_VERSION_STRING = 5;

    // ---- ICoreWebView2Controller ----
    static final int CTRL_PUT_IS_VISIBLE = 4;
    static final int CTRL_PUT_BOUNDS = 6;
    static final int CTRL_CLOSE = 24;
    static final int CTRL_GET_COREWEBVIEW2 = 25;

    // ---- ICoreWebView2 ----
    static final int WV_GET_SETTINGS = 3;
    static final int WV_NAVIGATE = 5;
    static final int WV_ADD_NAVIGATION_STARTING = 7;
    static final int WV_ADD_CONTENT_LOADING = 9;
    static final int WV_ADD_NAVIGATION_COMPLETED = 15;
    static final int WV_ADD_FRAME_NAVIGATION_STARTING = 17;
    static final int WV_ADD_PROCESS_FAILED = 25;
    static final int WV_ADD_SCRIPT_ON_DOCUMENT_CREATED = 27;
    static final int WV_EXECUTE_SCRIPT = 29;
    static final int WV_CAPTURE_PREVIEW = 30;
    static final int WV_POST_WEB_MESSAGE_AS_STRING = 33;
    static final int WV_ADD_WEB_MESSAGE_RECEIVED = 34;
    static final int WV_GET_BROWSER_PROCESS_ID = 37;
    static final int WV_ADD_NEW_WINDOW_REQUESTED = 44;
    static final int WV_ADD_WEB_RESOURCE_REQUESTED = 55;
    static final int WV_ADD_WEB_RESOURCE_REQUESTED_FILTER = 57;

    // ---- ICoreWebView2Settings ----
    static final int SETTINGS_PUT_ARE_DEV_TOOLS_ENABLED = 8;
    static final int SETTINGS_PUT_ARE_DEFAULT_CONTEXT_MENUS_ENABLED = 12;

    // ---- ICoreWebView2WebMessageReceivedEventArgs ----
    static final int MSG_ARGS_GET_SOURCE = 3;
    static final int MSG_ARGS_TRY_GET_STRING = 5;

    // ---- ICoreWebView2NavigationStartingEventArgs ----
    static final int NAV_ARGS_GET_URI = 3;
    static final int NAV_ARGS_GET_IS_USER_INITIATED = 4;
    static final int NAV_ARGS_PUT_CANCEL = 8;

    // ---- ICoreWebView2ProcessFailedEventArgs ----
    static final int PF_ARGS_GET_KIND = 3;

    // ---- ICoreWebView2WebResourceRequestedEventArgs ----
    static final int WRR_ARGS_GET_REQUEST = 3;
    static final int WRR_ARGS_PUT_RESPONSE = 5;

    // ---- ICoreWebView2WebResourceRequest ----
    static final int WR_REQ_GET_URI = 3;
    static final int WR_REQ_GET_METHOD = 5;
    static final int WR_REQ_GET_HEADERS = 9;

    // ---- ICoreWebView2HttpRequestHeaders ----
    static final int HTTP_REQ_HEADERS_GET_HEADER = 3;

    // ---- ICoreWebView2NewWindowRequestedEventArgs ----
    static final int NW_ARGS_PUT_HANDLED = 6; // get_Uri=3, get_NewWindow=4, put_NewWindow=5, put_Handled=6

    // ---- ICoreWebView2EnvironmentOptions slots (implemented BY us) ----
    // get/put AdditionalBrowserArguments=3/4, Language=5/6, TargetCompatibleBrowserVersion=7/8,
    // AllowSingleSignOnUsingOSPrimaryAccount=9/10
    // ---- ICoreWebView2EnvironmentOptions4: GetCustomSchemeRegistrations=3, Set=4
    // ---- ICoreWebView2CustomSchemeRegistration: get_SchemeName=3, get_TreatAsSecure=4,
    //      put_TreatAsSecure=5, GetAllowedOrigins=6, SetAllowedOrigins=7,
    //      get_HasAuthorityComponent=8, put_HasAuthorityComponent=9

    static final int WEB_RESOURCE_CONTEXT_ALL = 0;
}
