#pragma once

#ifndef PC_TEAM_PREVIEW_STANDALONE
#include <napi/native_api.h>
#endif

#include "team_preview_engine_core.h"

#ifndef PC_TEAM_PREVIEW_STANDALONE
napi_value RecognizeTeamPreview(napi_env env, napi_callback_info info);
#endif
