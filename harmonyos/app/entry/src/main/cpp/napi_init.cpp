#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <hilog/log.h>
#include <multimedia/player_framework/native_avbuffer.h>
#include <multimedia/player_framework/native_avscreen_capture.h>
#include <napi/native_api.h>

#include "team_preview_engine.h"
#include "replay_recorder.h"

namespace {

constexpr uint32_t PC_LOG_DOMAIN = 0x5043;
constexpr char PC_LOG_TAG[] = "PCBridgeNative";
constexpr int32_t RGBA_BYTES_PER_PIXEL = 4;

struct CaptureSession {
    std::mutex sessionMutex;
    std::mutex frameMutex;
    OH_AVScreenCapture *capture = nullptr;
    std::string message = "not prepared";
    std::atomic<bool> prepared{false};
    std::atomic<bool> running{false};
    std::atomic<int32_t> lastCode{0};
    std::atomic<int32_t> stateCode{-1};
    std::atomic<int32_t> errorCode{0};
    std::atomic<uint64_t> videoFrames{0};
    std::atomic<uint64_t> acceptedFrames{0};
    std::atomic<uint64_t> rejectedFrames{0};
    std::atomic<uint32_t> validStreak{0};
    std::atomic<bool> recognitionEnabled{true};
    int32_t width = 0;
    int32_t height = 0;
    std::shared_ptr<std::vector<uint8_t>> latestFrame;
    uint32_t latestHash = 0;
    int64_t latestTimestampUs = -1;
};

CaptureSession g_session;
pc::ReplayRecorder g_recorder;

struct FrameRect {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
    int32_t area;
};

void SetString(napi_env env, napi_value object, const char *name, const std::string &value)
{
    napi_value out;
    napi_create_string_utf8(env, value.c_str(), value.size(), &out);
    napi_set_named_property(env, object, name, out);
}

void SetNumber(napi_env env, napi_value object, const char *name, double value)
{
    napi_value out;
    napi_create_double(env, value, &out);
    napi_set_named_property(env, object, name, out);
}

void SetBool(napi_env env, napi_value object, const char *name, bool value)
{
    napi_value out;
    napi_get_boolean(env, value, &out);
    napi_set_named_property(env, object, name, out);
}

napi_value MakeBaseResult(napi_env env, bool ok, int32_t code, const std::string &message)
{
    napi_value object;
    napi_create_object(env, &object);
    SetBool(env, object, "ok", ok);
    SetNumber(env, object, "code", code);
    SetString(env, object, "message", message);
    return object;
}

int32_t ReadInt32(napi_env env, napi_value value)
{
    int32_t result = 0;
    napi_get_value_int32(env, value, &result);
    return result;
}

bool ReadBool(napi_env env, napi_value value)
{
    bool result = false;
    napi_get_value_bool(env, value, &result);
    return result;
}

std::string ReadUtf8(napi_env env, napi_value value)
{
    size_t length = 0;
    napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    std::vector<char> buffer(length + 1, '\0');
    napi_get_value_string_utf8(env, value, buffer.data(), buffer.size(), &length);
    return std::string(buffer.data(), length);
}

uint32_t Fnv1a(const uint8_t *bytes, size_t length)
{
    uint32_t hash = 2166136261U;
    for (size_t index = 0; index < length; ++index) {
        hash ^= bytes[index];
        hash *= 16777619U;
    }
    return hash;
}

bool CopyVisibleRgbaFrame(OH_AVBuffer *buffer, int32_t width, int32_t height, std::vector<uint8_t> &output)
{
    if (buffer == nullptr || width <= 0 || height <= 0) return false;
    uint8_t *address = OH_AVBuffer_GetAddr(buffer);
    const int32_t capacity = OH_AVBuffer_GetCapacity(buffer);
    if (address == nullptr || capacity <= 0 || capacity % height != 0) return false;
    const int32_t sourceStride = capacity / height;
    const int32_t visibleStride = width * RGBA_BYTES_PER_PIXEL;
    if (sourceStride < visibleStride) return false;
    output.resize(static_cast<size_t>(visibleStride) * height);
    for (int32_t row = 0; row < height; ++row) {
        std::memcpy(output.data() + static_cast<size_t>(row) * visibleStride,
            address + static_cast<size_t>(row) * sourceStride, visibleStride);
    }
    return true;
}

bool IsVisibleFrame(const std::vector<uint8_t> &rgba, int32_t width, int32_t height)
{
    if (rgba.size() < static_cast<size_t>(width) * height * RGBA_BYTES_PER_PIXEL) return false;
    const int32_t stepX = std::max(1, width / 64);
    const int32_t stepY = std::max(1, height / 36);
    uint64_t samples = 0;
    uint64_t nonBlack = 0;
    int32_t minimum = 255;
    int32_t maximum = 0;
    for (int32_t y = stepY / 2; y < height; y += stepY) {
        for (int32_t x = stepX / 2; x < width; x += stepX) {
            const size_t index = (static_cast<size_t>(y) * width + x) * RGBA_BYTES_PER_PIXEL;
            const int32_t luminance =
                (rgba[index] * 299 + rgba[index + 1] * 587 + rgba[index + 2] * 114) / 1000;
            minimum = std::min(minimum, luminance);
            maximum = std::max(maximum, luminance);
            if (luminance >= 12) ++nonBlack;
            ++samples;
        }
    }
    return samples >= 100 && nonBlack * 100 >= samples * 2 && maximum - minimum >= 10;
}

std::vector<FrameRect> DetectTeamCards(const std::vector<uint8_t> &rgba, int32_t sourceWidth, int32_t sourceHeight)
{
    if (sourceWidth <= 0 || sourceHeight <= 0) return {};
    const double scale = std::min(1.0, 900.0 / sourceWidth);
    const int32_t width = std::max(1, static_cast<int32_t>(sourceWidth * scale));
    const int32_t height = std::max(1, static_cast<int32_t>(sourceHeight * scale));
    std::vector<uint8_t> mask(static_cast<size_t>(width) * height, 0);
    for (int32_t y = 0; y < height; ++y) {
        const int32_t sourceY = std::min(sourceHeight - 1, static_cast<int32_t>(y / scale));
        for (int32_t x = 0; x < width; ++x) {
            const int32_t sourceX = std::min(sourceWidth - 1, static_cast<int32_t>(x / scale));
            const size_t sourceIndex = (static_cast<size_t>(sourceY) * sourceWidth + sourceX) * 4;
            const double red = rgba[sourceIndex] / 255.0;
            const double green = rgba[sourceIndex + 1] / 255.0;
            const double blue = rgba[sourceIndex + 2] / 255.0;
            const double maximum = std::max(red, std::max(green, blue));
            const double minimum = std::min(red, std::min(green, blue));
            const double delta = maximum - minimum;
            double hue = 0.0;
            if (delta != 0.0) {
                if (maximum == red) hue = 60.0 * std::fmod((green - blue) / delta, 6.0);
                else if (maximum == green) hue = 60.0 * ((blue - red) / delta + 2.0);
                else hue = 60.0 * ((red - green) / delta + 4.0);
            }
            if (hue < 0.0) hue += 360.0;
            const double saturation = maximum == 0.0 ? 0.0 : delta / maximum;
            mask[static_cast<size_t>(y) * width + x] =
                hue >= 220.0 && hue <= 280.0 && saturation >= 0.18 && maximum >= 0.20 ? 1 : 0;
        }
    }
    std::vector<uint8_t> visited(mask.size(), 0);
    std::vector<int32_t> queue(mask.size());
    std::vector<FrameRect> components;
    for (int32_t start = 0; start < static_cast<int32_t>(mask.size()); ++start) {
        if (mask[start] == 0 || visited[start] != 0) continue;
        int32_t head = 0;
        int32_t tail = 0;
        queue[tail++] = start;
        visited[start] = 1;
        int32_t left = width;
        int32_t top = height;
        int32_t right = 0;
        int32_t bottom = 0;
        int32_t area = 0;
        while (head < tail) {
            const int32_t current = queue[head++];
            const int32_t x = current % width;
            const int32_t y = current / width;
            left = std::min(left, x);
            top = std::min(top, y);
            right = std::max(right, x);
            bottom = std::max(bottom, y);
            ++area;
            const int32_t neighbors[4] = { current - 1, current + 1, current - width, current + width };
            const bool valid[4] = { x > 0, x < width - 1, y > 0, y < height - 1 };
            for (int index = 0; index < 4; ++index) {
                const int32_t next = neighbors[index];
                if (valid[index] && mask[next] != 0 && visited[next] == 0) {
                    visited[next] = 1;
                    queue[tail++] = next;
                }
            }
        }
        const int32_t scaledLeft = static_cast<int32_t>(left / scale);
        const int32_t scaledTop = static_cast<int32_t>(top / scale);
        const int32_t scaledRight = std::min(sourceWidth, static_cast<int32_t>((right + 1) / scale));
        const int32_t scaledBottom = std::min(sourceHeight, static_cast<int32_t>((bottom + 1) / scale));
        const int32_t rectWidth = scaledRight - scaledLeft;
        const int32_t rectHeight = scaledBottom - scaledTop;
        const int32_t scaledArea = static_cast<int32_t>(area / (scale * scale));
        const int64_t sourceArea = static_cast<int64_t>(sourceWidth) * sourceHeight;
        if (scaledArea > sourceArea * 0.003 && rectWidth > sourceWidth * 0.15 &&
            rectWidth < sourceWidth * 0.48 && rectHeight > sourceHeight * 0.05 && rectHeight < sourceHeight * 0.25) {
            components.push_back({ scaledLeft, scaledTop, scaledRight, scaledBottom, scaledArea });
        }
    }
    std::sort(components.begin(), components.end(), [](const FrameRect &left, const FrameRect &right) {
        return left.area > right.area;
    });
    if (components.size() > 6) components.resize(6);
    if (components.size() != 6) return {};
    std::sort(components.begin(), components.end(), [](const FrameRect &left, const FrameRect &right) {
        return (left.top + left.bottom) < (right.top + right.bottom);
    });
    for (size_t rowStart = 0; rowStart < components.size(); rowStart += 2) {
        auto first = components.begin() + static_cast<std::ptrdiff_t>(rowStart);
        auto last = first + 2;
        std::sort(first, last, [](const FrameRect &left, const FrameRect &right) { return left.left < right.left; });
        const int32_t sharedTop = std::max(first->top, (first + 1)->top);
        const int32_t sharedBottom = std::min(first->bottom, (first + 1)->bottom);
        if (sharedBottom > sharedTop) {
            first->top = sharedTop;
            first->bottom = sharedBottom;
            (first + 1)->top = sharedTop;
            (first + 1)->bottom = sharedBottom;
        }
    }
    return components;
}

void SetCardRects(napi_env env, napi_value object, const std::vector<FrameRect> &cards)
{
    napi_value array;
    napi_create_array_with_length(env, cards.size(), &array);
    for (size_t index = 0; index < cards.size(); ++index) {
        napi_value card;
        napi_create_object(env, &card);
        SetNumber(env, card, "left", cards[index].left);
        SetNumber(env, card, "top", cards[index].top);
        SetNumber(env, card, "right", cards[index].right);
        SetNumber(env, card, "bottom", cards[index].bottom);
        napi_set_element(env, array, static_cast<uint32_t>(index), card);
    }
    napi_set_named_property(env, object, "cards", array);
}

void ResetFrameState()
{
    g_session.videoFrames.store(0);
    g_session.acceptedFrames.store(0);
    g_session.rejectedFrames.store(0);
    g_session.validStreak.store(0);
    g_session.stateCode.store(-1);
    g_session.errorCode.store(0);
    std::lock_guard<std::mutex> frameGuard(g_session.frameMutex);
    g_session.latestFrame.reset();
    g_session.latestHash = 0;
    g_session.latestTimestampUs = -1;
}

void OnCaptureState(OH_AVScreenCapture *, OH_AVScreenCaptureStateCode state, void *)
{
    g_session.stateCode.store(static_cast<int32_t>(state));
    if (state == OH_SCREEN_CAPTURE_STATE_STARTED) {
        g_session.running.store(true);
    } else if (state == OH_SCREEN_CAPTURE_STATE_CANCELED || state == OH_SCREEN_CAPTURE_STATE_STOPPED_BY_USER ||
        state == OH_SCREEN_CAPTURE_STATE_INTERRUPTED_BY_OTHER || state == OH_SCREEN_CAPTURE_STATE_STOPPED_BY_CALL ||
        state == OH_SCREEN_CAPTURE_STATE_STOPPED_BY_USER_SWITCHES) {
        g_session.running.store(false);
        g_recorder.SignalInputEnded();
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, PC_LOG_DOMAIN, PC_LOG_TAG,
        "CAPTURE_STATE %{public}d", static_cast<int32_t>(state));
}

void OnCaptureError(OH_AVScreenCapture *, int32_t errorCode, void *)
{
    g_session.errorCode.store(errorCode);
    g_session.running.store(false);
    g_recorder.SignalInputEnded();
    OH_LOG_Print(LOG_APP, LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG, "CAPTURE_ERROR %{public}d", errorCode);
}

void OnCaptureBuffer(OH_AVScreenCapture *, OH_AVBuffer *buffer, OH_AVScreenCaptureBufferType type,
    int64_t timestamp, void *)
{
    if (type == OH_SCREEN_CAPTURE_BUFFERTYPE_AUDIO_INNER) {
        if (buffer != nullptr && g_recorder.IsAccepting()) {
            uint8_t *address = OH_AVBuffer_GetAddr(buffer);
            int32_t size = OH_AVBuffer_GetCapacity(buffer);
            OH_AVCodecBufferAttr attr {};
            if (OH_AVBuffer_GetBufferAttr(buffer, &attr) == AV_ERR_OK && attr.size > 0 &&
                attr.offset >= 0 && attr.offset + attr.size <= size) {
                address += attr.offset;
                size = attr.size;
            }
            if (address != nullptr && size > 0) g_recorder.EnqueueAudio(address, static_cast<size_t>(size));
        }
        return;
    }
    if (type != OH_SCREEN_CAPTURE_BUFFERTYPE_VIDEO) return;
    g_session.videoFrames.fetch_add(1);
    auto candidate = std::make_shared<std::vector<uint8_t>>();
    if (!CopyVisibleRgbaFrame(buffer, g_session.width, g_session.height, *candidate)) {
        g_session.rejectedFrames.fetch_add(1);
        g_session.validStreak.store(0);
        return;
    }
    if (g_recorder.IsAccepting()) g_recorder.EnqueueVideo(candidate, g_session.width, g_session.height);
    if (!g_session.recognitionEnabled.load()) return;
    if (!IsVisibleFrame(*candidate, g_session.width, g_session.height)) {
        g_session.rejectedFrames.fetch_add(1);
        g_session.validStreak.store(0);
        return;
    }
    const uint32_t streak = g_session.validStreak.fetch_add(1) + 1;
    if (streak < 2) return;
    const uint32_t hash = Fnv1a(candidate->data(), candidate->size());
    {
        std::lock_guard<std::mutex> frameGuard(g_session.frameMutex);
        g_session.latestFrame.swap(candidate);
        g_session.latestHash = hash;
        g_session.latestTimestampUs = timestamp;
    }
    g_session.acceptedFrames.fetch_add(1);
}

void ReleaseCaptureLocked()
{
    if (g_session.capture != nullptr) {
        if (g_session.running.load()) OH_AVScreenCapture_StopScreenCapture(g_session.capture);
        OH_AVScreenCapture_Release(g_session.capture);
        g_session.capture = nullptr;
    }
    g_session.prepared.store(false);
    g_session.running.store(false);
    if (g_recorder.IsAccepting()) g_recorder.Stop(false);
}

OH_AVScreenCaptureConfig MakeRawCaptureConfig(int32_t width, int32_t height)
{
    OH_AVScreenCaptureConfig config{};
    config.captureMode = OH_CAPTURE_HOME_SCREEN;
    config.dataType = OH_ORIGINAL_STREAM;
    config.audioInfo.micCapInfo.audioSampleRate = 0;
    config.audioInfo.micCapInfo.audioChannels = 0;
    config.audioInfo.micCapInfo.audioSource = OH_SOURCE_INVALID;
    config.audioInfo.innerCapInfo.audioSampleRate = 48000;
    config.audioInfo.innerCapInfo.audioChannels = 2;
    config.audioInfo.innerCapInfo.audioSource = OH_APP_PLAYBACK;
    config.videoInfo.videoCapInfo.displayId = 0;
    config.videoInfo.videoCapInfo.missionIDs = nullptr;
    config.videoInfo.videoCapInfo.missionIDsLen = 0;
    config.videoInfo.videoCapInfo.videoFrameWidth = width;
    config.videoInfo.videoCapInfo.videoFrameHeight = height;
    config.videoInfo.videoCapInfo.videoSource = OH_VIDEO_SOURCE_SURFACE_RGBA;
    config.videoInfo.videoEncInfo.videoCodec = OH_H264;
    config.videoInfo.videoEncInfo.videoBitrate = 12000000;
    config.videoInfo.videoEncInfo.videoFrameRate = 30;
    return config;
}

napi_value GetBridgeInfo(napi_env env, napi_callback_info)
{
    napi_value object;
    napi_create_object(env, &object);
    SetNumber(env, object, "api", 2);
    SetString(env, object, "name", "pcbridge");
    SetBool(env, object, "native", true);
    SetBool(env, object, "screenCapture", true);
    return object;
}

napi_value MakeCaptureStats(napi_env env)
{
    std::lock_guard<std::mutex> sessionGuard(g_session.sessionMutex);
    bool hasStableFrame = false;
    {
        std::lock_guard<std::mutex> frameGuard(g_session.frameMutex);
        hasStableFrame = g_session.latestFrame != nullptr && !g_session.latestFrame->empty();
    }
    const int32_t code = g_session.lastCode.load();
    napi_value object = MakeBaseResult(env, code == AV_SCREEN_CAPTURE_ERR_OK, code, g_session.message);
    SetBool(env, object, "prepared", g_session.prepared.load());
    SetBool(env, object, "running", g_session.running.load());
    SetBool(env, object, "hasStableFrame", hasStableFrame);
    SetNumber(env, object, "videoFrames", static_cast<double>(g_session.videoFrames.load()));
    SetNumber(env, object, "acceptedFrames", static_cast<double>(g_session.acceptedFrames.load()));
    SetNumber(env, object, "rejectedFrames", static_cast<double>(g_session.rejectedFrames.load()));
    SetNumber(env, object, "width", g_session.width);
    SetNumber(env, object, "height", g_session.height);
    SetNumber(env, object, "stateCode", g_session.stateCode.load());
    SetNumber(env, object, "errorCode", g_session.errorCode.load());
    return object;
}

int32_t PrepareCaptureLocked(int32_t width, int32_t height, bool recognitionEnabled)
{
    ReleaseCaptureLocked();
    ResetFrameState();
    g_session.recognitionEnabled.store(recognitionEnabled);
    g_session.width = width;
    g_session.height = height;
    g_session.capture = OH_AVScreenCapture_Create();
    int32_t code = g_session.capture == nullptr ? AV_SCREEN_CAPTURE_ERR_NO_MEMORY : AV_SCREEN_CAPTURE_ERR_OK;
    if (code == AV_SCREEN_CAPTURE_ERR_OK) code = OH_AVScreenCapture_SetStateCallback(g_session.capture, OnCaptureState, nullptr);
    if (code == AV_SCREEN_CAPTURE_ERR_OK) code = OH_AVScreenCapture_SetErrorCallback(g_session.capture, OnCaptureError, nullptr);
    if (code == AV_SCREEN_CAPTURE_ERR_OK) code = OH_AVScreenCapture_SetDataCallback(g_session.capture, OnCaptureBuffer, nullptr);
    if (code == AV_SCREEN_CAPTURE_ERR_OK) {
        OH_AVScreenCaptureConfig config = MakeRawCaptureConfig(width, height);
        code = OH_AVScreenCapture_Init(g_session.capture, config);
    }
    if (code == AV_SCREEN_CAPTURE_ERR_OK) {
        OH_AVScreenCapture_CaptureStrategy *strategy = OH_AVScreenCapture_CreateCaptureStrategy();
        if (strategy == nullptr) {
            code = AV_SCREEN_CAPTURE_ERR_NO_MEMORY;
        } else {
            code = OH_AVScreenCapture_StrategyForPickerPopUp(strategy, true);
            if (code == AV_SCREEN_CAPTURE_ERR_OK) {
                code = OH_AVScreenCapture_StrategyForCanvasFollowRotation(strategy, true);
            }
            if (code == AV_SCREEN_CAPTURE_ERR_OK) code = OH_AVScreenCapture_SetCaptureStrategy(g_session.capture, strategy);
            const int32_t releaseCode = OH_AVScreenCapture_ReleaseCaptureStrategy(strategy);
            if (code == AV_SCREEN_CAPTURE_ERR_OK) code = releaseCode;
        }
    }
    if (code == AV_SCREEN_CAPTURE_ERR_OK) {
        const int32_t pickerModeCode =
            OH_AVScreenCapture_SetPickerMode(g_session.capture, OH_CAPTURE_PICKER_MODE_WINDOW_ONLY);
        if (pickerModeCode != AV_SCREEN_CAPTURE_ERR_OK && pickerModeCode != AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT) {
            code = pickerModeCode;
        }
    }
    g_session.lastCode.store(code);
    g_session.prepared.store(code == AV_SCREEN_CAPTURE_ERR_OK);
    g_session.message = code == AV_SCREEN_CAPTURE_ERR_OK ? "capture initialized" : "capture initialization failed";
    if (code != AV_SCREEN_CAPTURE_ERR_OK) ReleaseCaptureLocked();
    OH_LOG_Print(LOG_APP, code == AV_SCREEN_CAPTURE_ERR_OK ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
        "CAPTURE_PREPARE code=%{public}d size=%{public}dx%{public}d", code, width, height);
    return code;
}

napi_value PrepareCapture(napi_env env, napi_callback_info info)
{
    size_t argumentCount = 2;
    napi_value arguments[2]{};
    napi_get_cb_info(env, info, &argumentCount, arguments, nullptr, nullptr);
    if (argumentCount < 2) {
        return MakeBaseResult(env, false, AV_SCREEN_CAPTURE_ERR_INVALID_VAL, "width and height are required");
    }
    const int32_t width = ReadInt32(env, arguments[0]);
    const int32_t height = ReadInt32(env, arguments[1]);
    std::lock_guard<std::mutex> guard(g_session.sessionMutex);
    const int32_t code = PrepareCaptureLocked(width, height, true);
    napi_value object = MakeBaseResult(env, code == AV_SCREEN_CAPTURE_ERR_OK, code, g_session.message);
    SetBool(env, object, "prepared", g_session.prepared.load());
    SetNumber(env, object, "width", width);
    SetNumber(env, object, "height", height);
    return object;
}

napi_value PrepareReplayCapture(napi_env env, napi_callback_info info)
{
    size_t argumentCount = 5;
    napi_value arguments[5]{};
    napi_get_cb_info(env, info, &argumentCount, arguments, nullptr, nullptr);
    if (argumentCount < 5) {
        return MakeBaseResult(env, false, AV_SCREEN_CAPTURE_ERR_INVALID_VAL,
            "path, width, height, recognition and audio are required");
    }
    const std::string path = ReadUtf8(env, arguments[0]);
    const int32_t width = ReadInt32(env, arguments[1]);
    const int32_t height = ReadInt32(env, arguments[2]);
    const bool recognitionEnabled = ReadBool(env, arguments[3]);
    const bool audioEnabled = ReadBool(env, arguments[4]);
    std::lock_guard<std::mutex> guard(g_session.sessionMutex);
    int32_t code = PrepareCaptureLocked(width, height, recognitionEnabled);
    if (code == AV_SCREEN_CAPTURE_ERR_OK && !g_recorder.Prepare(path, width, height, audioEnabled)) {
        code = g_recorder.Stats().errorCode;
        ReleaseCaptureLocked();
    }
    napi_value object = MakeBaseResult(env, code == AV_SCREEN_CAPTURE_ERR_OK, code,
        code == AV_SCREEN_CAPTURE_ERR_OK ? "capture and replay pipeline initialized" : "replay initialization failed");
    SetBool(env, object, "prepared", code == AV_SCREEN_CAPTURE_ERR_OK);
    SetBool(env, object, "recognitionEnabled", recognitionEnabled);
    SetBool(env, object, "audioEnabled", audioEnabled);
    SetNumber(env, object, "width", width);
    SetNumber(env, object, "height", height);
    return object;
}

napi_value StartCapture(napi_env env, napi_callback_info)
{
    std::lock_guard<std::mutex> guard(g_session.sessionMutex);
    int32_t code = AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT;
    if (g_session.capture != nullptr && g_session.prepared.load()) {
        const pc::ReplayRecorderStats replay = g_recorder.Stats();
        const bool replayStarted = !replay.prepared || g_recorder.Start();
        code = replayStarted ? OH_AVScreenCapture_StartScreenCapture(g_session.capture) : g_recorder.Stats().errorCode;
        if (code != AV_SCREEN_CAPTURE_ERR_OK && replay.prepared) g_recorder.Stop(false);
    }
    g_session.lastCode.store(code);
    if (code == AV_SCREEN_CAPTURE_ERR_OK) g_session.running.store(true);
    g_session.message = code == AV_SCREEN_CAPTURE_ERR_OK ? "capture start requested" : "capture start failed";
    return MakeBaseResult(env, code == AV_SCREEN_CAPTURE_ERR_OK, code, g_session.message);
}

napi_value PresentWindowPicker(napi_env env, napi_callback_info)
{
    std::lock_guard<std::mutex> guard(g_session.sessionMutex);
    int32_t code = AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT;
    if (g_session.capture != nullptr && g_session.running.load()) {
        const int32_t pickerModeCode =
            OH_AVScreenCapture_SetPickerMode(g_session.capture, OH_CAPTURE_PICKER_MODE_WINDOW_ONLY);
        if (pickerModeCode == AV_SCREEN_CAPTURE_ERR_OK || pickerModeCode == AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT) {
            code = OH_AVScreenCapture_PresentPicker(g_session.capture);
        } else {
            code = pickerModeCode;
        }
    }
    g_session.lastCode.store(code);
    g_session.message = code == AV_SCREEN_CAPTURE_ERR_OK ? "window picker presented" : "window picker failed";
    return MakeBaseResult(env, code == AV_SCREEN_CAPTURE_ERR_OK, code, g_session.message);
}

napi_value TakeLatestFrame(napi_env env, napi_callback_info)
{
    std::lock_guard<std::mutex> frameGuard(g_session.frameMutex);
    if (g_session.latestFrame == nullptr || g_session.latestFrame->empty()) {
        napi_value object = MakeBaseResult(env, false, AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT,
            "no stable visible frame available");
        SetNumber(env, object, "width", g_session.width);
        SetNumber(env, object, "height", g_session.height);
        return object;
    }
    napi_value object = MakeBaseResult(env, true, AV_SCREEN_CAPTURE_ERR_OK, "stable frame copied");
    void *arrayBufferData = nullptr;
    napi_value arrayBuffer;
    napi_create_arraybuffer(env, g_session.latestFrame->size(), &arrayBufferData, &arrayBuffer);
    std::memcpy(arrayBufferData, g_session.latestFrame->data(), g_session.latestFrame->size());
    napi_set_named_property(env, object, "data", arrayBuffer);
    SetNumber(env, object, "width", g_session.width);
    SetNumber(env, object, "height", g_session.height);
    SetNumber(env, object, "strideBytes", g_session.width * RGBA_BYTES_PER_PIXEL);
    SetNumber(env, object, "hash", g_session.latestHash);
    SetNumber(env, object, "timestampUs", static_cast<double>(g_session.latestTimestampUs));
    SetCardRects(env, object, DetectTeamCards(*g_session.latestFrame, g_session.width, g_session.height));
    return object;
}

napi_value GetCaptureStats(napi_env env, napi_callback_info)
{
    return MakeCaptureStats(env);
}

napi_value MakeReplayStats(napi_env env)
{
    const pc::ReplayRecorderStats stats = g_recorder.Stats();
    napi_value object = MakeBaseResult(env, !stats.failed, stats.errorCode, stats.message);
    SetBool(env, object, "prepared", stats.prepared);
    SetBool(env, object, "running", stats.running);
    SetBool(env, object, "finalized", stats.finalized);
    SetBool(env, object, "failed", stats.failed);
    SetBool(env, object, "audioEnabled", stats.audioEnabled);
    SetBool(env, object, "recognitionEnabled", g_session.recognitionEnabled.load());
    SetNumber(env, object, "videoInputFrames", static_cast<double>(stats.videoInputFrames));
    SetNumber(env, object, "videoEncodedFrames", static_cast<double>(stats.videoEncodedFrames));
    SetNumber(env, object, "videoDroppedFrames", static_cast<double>(stats.videoDroppedFrames));
    SetNumber(env, object, "audioInputBuffers", static_cast<double>(stats.audioInputBuffers));
    SetNumber(env, object, "audioEncodedBuffers", static_cast<double>(stats.audioEncodedBuffers));
    SetNumber(env, object, "nonSilentSamples", static_cast<double>(stats.nonSilentSamples));
    SetNumber(env, object, "audioPeak", stats.audioPeak);
    SetNumber(env, object, "durationUs", static_cast<double>(stats.durationUs));
    SetNumber(env, object, "fileBytes", static_cast<double>(stats.fileBytes));
    SetNumber(env, object, "videoWidth", pc::REPLAY_VIDEO_WIDTH);
    SetNumber(env, object, "videoHeight", pc::REPLAY_VIDEO_HEIGHT);
    SetNumber(env, object, "videoFps", pc::REPLAY_VIDEO_FPS);
    SetNumber(env, object, "videoBitrate", static_cast<double>(pc::REPLAY_VIDEO_BITRATE));
    SetNumber(env, object, "audioSampleRate", pc::REPLAY_AUDIO_SAMPLE_RATE);
    SetNumber(env, object, "audioChannels", pc::REPLAY_AUDIO_CHANNELS);
    SetNumber(env, object, "audioBitrate", static_cast<double>(pc::REPLAY_AUDIO_BITRATE));
    SetString(env, object, "videoCodec", "video/avc");
    SetString(env, object, "audioCodec", "audio/mp4a-latm");
    SetString(env, object, "filePath", stats.filePath);
    return object;
}

napi_value GetReplayStats(napi_env env, napi_callback_info)
{
    return MakeReplayStats(env);
}

napi_value PrepareReplayRecorder(napi_env env, napi_callback_info info)
{
    size_t argumentCount = 2;
    napi_value arguments[2]{};
    napi_get_cb_info(env, info, &argumentCount, arguments, nullptr, nullptr);
    if (argumentCount < 2) {
        return MakeBaseResult(env, false, AV_SCREEN_CAPTURE_ERR_INVALID_VAL, "path and audio flag are required");
    }
    std::lock_guard<std::mutex> guard(g_session.sessionMutex);
    if (g_session.capture == nullptr || !g_session.prepared.load()) {
        return MakeBaseResult(env, false, AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT,
            "screen capture session is not prepared");
    }
    g_recorder.Prepare(ReadUtf8(env, arguments[0]), g_session.width, g_session.height, ReadBool(env, arguments[1]));
    return MakeReplayStats(env);
}

napi_value StartReplayRecorder(napi_env env, napi_callback_info)
{
    g_recorder.Start();
    return MakeReplayStats(env);
}

napi_value StopReplayRecorder(napi_env env, napi_callback_info)
{
    g_recorder.Stop(true);
    return MakeReplayStats(env);
}

napi_value StopCapture(napi_env env, napi_callback_info)
{
    std::lock_guard<std::mutex> guard(g_session.sessionMutex);
    int32_t code = AV_SCREEN_CAPTURE_ERR_OK;
    if (g_session.capture != nullptr) {
        if (g_session.running.load()) code = OH_AVScreenCapture_StopScreenCapture(g_session.capture);
        const int32_t releaseCode = OH_AVScreenCapture_Release(g_session.capture);
        if (code == AV_SCREEN_CAPTURE_ERR_OK) code = releaseCode;
        g_session.capture = nullptr;
    }
    g_session.prepared.store(false);
    g_session.running.store(false);
    g_session.lastCode.store(code);
    g_session.message = code == AV_SCREEN_CAPTURE_ERR_OK ? "capture stopped and released" : "capture cleanup failed";
    if (g_recorder.IsAccepting() || g_recorder.Stats().prepared) g_recorder.Stop(false);
    return MakeBaseResult(env, code == AV_SCREEN_CAPTURE_ERR_OK, code, g_session.message);
}

napi_value StopReplayCapture(napi_env env, napi_callback_info)
{
    std::lock_guard<std::mutex> guard(g_session.sessionMutex);
    int32_t code = AV_SCREEN_CAPTURE_ERR_OK;
    if (g_session.capture != nullptr) {
        if (g_session.running.load()) code = OH_AVScreenCapture_StopScreenCapture(g_session.capture);
        const int32_t releaseCode = OH_AVScreenCapture_Release(g_session.capture);
        if (code == AV_SCREEN_CAPTURE_ERR_OK) code = releaseCode;
        g_session.capture = nullptr;
    }
    g_session.prepared.store(false);
    g_session.running.store(false);
    const bool finalized = g_recorder.Stop(true);
    if (!finalized && code == AV_SCREEN_CAPTURE_ERR_OK) code = g_recorder.Stats().errorCode;
    g_session.lastCode.store(code);
    g_session.message = finalized ? "capture stopped and replay finalized" : "replay finalization failed";
    return MakeReplayStats(env);
}

napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor descriptors[] = {
        { "getBridgeInfo", nullptr, GetBridgeInfo, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prepareCapture", nullptr, PrepareCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prepareReplayCapture", nullptr, PrepareReplayCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "startCapture", nullptr, StartCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "presentWindowPicker", nullptr, PresentWindowPicker, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "getCaptureStats", nullptr, GetCaptureStats, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "getReplayStats", nullptr, GetReplayStats, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prepareReplayRecorder", nullptr, PrepareReplayRecorder, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "startReplayRecorder", nullptr, StartReplayRecorder, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "stopReplayRecorder", nullptr, StopReplayRecorder, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "takeLatestFrame", nullptr, TakeLatestFrame, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "recognizeTeamPreview", nullptr, RecognizeTeamPreview, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "stopCapture", nullptr, StopCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "stopReplayCapture", nullptr, StopReplayCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
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
