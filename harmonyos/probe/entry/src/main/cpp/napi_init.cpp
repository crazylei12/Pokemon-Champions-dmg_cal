#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>
#include <unistd.h>

#include <hilog/log.h>
#include <multimedia/player_framework/native_avbuffer.h>
#include <multimedia/player_framework/native_avscreen_capture.h>
#include <napi/native_api.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

namespace {

constexpr uint32_t PC_LOG_DOMAIN = 0x5043;
constexpr char PC_LOG_TAG[] = "PCProbeNative";

enum class CaptureKind {
    NONE,
    RAW,
    FILE_RECORDING,
};

struct CaptureSession {
    std::mutex mutex;
    OH_AVScreenCapture *capture = nullptr;
    CaptureKind kind = CaptureKind::NONE;
    std::string recorderPath;
    std::string message = "not prepared";
    std::atomic<bool> prepared{false};
    std::atomic<bool> running{false};
    std::atomic<uint64_t> videoFrames{0};
    std::atomic<uint64_t> innerAudioBuffers{0};
    std::atomic<uint64_t> microphoneBuffers{0};
    std::atomic<int32_t> firstVideoBytes{0};
    std::atomic<uint32_t> firstVideoHash{0};
    std::atomic<int64_t> firstTimestampUs{-1};
    std::atomic<int64_t> lastTimestampUs{-1};
    std::atomic<int32_t> stateCode{-1};
    std::atomic<int32_t> errorCode{0};
    std::atomic<int32_t> lastCode{0};
    std::atomic<bool> snapshotPending{false};
    std::atomic<int32_t> snapshotBytes{0};
    std::atomic<uint32_t> snapshotHash{0};
    std::atomic<int64_t> snapshotTimestampUs{-1};
    std::atomic<int32_t> frameStrideBytes{0};
    std::string snapshotPath;
    int32_t width = 0;
    int32_t height = 0;
};

CaptureSession g_session;

void SetBool(napi_env env, napi_value object, const char *name, bool value)
{
    napi_value out;
    napi_get_boolean(env, value, &out);
    napi_set_named_property(env, object, name, out);
}

void SetNumber(napi_env env, napi_value object, const char *name, double value)
{
    napi_value out;
    napi_create_double(env, value, &out);
    napi_set_named_property(env, object, name, out);
}

void SetString(napi_env env, napi_value object, const char *name, const std::string &value)
{
    napi_value out;
    napi_create_string_utf8(env, value.c_str(), value.size(), &out);
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

std::string ReadUtf8(napi_env env, napi_value value)
{
    size_t length = 0;
    napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    std::vector<char> buffer(length + 1, '\0');
    napi_get_value_string_utf8(env, value, buffer.data(), buffer.size(), &length);
    return std::string(buffer.data(), length);
}

int32_t ReadInt32(napi_env env, napi_value value)
{
    int32_t result = 0;
    napi_get_value_int32(env, value, &result);
    return result;
}

uint32_t ReadBigEndianU32(const uint8_t *bytes)
{
    return (static_cast<uint32_t>(bytes[0]) << 24U) |
        (static_cast<uint32_t>(bytes[1]) << 16U) |
        (static_cast<uint32_t>(bytes[2]) << 8U) |
        static_cast<uint32_t>(bytes[3]);
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

void OnCaptureState(OH_AVScreenCapture *, OH_AVScreenCaptureStateCode state, void *)
{
    g_session.stateCode.store(static_cast<int32_t>(state));
    if (state == OH_SCREEN_CAPTURE_STATE_STARTED) {
        g_session.running.store(true);
    } else if (state == OH_SCREEN_CAPTURE_STATE_CANCELED || state == OH_SCREEN_CAPTURE_STATE_STOPPED_BY_USER ||
        state == OH_SCREEN_CAPTURE_STATE_INTERRUPTED_BY_OTHER || state == OH_SCREEN_CAPTURE_STATE_STOPPED_BY_CALL ||
        state == OH_SCREEN_CAPTURE_STATE_STOPPED_BY_USER_SWITCHES) {
        g_session.running.store(false);
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, PC_LOG_DOMAIN, PC_LOG_TAG, "PROBE_CAPTURE_STATE %{public}d", state);
}

void OnCaptureError(OH_AVScreenCapture *, int32_t errorCode, void *)
{
    g_session.errorCode.store(errorCode);
    g_session.running.store(false);
    OH_LOG_Print(LOG_APP, LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG, "PROBE_CAPTURE_ERROR %{public}d", errorCode);
}

void OnCaptureBuffer(OH_AVScreenCapture *, OH_AVBuffer *buffer, OH_AVScreenCaptureBufferType type,
    int64_t timestamp, void *)
{
    if (type == OH_SCREEN_CAPTURE_BUFFERTYPE_VIDEO) {
        const uint64_t frameIndex = g_session.videoFrames.fetch_add(1) + 1;
        if (g_session.firstTimestampUs.load() < 0) {
            g_session.firstTimestampUs.store(timestamp);
        }
        g_session.lastTimestampUs.store(timestamp);
        if (buffer != nullptr) {
            uint8_t *address = OH_AVBuffer_GetAddr(buffer);
            const int32_t capacity = OH_AVBuffer_GetCapacity(buffer);
            if (address != nullptr && capacity > 0) {
                if (frameIndex == 1) {
                    const size_t hashLength = static_cast<size_t>(std::min(capacity, 16384));
                    g_session.firstVideoBytes.store(capacity);
                    g_session.firstVideoHash.store(Fnv1a(address, hashLength));
                }
                if (g_session.snapshotPending.exchange(false)) {
                    std::string snapshotPath;
                    int32_t height = 0;
                    {
                        std::lock_guard<std::mutex> guard(g_session.mutex);
                        snapshotPath = g_session.snapshotPath;
                        height = g_session.height;
                    }
                    std::ofstream output(snapshotPath, std::ios::binary | std::ios::trunc);
                    output.write(reinterpret_cast<const char *>(address), capacity);
                    const bool saved = output.good();
                    output.close();
                    g_session.snapshotBytes.store(saved ? capacity : -1);
                    g_session.snapshotHash.store(saved ? Fnv1a(address, static_cast<size_t>(capacity)) : 0);
                    g_session.snapshotTimestampUs.store(timestamp);
                    g_session.frameStrideBytes.store(
                        saved && height > 0 && capacity % height == 0 ? capacity / height : 0);
                    OH_LOG_Print(LOG_APP, saved ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
                        "PROBE_FRAME_SNAPSHOT_%{public}s bytes=%{public}d stride=%{public}d hash=%{public}u path=%{public}s",
                        saved ? "PASS" : "FAIL", capacity, g_session.frameStrideBytes.load(),
                        g_session.snapshotHash.load(), snapshotPath.c_str());
                }
            }
        }
    } else if (type == OH_SCREEN_CAPTURE_BUFFERTYPE_AUDIO_INNER) {
        g_session.innerAudioBuffers.fetch_add(1);
    } else if (type == OH_SCREEN_CAPTURE_BUFFERTYPE_AUDIO_MIC) {
        g_session.microphoneBuffers.fetch_add(1);
    }
}

void ResetStats()
{
    g_session.videoFrames.store(0);
    g_session.innerAudioBuffers.store(0);
    g_session.microphoneBuffers.store(0);
    g_session.firstVideoBytes.store(0);
    g_session.firstVideoHash.store(0);
    g_session.firstTimestampUs.store(-1);
    g_session.lastTimestampUs.store(-1);
    g_session.stateCode.store(-1);
    g_session.errorCode.store(0);
    g_session.lastCode.store(0);
    g_session.snapshotPending.store(false);
    g_session.snapshotBytes.store(0);
    g_session.snapshotHash.store(0);
    g_session.snapshotTimestampUs.store(-1);
    g_session.frameStrideBytes.store(0);
    g_session.snapshotPath.clear();
}

void ReleaseCaptureLocked()
{
    if (g_session.capture != nullptr) {
        if (g_session.running.load()) {
            if (g_session.kind == CaptureKind::FILE_RECORDING) {
                OH_AVScreenCapture_StopScreenRecording(g_session.capture);
            } else {
                OH_AVScreenCapture_StopScreenCapture(g_session.capture);
            }
        }
        OH_AVScreenCapture_Release(g_session.capture);
        g_session.capture = nullptr;
    }
    g_session.kind = CaptureKind::NONE;
    g_session.prepared.store(false);
    g_session.running.store(false);
    g_session.snapshotPending.store(false);
}

napi_value MakeCaptureStats(napi_env env)
{
    std::lock_guard<std::mutex> guard(g_session.mutex);
    const int32_t code = g_session.lastCode.load();
    napi_value object = MakeBaseResult(env, code == AV_SCREEN_CAPTURE_ERR_OK, code, g_session.message);
    SetBool(env, object, "prepared", g_session.prepared.load());
    SetBool(env, object, "running", g_session.running.load());
    SetBool(env, object, "recording", g_session.kind == CaptureKind::FILE_RECORDING);
    SetNumber(env, object, "videoFrames", static_cast<double>(g_session.videoFrames.load()));
    SetNumber(env, object, "innerAudioBuffers", static_cast<double>(g_session.innerAudioBuffers.load()));
    SetNumber(env, object, "microphoneBuffers", static_cast<double>(g_session.microphoneBuffers.load()));
    SetNumber(env, object, "firstVideoBytes", g_session.firstVideoBytes.load());
    SetNumber(env, object, "firstVideoHash", g_session.firstVideoHash.load());
    SetNumber(env, object, "firstTimestampUs", static_cast<double>(g_session.firstTimestampUs.load()));
    SetNumber(env, object, "lastTimestampUs", static_cast<double>(g_session.lastTimestampUs.load()));
    SetNumber(env, object, "stateCode", g_session.stateCode.load());
    SetNumber(env, object, "errorCode", g_session.errorCode.load());
    SetBool(env, object, "snapshotPending", g_session.snapshotPending.load());
    SetNumber(env, object, "snapshotBytes", g_session.snapshotBytes.load());
    SetNumber(env, object, "snapshotHash", g_session.snapshotHash.load());
    SetNumber(env, object, "snapshotTimestampUs", static_cast<double>(g_session.snapshotTimestampUs.load()));
    SetNumber(env, object, "frameStrideBytes", g_session.frameStrideBytes.load());
    SetString(env, object, "snapshotPath", g_session.snapshotPath);
    SetNumber(env, object, "width", g_session.width);
    SetNumber(env, object, "height", g_session.height);
    return object;
}

OH_AVScreenCaptureConfig MakeCaptureConfig(CaptureKind kind, int32_t width, int32_t height)
{
    OH_AVScreenCaptureConfig config{};
    // Phone/tablet window selection is driven by the API 23+ picker strategy.
    // OH_CAPTURE_SPECIFIED_WINDOW without a mission ID is rejected by the phone emulator during Init.
    config.captureMode = OH_CAPTURE_HOME_SCREEN;
    config.dataType = kind == CaptureKind::FILE_RECORDING ? OH_CAPTURE_FILE : OH_ORIGINAL_STREAM;

    config.audioInfo.micCapInfo.audioSampleRate = 0;
    config.audioInfo.micCapInfo.audioChannels = 0;
    config.audioInfo.micCapInfo.audioSource = OH_SOURCE_INVALID;
    config.audioInfo.innerCapInfo.audioSampleRate = 48000;
    config.audioInfo.innerCapInfo.audioChannels = 2;
    config.audioInfo.innerCapInfo.audioSource = OH_APP_PLAYBACK;
    config.audioInfo.audioEncInfo.audioBitrate = 128000;
    config.audioInfo.audioEncInfo.audioCodecformat = OH_AAC_LC;

    config.videoInfo.videoCapInfo.displayId = 0;
    config.videoInfo.videoCapInfo.missionIDs = nullptr;
    config.videoInfo.videoCapInfo.missionIDsLen = 0;
    config.videoInfo.videoCapInfo.videoFrameWidth = width;
    config.videoInfo.videoCapInfo.videoFrameHeight = height;
    config.videoInfo.videoCapInfo.videoSource = OH_VIDEO_SOURCE_SURFACE_RGBA;
    config.videoInfo.videoEncInfo.videoCodec = OH_H264;
    config.videoInfo.videoEncInfo.videoBitrate = 12000000;
    config.videoInfo.videoEncInfo.videoFrameRate = 30;

    if (kind == CaptureKind::FILE_RECORDING) {
        config.recorderInfo.url = g_session.recorderPath.data();
        config.recorderInfo.urlLen = static_cast<uint32_t>(g_session.recorderPath.size());
        config.recorderInfo.fileFormat = CFT_MPEG_4;
    }
    return config;
}

napi_value PrepareCapture(napi_env env, napi_callback_info info, CaptureKind kind, bool useMissionId)
{
    const size_t requiredArgumentCount = (kind == CaptureKind::FILE_RECORDING ? 3U : 2U) +
        (useMissionId ? 1U : 0U);
    size_t argumentCount = requiredArgumentCount;
    napi_value arguments[4]{};
    napi_get_cb_info(env, info, &argumentCount, arguments, nullptr, nullptr);
    if (argumentCount < requiredArgumentCount) {
        return MakeBaseResult(env, false, AV_SCREEN_CAPTURE_ERR_INVALID_VAL, "missing capture arguments");
    }

    const size_t missionIdIndex = kind == CaptureKind::FILE_RECORDING ? 1U : 0U;
    const size_t widthIndex = (kind == CaptureKind::FILE_RECORDING ? 1U : 0U) +
        (useMissionId ? 1U : 0U);
    const size_t heightIndex = widthIndex + 1;
    const int32_t missionId = useMissionId ? ReadInt32(env, arguments[missionIdIndex]) : -1;
    const int32_t width = ReadInt32(env, arguments[widthIndex]);
    const int32_t height = ReadInt32(env, arguments[heightIndex]);

    {
        std::lock_guard<std::mutex> guard(g_session.mutex);
        ReleaseCaptureLocked();
        ResetStats();
        g_session.kind = kind;
        g_session.width = width;
        g_session.height = height;
        if (kind == CaptureKind::FILE_RECORDING) {
            g_session.recorderPath = ReadUtf8(env, arguments[0]);
        } else {
            g_session.recorderPath.clear();
        }

        g_session.capture = OH_AVScreenCapture_Create();
        if (g_session.capture == nullptr) {
            g_session.lastCode.store(AV_SCREEN_CAPTURE_ERR_NO_MEMORY);
            g_session.message = "OH_AVScreenCapture_Create returned null";
        } else {
            int32_t code = OH_AVScreenCapture_SetStateCallback(g_session.capture, OnCaptureState, nullptr);
            if (code == AV_SCREEN_CAPTURE_ERR_OK) {
                code = OH_AVScreenCapture_SetErrorCallback(g_session.capture, OnCaptureError, nullptr);
            }
            if (code == AV_SCREEN_CAPTURE_ERR_OK && kind == CaptureKind::RAW) {
                code = OH_AVScreenCapture_SetDataCallback(g_session.capture, OnCaptureBuffer, nullptr);
            }
            if (code == AV_SCREEN_CAPTURE_ERR_OK) {
                OH_AVScreenCaptureConfig config = MakeCaptureConfig(kind, width, height);
                int32_t selectedMissionId = missionId;
                if (useMissionId) {
                    config.captureMode = OH_CAPTURE_SPECIFIED_WINDOW;
                    config.videoInfo.videoCapInfo.missionIDs = &selectedMissionId;
                    config.videoInfo.videoCapInfo.missionIDsLen = 1;
                }
                code = OH_AVScreenCapture_Init(g_session.capture, config);
                OH_LOG_Print(LOG_APP, code == AV_SCREEN_CAPTURE_ERR_OK ? LOG_INFO : LOG_ERROR,
                    PC_LOG_DOMAIN, PC_LOG_TAG,
                    "PROBE_CAPTURE_STEP init code=%{public}d mission=%{public}d", code, missionId);
            }
            if (code == AV_SCREEN_CAPTURE_ERR_OK) {
                OH_AVScreenCapture_CaptureStrategy *strategy = OH_AVScreenCapture_CreateCaptureStrategy();
                if (strategy == nullptr) {
                    code = AV_SCREEN_CAPTURE_ERR_NO_MEMORY;
                } else {
                    code = OH_AVScreenCapture_StrategyForPickerPopUp(strategy, !useMissionId);
                    if (code == AV_SCREEN_CAPTURE_ERR_OK) {
                        code = OH_AVScreenCapture_StrategyForCanvasFollowRotation(strategy, true);
                    }
                    if (code == AV_SCREEN_CAPTURE_ERR_OK) {
                        code = OH_AVScreenCapture_SetCaptureStrategy(g_session.capture, strategy);
                    }
                    const int32_t releaseStrategyCode = OH_AVScreenCapture_ReleaseCaptureStrategy(strategy);
                    if (code == AV_SCREEN_CAPTURE_ERR_OK) {
                        code = releaseStrategyCode;
                    }
                }
                OH_LOG_Print(LOG_APP, code == AV_SCREEN_CAPTURE_ERR_OK ? LOG_INFO : LOG_ERROR,
                    PC_LOG_DOMAIN, PC_LOG_TAG, "PROBE_CAPTURE_STEP picker_strategy code=%{public}d", code);
            }
            if (code == AV_SCREEN_CAPTURE_ERR_OK && !useMissionId) {
                const int32_t pickerModeCode =
                    OH_AVScreenCapture_SetPickerMode(g_session.capture, OH_CAPTURE_PICKER_MODE_WINDOW_ONLY);
                // The API 24 phone emulator rejects picker-mode narrowing before authorization, while the
                // capture strategy can still display the system picker. Keep the restriction on supporting
                // devices, but do not discard an otherwise valid session on this documented probe path.
                if (pickerModeCode != AV_SCREEN_CAPTURE_ERR_OK &&
                    pickerModeCode != AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT) {
                    code = pickerModeCode;
                }
                OH_LOG_Print(LOG_APP, pickerModeCode == AV_SCREEN_CAPTURE_ERR_OK ? LOG_INFO : LOG_WARN,
                    PC_LOG_DOMAIN, PC_LOG_TAG, "PROBE_CAPTURE_STEP picker_mode code=%{public}d", pickerModeCode);
            }
            g_session.lastCode.store(code);
            g_session.prepared.store(code == AV_SCREEN_CAPTURE_ERR_OK);
            g_session.message = code == AV_SCREEN_CAPTURE_ERR_OK ? "capture initialized" : "capture initialization failed";
            OH_LOG_Print(LOG_APP, code == AV_SCREEN_CAPTURE_ERR_OK ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
                "PROBE_CAPTURE_PREPARE kind=%{public}d code=%{public}d mission=%{public}d size=%{public}dx%{public}d",
                static_cast<int32_t>(kind), code, missionId, width, height);
        }
    }
    return MakeCaptureStats(env);
}

napi_value ProbeNativeCapture(napi_env env, napi_callback_info)
{
    OH_AVScreenCapture *capture = OH_AVScreenCapture_Create();
    if (capture == nullptr) {
        return MakeBaseResult(env, false, AV_SCREEN_CAPTURE_ERR_NO_MEMORY, "native screen capture create failed");
    }
    const int32_t code = OH_AVScreenCapture_Release(capture);
    const bool ok = code == AV_SCREEN_CAPTURE_ERR_OK;
    OH_LOG_Print(LOG_APP, ok ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
        "PROBE_NATIVE_CAPTURE_%{public}s code=%{public}d", ok ? "PASS" : "FAIL", code);
    return MakeBaseResult(env, ok, code, ok ? "native screen capture create/release passed" : "release failed");
}

napi_value ProbeOpenCvTemplate(napi_env env, napi_callback_info info)
{
    size_t argumentCount = 1;
    napi_value arguments[1]{};
    napi_get_cb_info(env, info, &argumentCount, arguments, nullptr, nullptr);
    if (argumentCount != 1) {
        return MakeBaseResult(env, false, -1, "template path required");
    }
    const std::string path = ReadUtf8(env, arguments[0]);
    std::ifstream input(path, std::ios::binary);
    std::vector<uint8_t> header(28, 0);
    input.read(reinterpret_cast<char *>(header.data()), static_cast<std::streamsize>(header.size()));
    const bool headerRead = input.gcount() == static_cast<std::streamsize>(header.size());
    const std::string magic = headerRead ? std::string(reinterpret_cast<const char *>(header.data()), 8) : "";
    const uint32_t version = headerRead ? ReadBigEndianU32(header.data() + 8) : 0;
    const uint32_t width = headerRead ? ReadBigEndianU32(header.data() + 12) : 0;
    const uint32_t height = headerRead ? ReadBigEndianU32(header.data() + 16) : 0;
    const uint32_t recordBytes = headerRead ? ReadBigEndianU32(header.data() + 20) : 0;
    const uint32_t recordCount = headerRead ? ReadBigEndianU32(header.data() + 24) : 0;

    cv::Mat source(32, 32, CV_32FC1);
    cv::RNG random(0x5043U);
    random.fill(source, cv::RNG::UNIFORM, 0.0F, 1.0F);
    const cv::Mat templ = source(cv::Rect(9, 11, 8, 7)).clone();
    cv::Mat scores;
    cv::matchTemplate(source, templ, scores, cv::TM_CCOEFF_NORMED);
    double minScore = 0.0;
    double maxScore = 0.0;
    cv::Point minLocation;
    cv::Point maxLocation;
    cv::minMaxLoc(scores, &minScore, &maxScore, &minLocation, &maxLocation);

    const bool headerOk = magic == "PTVFEAT2" && version == 2 && width == 96 && height == 16 &&
        recordBytes == 384 && recordCount > 1000;
    const bool matchOk = maxScore > 0.9999 && maxLocation.x == 9 && maxLocation.y == 11;
    const bool ok = headerOk && matchOk;
    napi_value object = MakeBaseResult(env, ok, ok ? 0 : -2,
        ok ? "OpenCV 4.13 template probe passed" : "template header or match result mismatch");
    SetString(env, object, "magic", magic);
    SetNumber(env, object, "version", version);
    SetNumber(env, object, "templateWidth", width);
    SetNumber(env, object, "templateHeight", height);
    SetNumber(env, object, "recordCount", recordCount);
    SetNumber(env, object, "matchScore", maxScore);
    OH_LOG_Print(LOG_APP, ok ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
        "PROBE_OPENCV_%{public}s version=%{public}u records=%{public}u score=%{public}f",
        ok ? "PASS" : "FAIL", version, recordCount, maxScore);
    return object;
}

napi_value ProbeOpenCvTemplateFd(napi_env env, napi_callback_info info)
{
    size_t argumentCount = 3;
    napi_value arguments[3]{};
    napi_get_cb_info(env, info, &argumentCount, arguments, nullptr, nullptr);
    if (argumentCount != 3) {
        return MakeBaseResult(env, false, -1, "raw file descriptor, offset and length required");
    }
    int32_t fd = -1;
    int64_t offset = 0;
    int64_t length = 0;
    napi_get_value_int32(env, arguments[0], &fd);
    napi_get_value_int64(env, arguments[1], &offset);
    napi_get_value_int64(env, arguments[2], &length);
    std::vector<uint8_t> header(28, 0);
    const ssize_t bytesRead = pread(fd, header.data(), header.size(), static_cast<off_t>(offset));
    const bool headerRead = bytesRead == static_cast<ssize_t>(header.size()) && length >= 28;
    const std::string magic = headerRead ? std::string(reinterpret_cast<const char *>(header.data()), 8) : "";
    const uint32_t version = headerRead ? ReadBigEndianU32(header.data() + 8) : 0;
    const uint32_t width = headerRead ? ReadBigEndianU32(header.data() + 12) : 0;
    const uint32_t height = headerRead ? ReadBigEndianU32(header.data() + 16) : 0;
    const uint32_t recordBytes = headerRead ? ReadBigEndianU32(header.data() + 20) : 0;
    const uint32_t recordCount = headerRead ? ReadBigEndianU32(header.data() + 24) : 0;

    cv::Mat source(32, 32, CV_32FC1);
    cv::RNG random(0x5043U);
    random.fill(source, cv::RNG::UNIFORM, 0.0F, 1.0F);
    const cv::Mat templ = source(cv::Rect(9, 11, 8, 7)).clone();
    cv::Mat scores;
    cv::matchTemplate(source, templ, scores, cv::TM_CCOEFF_NORMED);
    double minScore = 0.0;
    double maxScore = 0.0;
    cv::Point minLocation;
    cv::Point maxLocation;
    cv::minMaxLoc(scores, &minScore, &maxScore, &minLocation, &maxLocation);

    const bool headerOk = magic == "PTVFEAT2" && version == 2 && width == 96 && height == 16 &&
        recordBytes == 384 && recordCount > 1000;
    const bool matchOk = maxScore > 0.9999 && maxLocation.x == 9 && maxLocation.y == 11;
    const bool ok = headerOk && matchOk;
    napi_value object = MakeBaseResult(env, ok, ok ? 0 : -2,
        ok ? "OpenCV 4.13 packaged template probe passed" : "template descriptor or match result mismatch");
    SetString(env, object, "magic", magic);
    SetNumber(env, object, "version", version);
    SetNumber(env, object, "templateWidth", width);
    SetNumber(env, object, "templateHeight", height);
    SetNumber(env, object, "recordCount", recordCount);
    SetNumber(env, object, "matchScore", maxScore);
    OH_LOG_Print(LOG_APP, ok ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
        "PROBE_OPENCV_FD_%{public}s length=%{public}lld records=%{public}u score=%{public}f",
        ok ? "PASS" : "FAIL", static_cast<long long>(length), recordCount, maxScore);
    return object;
}

napi_value PrepareRawCapture(napi_env env, napi_callback_info info)
{
    return PrepareCapture(env, info, CaptureKind::RAW, false);
}

napi_value PrepareRawWindowCapture(napi_env env, napi_callback_info info)
{
    return PrepareCapture(env, info, CaptureKind::RAW, true);
}

napi_value PrepareFileRecording(napi_env env, napi_callback_info info)
{
    return PrepareCapture(env, info, CaptureKind::FILE_RECORDING, false);
}

napi_value PrepareFileWindowRecording(napi_env env, napi_callback_info info)
{
    return PrepareCapture(env, info, CaptureKind::FILE_RECORDING, true);
}

napi_value PresentWindowPicker(napi_env env, napi_callback_info)
{
    {
        std::lock_guard<std::mutex> guard(g_session.mutex);
        if (g_session.capture == nullptr || !g_session.prepared.load()) {
            g_session.lastCode.store(AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT);
            g_session.message = "capture is not prepared";
        } else {
            const int32_t pickerModeCode =
                OH_AVScreenCapture_SetPickerMode(g_session.capture, OH_CAPTURE_PICKER_MODE_WINDOW_ONLY);
            int32_t code = pickerModeCode;
            if (pickerModeCode == AV_SCREEN_CAPTURE_ERR_OK ||
                pickerModeCode == AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT) {
                code = OH_AVScreenCapture_PresentPicker(g_session.capture);
            }
            g_session.lastCode.store(code);
            g_session.message = code == AV_SCREEN_CAPTURE_ERR_OK ? "window picker presented" : "window picker failed";
            OH_LOG_Print(LOG_APP, code == AV_SCREEN_CAPTURE_ERR_OK ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
                "PROBE_CAPTURE_PICKER modeCode=%{public}d presentCode=%{public}d", pickerModeCode, code);
        }
    }
    return MakeCaptureStats(env);
}

napi_value StartPreparedCapture(napi_env env, napi_callback_info)
{
    {
        std::lock_guard<std::mutex> guard(g_session.mutex);
        if (g_session.capture == nullptr || !g_session.prepared.load()) {
            g_session.lastCode.store(AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT);
            g_session.message = "capture is not prepared";
        } else {
            const int32_t code = g_session.kind == CaptureKind::FILE_RECORDING ?
                OH_AVScreenCapture_StartScreenRecording(g_session.capture) :
                OH_AVScreenCapture_StartScreenCapture(g_session.capture);
            g_session.lastCode.store(code);
            g_session.running.store(code == AV_SCREEN_CAPTURE_ERR_OK);
            g_session.message = code == AV_SCREEN_CAPTURE_ERR_OK ? "capture start requested" : "capture start failed";
            OH_LOG_Print(LOG_APP, code == AV_SCREEN_CAPTURE_ERR_OK ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
                "PROBE_CAPTURE_START code=%{public}d", code);
        }
    }
    return MakeCaptureStats(env);
}

napi_value RequestFrameSnapshot(napi_env env, napi_callback_info info)
{
    size_t argumentCount = 1;
    napi_value arguments[1]{};
    napi_get_cb_info(env, info, &argumentCount, arguments, nullptr, nullptr);
    if (argumentCount < 1) {
        return MakeBaseResult(env, false, AV_SCREEN_CAPTURE_ERR_INVALID_VAL, "missing snapshot path");
    }

    {
        std::lock_guard<std::mutex> guard(g_session.mutex);
        if (g_session.capture == nullptr || g_session.kind != CaptureKind::RAW || !g_session.running.load()) {
            g_session.lastCode.store(AV_SCREEN_CAPTURE_ERR_OPERATE_NOT_PERMIT);
            g_session.message = "raw capture is not running";
        } else {
            g_session.snapshotPath = ReadUtf8(env, arguments[0]);
            g_session.snapshotBytes.store(0);
            g_session.snapshotHash.store(0);
            g_session.snapshotTimestampUs.store(-1);
            g_session.frameStrideBytes.store(0);
            g_session.snapshotPending.store(true);
            g_session.lastCode.store(AV_SCREEN_CAPTURE_ERR_OK);
            g_session.message = "next video frame snapshot armed";
            OH_LOG_Print(LOG_APP, LOG_INFO, PC_LOG_DOMAIN, PC_LOG_TAG,
                "PROBE_FRAME_SNAPSHOT_ARMED path=%{public}s", g_session.snapshotPath.c_str());
        }
    }
    return MakeCaptureStats(env);
}

napi_value StopCapture(napi_env env, napi_callback_info)
{
    {
        std::lock_guard<std::mutex> guard(g_session.mutex);
        int32_t code = AV_SCREEN_CAPTURE_ERR_OK;
        if (g_session.capture != nullptr) {
            if (g_session.running.load()) {
                code = g_session.kind == CaptureKind::FILE_RECORDING ?
                    OH_AVScreenCapture_StopScreenRecording(g_session.capture) :
                    OH_AVScreenCapture_StopScreenCapture(g_session.capture);
            }
            const int32_t releaseCode = OH_AVScreenCapture_Release(g_session.capture);
            if (code == AV_SCREEN_CAPTURE_ERR_OK) {
                code = releaseCode;
            }
            g_session.capture = nullptr;
        }
        g_session.lastCode.store(code);
        g_session.running.store(false);
        g_session.prepared.store(false);
        g_session.message = code == AV_SCREEN_CAPTURE_ERR_OK ? "capture stopped and released" : "capture cleanup failed";
        OH_LOG_Print(LOG_APP, code == AV_SCREEN_CAPTURE_ERR_OK ? LOG_INFO : LOG_ERROR, PC_LOG_DOMAIN, PC_LOG_TAG,
            "PROBE_CAPTURE_STOP code=%{public}d frames=%{public}llu audio=%{public}llu", code,
            static_cast<unsigned long long>(g_session.videoFrames.load()),
            static_cast<unsigned long long>(g_session.innerAudioBuffers.load()));
    }
    return MakeCaptureStats(env);
}

napi_value GetCaptureStats(napi_env env, napi_callback_info)
{
    return MakeCaptureStats(env);
}

napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor descriptors[] = {
        { "probeNativeCapture", nullptr, ProbeNativeCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "probeOpenCvTemplate", nullptr, ProbeOpenCvTemplate, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "probeOpenCvTemplateFd", nullptr, ProbeOpenCvTemplateFd, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prepareRawCapture", nullptr, PrepareRawCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prepareRawWindowCapture", nullptr, PrepareRawWindowCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prepareFileRecording", nullptr, PrepareFileRecording, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prepareFileWindowRecording", nullptr, PrepareFileWindowRecording, nullptr, nullptr, nullptr, napi_default,
            nullptr },
        { "presentWindowPicker", nullptr, PresentWindowPicker, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "startPreparedCapture", nullptr, StartPreparedCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "requestFrameSnapshot", nullptr, RequestFrameSnapshot, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "stopCapture", nullptr, StopCapture, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "getCaptureStats", nullptr, GetCaptureStats, nullptr, nullptr, nullptr, napi_default, nullptr },
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
    .nm_modname = "pcprobe",
    .nm_priv = nullptr,
    .reserved = { nullptr },
};

extern "C" __attribute__((constructor)) void RegisterPcProbeModule()
{
    napi_module_register(&g_module);
}
