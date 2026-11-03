// Read the UUID from Microsoft's MIDL declaration, not a second GUID literal.
#include <windows.h>
#include "headers/pluginauthenticator.h"

GUID plugin_iid() {
    return __uuidof(IPluginAuthenticator);
}
