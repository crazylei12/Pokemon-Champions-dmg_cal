#include "replay_recorder.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <hilog/log.h>
#include <multimedia/player_framework/native_avbuffer.h>
#include <multimedia/player_framework/native_avcodec_audiocodec.h>
#include <multimedia/player_framework/native_avcodec_base.h>
#include <multimedia/player_framework/native_avcodec_videoencoder.h>
#include <multimedia/player_framework/native_avformat.h>
#include <multimedia/player_framework/native_avmuxer.h>

namespace pc {
namespace {

constexpr uint32_t PC_REPLAY_LOG_DOMAIN = 0x5043;
constexpr char PC_REPLAY_LOG_TAG[] = "PCReplayRecorder";
constexpr size_t PCM_BYTES_PER_FRAME = REPLAY_AUDIO_CHANNELS * sizeof(int16_t);
constexpr size_t AAC_INPUT_FRAMES = 1024;
constexpr size_t AAC_INPUT_BYTES = AAC_INPUT_FRAMES * PCM_BYTES_PER_FRAME;
constexpr int64_t CODEC_QUERY_TIMEOUT_US = 20'000;
constexpr size_t MAX_VIDEO_QUEUE = 2;
constexpr size_t MAX_AUDIO_QUEUE = 96;

struct VideoProfile {
    int32_t width;
    int32_t height;
    int32_t fps;
    int64_t bitrate;
};

constexpr VideoProfile VIDEO_PROFILES[] = {
    {960, 540, 24, 1'500'000},
    {854, 480, 20, 1'000'000},
    {640, 360, 20, 750'000},
};

uint8_t ClampByte(int32_t value)
{
    return static_cast<uint8_t>(std::clamp(value, 0, 255));
}

int64_t FileSize(const std::string &path)
{
    struct stat info {};
    return stat(path.c_str(), &info) == 0 ? static_cast<int64_t>(info.st_size) : 0;
}

} // namespace

ReplayRecorder::~ReplayRecorder()
{
    Stop(false);
}

bool ReplayRecorder::Prepare(const std::string &path, int32_t sourceWidth, int32_t sourceHeight, bool audioEnabled)
{
    Stop(false);
    if (path.empty() || sourceWidth <= 0 || sourceHeight <= 0) {
        SetFailure(AV_ERR_INVALID_VAL, "invalid replay output path or source size");
        return false;
    }
    filePath_ = path;
    sourceWidth_ = sourceWidth;
    sourceHeight_ = sourceHeight;
    videoWidth_ = REPLAY_VIDEO_WIDTH;
    videoHeight_ = REPLAY_VIDEO_HEIGHT;
    videoFps_ = REPLAY_VIDEO_FPS;
    videoBitrate_ = REPLAY_VIDEO_BITRATE;
    audioEnabled_.store(audioEnabled);
    failed_.store(false);
    finalized_.store(false);
    stopping_.store(false);
    errorCode_.store(AV_ERR_OK);
    videoInputFrames_.store(0);
    videoEncodedFrames_.store(0);
    videoDroppedFrames_.store(0);
    audioInputBuffers_.store(0);
    audioEncodedBuffers_.store(0);
    nonSilentSamples_.store(0);
    audioPeak_.store(0);
    durationUs_.store(0);
    acceptedVideoSequence_.store(0);
    videoInputDone_.store(false);
    audioInputDone_.store(!audioEnabled);
    videoOutputDone_.store(false);
    audioOutputDone_.store(!audioEnabled);
    {
        std::lock_guard<std::mutex> videoGuard(videoMutex_);
        videoQueue_.clear();
    }
    {
        std::lock_guard<std::mutex> audioGuard(audioMutex_);
        audioQueue_.clear();
        audioPending_.clear();
    }
    if (!PrepareVideoEncoder() || (audioEnabled && !PrepareAudioEncoder()) || !PrepareMuxer()) {
        ReleaseCodecsAndMuxer();
        unlink(filePath_.c_str());
        return false;
    }
    prepared_.store(true);
    {
        std::lock_guard<std::mutex> guard(stateMutex_);
        message_ = "H.264/AAC-LC MP4 pipeline prepared";
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, PC_REPLAY_LOG_DOMAIN, PC_REPLAY_LOG_TAG,
        "REPLAY_PREPARED source=%{public}dx%{public}d output=%{public}dx%{public}d@%{public}d audio=%{public}d",
        sourceWidth, sourceHeight, videoWidth_, videoHeight_, videoFps_, audioEnabled ? 1 : 0);
    return true;
}

bool ReplayRecorder::PrepareMuxer()
{
    outputFd_ = open(filePath_.c_str(), O_CREAT | O_RDWR | O_TRUNC, S_IRUSR | S_IWUSR);
    if (outputFd_ < 0) {
        SetFailure(-errno, "cannot create private replay file");
        return false;
    }
    muxer_ = OH_AVMuxer_Create(outputFd_, AV_OUTPUT_FORMAT_MPEG_4);
    if (muxer_ == nullptr) {
        SetFailure(AV_ERR_NO_MEMORY, "cannot create MP4 muxer");
        return false;
    }

    OH_AVFormat *video = OH_AVFormat_CreateVideoFormat(
        OH_AVCODEC_MIMETYPE_VIDEO_AVC, videoWidth_, videoHeight_);
    if (video == nullptr) {
        SetFailure(AV_ERR_NO_MEMORY, "cannot create video track format");
        return false;
    }
    OH_AVFormat_SetDoubleValue(video, OH_MD_KEY_FRAME_RATE, videoFps_);
    OH_AVFormat_SetLongValue(video, OH_MD_KEY_BITRATE, videoBitrate_);
    OH_AVFormat_SetIntValue(video, OH_MD_KEY_PROFILE, AVC_PROFILE_BASELINE);
    int32_t code = OH_AVMuxer_AddTrack(muxer_, &videoTrackId_, video);
    OH_AVFormat_Destroy(video);
    if (code != AV_ERR_OK || videoTrackId_ < 0) {
        SetFailure(code, "cannot add H.264 track");
        return false;
    }

    if (audioEnabled_.load()) {
        OH_AVFormat *audio = OH_AVFormat_CreateAudioFormat(
            OH_AVCODEC_MIMETYPE_AUDIO_AAC, REPLAY_AUDIO_SAMPLE_RATE, REPLAY_AUDIO_CHANNELS);
        if (audio == nullptr) {
            SetFailure(AV_ERR_NO_MEMORY, "cannot create audio track format");
            return false;
        }
        OH_AVFormat_SetLongValue(audio, OH_MD_KEY_BITRATE, REPLAY_AUDIO_BITRATE);
        OH_AVFormat_SetIntValue(audio, OH_MD_KEY_PROFILE, AAC_PROFILE_LC);
        OH_AVFormat_SetIntValue(audio, OH_MD_KEY_AUDIO_SAMPLE_FORMAT, SAMPLE_S16LE);
        code = OH_AVMuxer_AddTrack(muxer_, &audioTrackId_, audio);
        OH_AVFormat_Destroy(audio);
        if (code != AV_ERR_OK || audioTrackId_ < 0) {
            SetFailure(code, "cannot add AAC-LC track");
            return false;
        }
    }
    code = OH_AVMuxer_Start(muxer_);
    if (code != AV_ERR_OK) {
        SetFailure(code, "cannot start MP4 muxer");
        return false;
    }
    return true;
}

bool ReplayRecorder::PrepareVideoEncoder()
{
    int32_t lastCode = AV_ERR_NO_MEMORY;
    bool encoderAvailable = false;
    for (const VideoProfile &profile : VIDEO_PROFILES) {
        videoEncoder_ = OH_VideoEncoder_CreateByMime(OH_AVCODEC_MIMETYPE_VIDEO_AVC);
        if (videoEncoder_ == nullptr) continue;
        encoderAvailable = true;
        OH_AVFormat *format = OH_AVFormat_CreateVideoFormat(
            OH_AVCODEC_MIMETYPE_VIDEO_AVC, profile.width, profile.height);
        if (format == nullptr) {
            OH_VideoEncoder_Destroy(videoEncoder_);
            videoEncoder_ = nullptr;
            continue;
        }
        OH_AVFormat_SetDoubleValue(format, OH_MD_KEY_FRAME_RATE, profile.fps);
        OH_AVFormat_SetIntValue(format, OH_MD_KEY_PIXEL_FORMAT, AV_PIXEL_FORMAT_NV12);
        OH_AVFormat_SetIntValue(format, OH_MD_KEY_VIDEO_ENCODE_BITRATE_MODE, CBR);
        OH_AVFormat_SetLongValue(format, OH_MD_KEY_BITRATE, profile.bitrate);
        OH_AVFormat_SetIntValue(format, OH_MD_KEY_PROFILE, AVC_PROFILE_BASELINE);
        OH_AVFormat_SetIntValue(format, OH_MD_KEY_I_FRAME_INTERVAL, 2'000);
        lastCode = OH_VideoEncoder_Configure(videoEncoder_, format);
        OH_AVFormat_Destroy(format);
        if (lastCode == AV_ERR_OK) lastCode = OH_VideoEncoder_Prepare(videoEncoder_);
        if (lastCode == AV_ERR_OK) {
            videoWidth_ = profile.width;
            videoHeight_ = profile.height;
            videoFps_ = profile.fps;
            videoBitrate_ = profile.bitrate;
            return true;
        }
        OH_VideoEncoder_Destroy(videoEncoder_);
        videoEncoder_ = nullptr;
    }
    SetFailure(lastCode, encoderAvailable ? "no H.264 encoder accepted replay profiles" :
        "H.264 encoder is unavailable");
    return false;
}

bool ReplayRecorder::PrepareAudioEncoder()
{
    audioEncoder_ = OH_AudioCodec_CreateByMime(OH_AVCODEC_MIMETYPE_AUDIO_AAC, true);
    if (audioEncoder_ == nullptr) {
        SetFailure(AV_ERR_NO_MEMORY, "AAC-LC encoder is unavailable");
        return false;
    }
    OH_AVFormat *format = OH_AVFormat_CreateAudioFormat(
        OH_AVCODEC_MIMETYPE_AUDIO_AAC, REPLAY_AUDIO_SAMPLE_RATE, REPLAY_AUDIO_CHANNELS);
    if (format == nullptr) {
        SetFailure(AV_ERR_NO_MEMORY, "cannot create AAC-LC encoder format");
        return false;
    }
    OH_AVFormat_SetLongValue(format, OH_MD_KEY_BITRATE, REPLAY_AUDIO_BITRATE);
    OH_AVFormat_SetIntValue(format, OH_MD_KEY_PROFILE, AAC_PROFILE_LC);
    OH_AVFormat_SetIntValue(format, OH_MD_KEY_AUDIO_SAMPLE_FORMAT, SAMPLE_S16LE);
    OH_AVFormat_SetIntValue(format, OH_MD_KEY_MAX_INPUT_SIZE, static_cast<int32_t>(AAC_INPUT_BYTES));
    int32_t code = OH_AudioCodec_Configure(audioEncoder_, format);
    OH_AVFormat_Destroy(format);
    if (code == AV_ERR_OK) code = OH_AudioCodec_Prepare(audioEncoder_);
    if (code != AV_ERR_OK) {
        SetFailure(code, "cannot configure AAC-LC encoder");
        return false;
    }
    return true;
}

bool ReplayRecorder::Start()
{
    if (!prepared_.load() || accepting_.load()) return false;
    int32_t code = OH_VideoEncoder_Start(videoEncoder_);
    if (code == AV_ERR_OK && audioEnabled_.load()) code = OH_AudioCodec_Start(audioEncoder_);
    if (code != AV_ERR_OK) {
        SetFailure(code, "cannot start replay encoders");
        return false;
    }
    startedAt_ = std::chrono::steady_clock::now();
    lastVideoAcceptedAt_ = startedAt_ - std::chrono::microseconds(1'000'000 / videoFps_);
    accepting_.store(true);
    stopping_.store(false);
    videoInputThread_ = std::thread(&ReplayRecorder::VideoInputLoop, this);
    videoOutputThread_ = std::thread(&ReplayRecorder::VideoOutputLoop, this);
    if (audioEnabled_.load()) {
        audioInputThread_ = std::thread(&ReplayRecorder::AudioInputLoop, this);
        audioOutputThread_ = std::thread(&ReplayRecorder::AudioOutputLoop, this);
    }
    {
        std::lock_guard<std::mutex> guard(stateMutex_);
        message_ = "replay recording is running";
    }
    return true;
}

void ReplayRecorder::EnqueueVideo(const std::shared_ptr<std::vector<uint8_t>> &rgba,
    int32_t width, int32_t height)
{
    if (!accepting_.load() || !rgba || rgba->empty()) return;
    const auto now = std::chrono::steady_clock::now();
    const int64_t minimumIntervalUs = 1'000'000 / videoFps_;
    if (std::chrono::duration_cast<std::chrono::microseconds>(now - lastVideoAcceptedAt_).count() < minimumIntervalUs) {
        videoDroppedFrames_.fetch_add(1);
        return;
    }
    lastVideoAcceptedAt_ = now;
    const uint64_t sequence = acceptedVideoSequence_.fetch_add(1);
    const int64_t ptsUs = static_cast<int64_t>(sequence) * minimumIntervalUs;
    {
        std::lock_guard<std::mutex> guard(videoMutex_);
        if (videoQueue_.size() >= MAX_VIDEO_QUEUE) {
            videoQueue_.pop_front();
            videoDroppedFrames_.fetch_add(1);
        }
        videoQueue_.push_back({rgba, width, height, ptsUs});
    }
    videoInputFrames_.fetch_add(1);
    durationUs_.store(std::max(durationUs_.load(), ptsUs));
    videoCondition_.notify_one();
}

void ReplayRecorder::EnqueueAudio(const uint8_t *pcm, size_t size)
{
    if (!accepting_.load() || !audioEnabled_.load() || pcm == nullptr || size < PCM_BYTES_PER_FRAME) return;
    size -= size % PCM_BYTES_PER_FRAME;
    std::vector<uint8_t> copy(pcm, pcm + size);
    const int16_t *samples = reinterpret_cast<const int16_t *>(copy.data());
    const size_t sampleCount = copy.size() / sizeof(int16_t);
    uint64_t nonSilent = 0;
    int32_t peak = audioPeak_.load();
    for (size_t index = 0; index < sampleCount; ++index) {
        const int32_t amplitude = std::abs(static_cast<int32_t>(samples[index]));
        if (amplitude > 8) ++nonSilent;
        peak = std::max(peak, amplitude);
    }
    nonSilentSamples_.fetch_add(nonSilent);
    audioPeak_.store(peak);
    {
        std::lock_guard<std::mutex> guard(audioMutex_);
        if (audioQueue_.size() >= MAX_AUDIO_QUEUE) audioQueue_.pop_front();
        audioQueue_.push_back(std::move(copy));
    }
    audioInputBuffers_.fetch_add(1);
    audioCondition_.notify_one();
}

void ReplayRecorder::SignalInputEnded()
{
    if (!prepared_.load()) return;
    accepting_.store(false);
    stopping_.store(true);
    videoCondition_.notify_all();
    audioCondition_.notify_all();
}

void ReplayRecorder::VideoInputLoop()
{
    while (true) {
        VideoFrame frame;
        {
            std::unique_lock<std::mutex> lock(videoMutex_);
            videoCondition_.wait_for(lock, std::chrono::milliseconds(50), [this] {
                return !videoQueue_.empty() || stopping_.load();
            });
            if (videoQueue_.empty()) {
                if (stopping_.load()) break;
                continue;
            }
            frame = std::move(videoQueue_.front());
            videoQueue_.pop_front();
        }
        if (!PushVideoFrame(frame)) break;
    }
    PushVideoEos();
    videoInputDone_.store(true);
}

bool ReplayRecorder::PushVideoFrame(const VideoFrame &frame)
{
    std::vector<uint8_t> nv12;
    ConvertRgbaToLetterboxedNv12(*frame.rgba, frame.width, frame.height, nv12);
    uint32_t index = 0;
    const int32_t query = OH_VideoEncoder_QueryInputBuffer(videoEncoder_, &index, CODEC_QUERY_TIMEOUT_US);
    if (query == AV_ERR_TRY_AGAIN_LATER) {
        videoDroppedFrames_.fetch_add(1);
        return true;
    }
    if (query != AV_ERR_OK) {
        SetFailure(query, "H.264 input buffer query failed");
        return false;
    }
    OH_AVBuffer *buffer = OH_VideoEncoder_GetInputBuffer(videoEncoder_, index);
    uint8_t *address = buffer == nullptr ? nullptr : OH_AVBuffer_GetAddr(buffer);
    const int32_t capacity = buffer == nullptr ? 0 : OH_AVBuffer_GetCapacity(buffer);
    if (address == nullptr || capacity < static_cast<int32_t>(nv12.size())) {
        SetFailure(AV_ERR_NO_MEMORY, "H.264 input buffer is too small");
        return false;
    }
    std::memcpy(address, nv12.data(), nv12.size());
    OH_AVCodecBufferAttr attr { frame.ptsUs, static_cast<int32_t>(nv12.size()), 0, AVCODEC_BUFFER_FLAGS_NONE };
    int32_t code = OH_AVBuffer_SetBufferAttr(buffer, &attr);
    if (code == AV_ERR_OK) code = OH_VideoEncoder_PushInputBuffer(videoEncoder_, index);
    if (code != AV_ERR_OK) {
        SetFailure(code, "H.264 input buffer submission failed");
        return false;
    }
    return true;
}

bool ReplayRecorder::PushVideoEos()
{
    if (videoEncoder_ == nullptr || failed_.load()) return false;
    uint32_t index = 0;
    if (OH_VideoEncoder_QueryInputBuffer(videoEncoder_, &index, 200'000) != AV_ERR_OK) return false;
    OH_AVBuffer *buffer = OH_VideoEncoder_GetInputBuffer(videoEncoder_, index);
    if (buffer == nullptr) return false;
    OH_AVCodecBufferAttr attr { durationUs_.load() + 1, 0, 0, AVCODEC_BUFFER_FLAGS_EOS };
    if (OH_AVBuffer_SetBufferAttr(buffer, &attr) != AV_ERR_OK) return false;
    return OH_VideoEncoder_PushInputBuffer(videoEncoder_, index) == AV_ERR_OK;
}

void ReplayRecorder::VideoOutputLoop()
{
    int idleAfterInput = 0;
    while (!failed_.load()) {
        uint32_t index = 0;
        const int32_t query = OH_VideoEncoder_QueryOutputBuffer(videoEncoder_, &index, CODEC_QUERY_TIMEOUT_US);
        if (query == AV_ERR_STREAM_CHANGED) continue;
        if (query == AV_ERR_TRY_AGAIN_LATER) {
            if (videoInputDone_.load() && ++idleAfterInput > 100) break;
            continue;
        }
        if (query != AV_ERR_OK) {
            SetFailure(query, "H.264 output buffer query failed");
            break;
        }
        idleAfterInput = 0;
        OH_AVBuffer *buffer = OH_VideoEncoder_GetOutputBuffer(videoEncoder_, index);
        OH_AVCodecBufferAttr attr {};
        const bool attrOk = buffer != nullptr && OH_AVBuffer_GetBufferAttr(buffer, &attr) == AV_ERR_OK;
        if (attrOk && attr.size > 0) {
            std::lock_guard<std::mutex> muxerGuard(muxerMutex_);
            const int32_t code = OH_AVMuxer_WriteSampleBuffer(muxer_, videoTrackId_, buffer);
            if (code != AV_ERR_OK) SetFailure(code, "MP4 video sample write failed");
            else videoEncodedFrames_.fetch_add(1);
        }
        OH_VideoEncoder_FreeOutputBuffer(videoEncoder_, index);
        if (attrOk && (attr.flags & AVCODEC_BUFFER_FLAGS_EOS) != 0) break;
    }
    videoOutputDone_.store(true);
}

void ReplayRecorder::AudioInputLoop()
{
    uint64_t submittedFrames = 0;
    while (true) {
        std::vector<uint8_t> next;
        {
            std::unique_lock<std::mutex> lock(audioMutex_);
            audioCondition_.wait_for(lock, std::chrono::milliseconds(50), [this] {
                return !audioQueue_.empty() || stopping_.load();
            });
            if (!audioQueue_.empty()) {
                next = std::move(audioQueue_.front());
                audioQueue_.pop_front();
            } else if (stopping_.load()) {
                break;
            }
        }
        if (!next.empty()) audioPending_.insert(audioPending_.end(), next.begin(), next.end());
        while (audioPending_.size() >= AAC_INPUT_BYTES && !failed_.load()) {
            const int64_t ptsUs = static_cast<int64_t>(submittedFrames) * 1'000'000 / REPLAY_AUDIO_SAMPLE_RATE;
            if (!PushAudioChunk(audioPending_.data(), AAC_INPUT_BYTES, ptsUs, false)) break;
            submittedFrames += AAC_INPUT_FRAMES;
            durationUs_.store(std::max(durationUs_.load(), ptsUs));
            audioPending_.erase(audioPending_.begin(), audioPending_.begin() + AAC_INPUT_BYTES);
        }
    }
    if (!audioPending_.empty() && !failed_.load()) {
        audioPending_.resize(AAC_INPUT_BYTES, 0);
        const int64_t ptsUs = static_cast<int64_t>(submittedFrames) * 1'000'000 / REPLAY_AUDIO_SAMPLE_RATE;
        PushAudioChunk(audioPending_.data(), audioPending_.size(), ptsUs, false);
        submittedFrames += AAC_INPUT_FRAMES;
    }
    const int64_t eosPts = static_cast<int64_t>(submittedFrames) * 1'000'000 / REPLAY_AUDIO_SAMPLE_RATE;
    PushAudioChunk(nullptr, 0, eosPts, true);
    audioInputDone_.store(true);
}

bool ReplayRecorder::PushAudioChunk(const uint8_t *data, size_t size, int64_t ptsUs, bool eos)
{
    uint32_t index = 0;
    const int32_t query = OH_AudioCodec_QueryInputBuffer(audioEncoder_, &index, 200'000);
    if (query != AV_ERR_OK) {
        SetFailure(query, "AAC-LC input buffer query failed");
        return false;
    }
    OH_AVBuffer *buffer = OH_AudioCodec_GetInputBuffer(audioEncoder_, index);
    uint8_t *address = buffer == nullptr ? nullptr : OH_AVBuffer_GetAddr(buffer);
    const int32_t capacity = buffer == nullptr ? 0 : OH_AVBuffer_GetCapacity(buffer);
    if (buffer == nullptr || (!eos && (address == nullptr || capacity < static_cast<int32_t>(size)))) {
        SetFailure(AV_ERR_NO_MEMORY, "AAC-LC input buffer is too small");
        return false;
    }
    if (!eos && size > 0) std::memcpy(address, data, size);
    OH_AVCodecBufferAttr attr { ptsUs, static_cast<int32_t>(size), 0,
        eos ? AVCODEC_BUFFER_FLAGS_EOS : AVCODEC_BUFFER_FLAGS_NONE };
    int32_t code = OH_AVBuffer_SetBufferAttr(buffer, &attr);
    if (code == AV_ERR_OK) code = OH_AudioCodec_PushInputBuffer(audioEncoder_, index);
    if (code != AV_ERR_OK) SetFailure(code, "AAC-LC input buffer submission failed");
    return code == AV_ERR_OK;
}

void ReplayRecorder::AudioOutputLoop()
{
    int idleAfterInput = 0;
    while (!failed_.load()) {
        uint32_t index = 0;
        const int32_t query = OH_AudioCodec_QueryOutputBuffer(audioEncoder_, &index, CODEC_QUERY_TIMEOUT_US);
        if (query == AV_ERR_STREAM_CHANGED) continue;
        if (query == AV_ERR_TRY_AGAIN_LATER) {
            if (audioInputDone_.load() && ++idleAfterInput > 100) break;
            continue;
        }
        if (query != AV_ERR_OK) {
            SetFailure(query, "AAC-LC output buffer query failed");
            break;
        }
        idleAfterInput = 0;
        OH_AVBuffer *buffer = OH_AudioCodec_GetOutputBuffer(audioEncoder_, index);
        OH_AVCodecBufferAttr attr {};
        const bool attrOk = buffer != nullptr && OH_AVBuffer_GetBufferAttr(buffer, &attr) == AV_ERR_OK;
        if (attrOk && attr.size > 0) {
            std::lock_guard<std::mutex> muxerGuard(muxerMutex_);
            const int32_t code = OH_AVMuxer_WriteSampleBuffer(muxer_, audioTrackId_, buffer);
            if (code != AV_ERR_OK) SetFailure(code, "MP4 audio sample write failed");
            else audioEncodedBuffers_.fetch_add(1);
        }
        OH_AudioCodec_FreeOutputBuffer(audioEncoder_, index);
        if (attrOk && (attr.flags & AVCODEC_BUFFER_FLAGS_EOS) != 0) break;
    }
    audioOutputDone_.store(true);
}

bool ReplayRecorder::Stop(bool keepOutput)
{
    const bool hadResources = prepared_.load() || accepting_.load() || videoEncoder_ != nullptr || muxer_ != nullptr;
    accepting_.store(false);
    stopping_.store(true);
    videoCondition_.notify_all();
    audioCondition_.notify_all();
    if (videoInputThread_.joinable()) videoInputThread_.join();
    if (audioInputThread_.joinable()) audioInputThread_.join();
    if (videoOutputThread_.joinable()) videoOutputThread_.join();
    if (audioOutputThread_.joinable()) audioOutputThread_.join();
    if (videoEncoder_ != nullptr) OH_VideoEncoder_Stop(videoEncoder_);
    if (audioEncoder_ != nullptr) OH_AudioCodec_Stop(audioEncoder_);
    bool muxerStopped = true;
    if (muxer_ != nullptr) muxerStopped = OH_AVMuxer_Stop(muxer_) == AV_ERR_OK;
    ReleaseCodecsAndMuxer();
    prepared_.store(false);
    const bool hasMedia = videoEncodedFrames_.load() > 0;
    const bool success = hadResources && !failed_.load() && muxerStopped && hasMedia;
    finalized_.store(success && keepOutput);
    if (!success || !keepOutput) unlink(filePath_.c_str());
    {
        std::lock_guard<std::mutex> guard(stateMutex_);
        if (success && keepOutput) message_ = "replay finalized in private storage";
        else if (!failed_.load() && hadResources) message_ = "replay canceled and private output removed";
    }
    return success && keepOutput;
}

void ReplayRecorder::ReleaseCodecsAndMuxer()
{
    if (videoEncoder_ != nullptr) {
        OH_VideoEncoder_Destroy(videoEncoder_);
        videoEncoder_ = nullptr;
    }
    if (audioEncoder_ != nullptr) {
        OH_AudioCodec_Destroy(audioEncoder_);
        audioEncoder_ = nullptr;
    }
    if (muxer_ != nullptr) {
        OH_AVMuxer_Destroy(muxer_);
        muxer_ = nullptr;
    }
    if (outputFd_ >= 0) {
        close(outputFd_);
        outputFd_ = -1;
    }
}

void ReplayRecorder::SetFailure(int32_t code, const std::string &message)
{
    failed_.store(true);
    errorCode_.store(code);
    accepting_.store(false);
    {
        std::lock_guard<std::mutex> guard(stateMutex_);
        message_ = message;
    }
    OH_LOG_Print(LOG_APP, LOG_ERROR, PC_REPLAY_LOG_DOMAIN, PC_REPLAY_LOG_TAG,
        "REPLAY_FAILURE code=%{public}d message=%{public}s", code, message.c_str());
}

ReplayRecorderStats ReplayRecorder::Stats() const
{
    ReplayRecorderStats stats;
    stats.prepared = prepared_.load();
    stats.running = accepting_.load();
    stats.finalized = finalized_.load();
    stats.failed = failed_.load();
    stats.audioEnabled = audioEnabled_.load();
    stats.videoInputFrames = videoInputFrames_.load();
    stats.videoEncodedFrames = videoEncodedFrames_.load();
    stats.videoDroppedFrames = videoDroppedFrames_.load();
    stats.audioInputBuffers = audioInputBuffers_.load();
    stats.audioEncodedBuffers = audioEncodedBuffers_.load();
    stats.nonSilentSamples = nonSilentSamples_.load();
    stats.audioPeak = audioPeak_.load();
    stats.durationUs = durationUs_.load();
    stats.fileBytes = FileSize(filePath_);
    stats.videoWidth = videoWidth_;
    stats.videoHeight = videoHeight_;
    stats.videoFps = videoFps_;
    stats.videoBitrate = videoBitrate_;
    stats.errorCode = errorCode_.load();
    stats.filePath = filePath_;
    {
        std::lock_guard<std::mutex> guard(stateMutex_);
        stats.message = message_;
    }
    return stats;
}

bool ReplayRecorder::IsAccepting() const
{
    return accepting_.load();
}

void ReplayRecorder::ConvertRgbaToLetterboxedNv12(const std::vector<uint8_t> &rgba,
    int32_t sourceWidth, int32_t sourceHeight, std::vector<uint8_t> &nv12)
{
    const int32_t targetWidth = videoWidth_;
    const int32_t targetHeight = videoHeight_;
    const double sourceAspect = static_cast<double>(sourceWidth) / sourceHeight;
    const double targetAspect = static_cast<double>(targetWidth) / targetHeight;
    int32_t viewWidth = targetWidth;
    int32_t viewHeight = targetHeight;
    if (sourceAspect >= targetAspect) viewHeight = std::max(2, static_cast<int32_t>(std::round(targetWidth / sourceAspect)));
    else viewWidth = std::max(2, static_cast<int32_t>(std::round(targetHeight * sourceAspect)));
    viewWidth &= ~1;
    viewHeight &= ~1;
    const int32_t offsetX = ((targetWidth - viewWidth) / 2) & ~1;
    const int32_t offsetY = ((targetHeight - viewHeight) / 2) & ~1;
    nv12.assign(static_cast<size_t>(targetWidth) * targetHeight * 3 / 2, 128);
    std::fill(nv12.begin(), nv12.begin() + static_cast<size_t>(targetWidth) * targetHeight, 16);

    auto sourcePixel = [&](int32_t x, int32_t y) -> const uint8_t * {
        const int32_t sx = std::clamp(x * sourceWidth / viewWidth, 0, sourceWidth - 1);
        const int32_t sy = std::clamp(y * sourceHeight / viewHeight, 0, sourceHeight - 1);
        return rgba.data() + (static_cast<size_t>(sy) * sourceWidth + sx) * 4;
    };
    for (int32_t y = 0; y < viewHeight; ++y) {
        uint8_t *target = nv12.data() + static_cast<size_t>(offsetY + y) * targetWidth + offsetX;
        for (int32_t x = 0; x < viewWidth; ++x) {
            const uint8_t *pixel = sourcePixel(x, y);
            target[x] = ClampByte(((66 * pixel[0] + 129 * pixel[1] + 25 * pixel[2] + 128) >> 8) + 16);
        }
    }
    const size_t uvStart = static_cast<size_t>(targetWidth) * targetHeight;
    for (int32_t y = 0; y < viewHeight; y += 2) {
        uint8_t *target = nv12.data() + uvStart + static_cast<size_t>((offsetY + y) / 2) * targetWidth + offsetX;
        for (int32_t x = 0; x < viewWidth; x += 2) {
            const uint8_t *pixel = sourcePixel(x, y);
            target[x] = ClampByte(((-38 * pixel[0] - 74 * pixel[1] + 112 * pixel[2] + 128) >> 8) + 128);
            target[x + 1] = ClampByte(((112 * pixel[0] - 94 * pixel[1] - 18 * pixel[2] + 128) >> 8) + 128);
        }
    }
}

} // namespace pc
