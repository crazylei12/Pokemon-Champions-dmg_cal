#include <cstdint>
#include <napi/native_api.h>

namespace {

void SetString(napi_env env, napi_value object, const char *name, const char *value)
{
    napi_value out;
    napi_create_string_utf8(env, value, NAPI_AUTO_LENGTH, &out);
    napi_set_named_property(env, object, name, out);
}

void SetNumber(napi_env env, napi_value object, const char *name, int32_t value)
{
    napi_value out;
    napi_create_int32(env, value, &out);
    napi_set_named_property(env, object, name, out);
}

void SetBool(napi_env env, napi_value object, const char *name, bool value)
{
    napi_value out;
    napi_get_boolean(env, value, &out);
    napi_set_named_property(env, object, name, out);
}

napi_value GetBridgeInfo(napi_env env, napi_callback_info)
{
    napi_value object;
    napi_create_object(env, &object);
    SetNumber(env, object, "api", 1);
    SetString(env, object, "name", "pcbridge");
    SetBool(env, object, "native", true);
    return object;
}

napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor descriptors[] = {
        { "getBridgeInfo", nullptr, GetBridgeInfo, nullptr, nullptr, nullptr, napi_default, nullptr },
    };
    napi_define_properties(env, exports, sizeof(descriptors) / sizeof(descriptors[0]), descriptors);
    return exports;
}

} // namespace

static napi_module g_module = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "pcbridge",
    .nm_priv = nullptr,
    .reserved = { nullptr },
};

extern "C" __attribute__((constructor)) void RegisterPcBridgeModule()
{
    napi_module_register(&g_module);
}
