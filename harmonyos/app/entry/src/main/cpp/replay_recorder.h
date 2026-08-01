#ifndef PC_REPLAY_RECORDER_H
#define PC_REPLAY_RECORDER_H

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct OH_AVCodec;
struct OH_AVMuxer;

namespace pc {

constexpr int32_t REPLAY_VIDEO_WIDTH = 960;
constexpr int32_t REPLAY_VIDEO_HEIGHT = 540;
constexpr int32_t REPLAY_VIDEO_FPS = 24;
constexpr int64_t REPLAY_VIDEO_BITRATE = 4'000'000;
constexpr int32_t REPLAY_AUDIO_SAMPLE_RATE = 48'000;
constexpr int32_t REPLAY_AUDIO_CHANNELS = 2;
constexpr int64_t REPLAY_AUDIO_BITRATE = 128'000;

struct ReplayRecorderStats {
    bool prepared = false;
    bool running = false;
    bool finalized = false;
    bool failed = false;
    bool audioEnabled = true;
    uint64_t videoInputFrames = 0;
    uint64_t videoEncodedFrames = 0;
    uint64_t videoDroppedFrames = 0;
    uint64_t audioInputBuffers = 0;
    uint64_t audioEncodedBuffers = 0;
    uint64_t nonSilentSamples = 0;
    int32_t audioPeak = 0;
    int64_t durationUs = 0;
    int64_t fileBytes = 0;
    int32_t errorCode = 0;
    std::string filePath;
    std::string message;
};

class ReplayRecorder {
public:
    ReplayRecorder() = default;
    ~ReplayRecorder();

    bool Prepare(const std::string &path, int32_t sourceWidth, int32_t sourceHeight, bool audioEnabled);
    bool Start();
    void EnqueueVideo(const std::shared_ptr<std::vector<uint8_t>> &rgba, int32_t width, int32_t height);
    void EnqueueAudio(const uint8_t *pcm, size_t size);
    void SignalInputEnded();
    bool Stop(bool keepOutput);
    ReplayRecorderStats Stats() const;
    bool IsAccepting() const;

private:
    struct VideoFrame {
        std::shared_ptr<std::vector<uint8_t>> rgba;
        int32_t width = 0;
        int32_t height = 0;
        int64_t ptsUs = 0;
    };

    bool PrepareMuxer();
    bool PrepareVideoEncoder();
    bool PrepareAudioEncoder();
    void VideoInputLoop();
    void VideoOutputLoop();
    void AudioInputLoop();
    void AudioOutputLoop();
    bool PushVideoFrame(const VideoFrame &frame);
    bool PushVideoEos();
    bool PushAudioChunk(const uint8_t *data, size_t size, int64_t ptsUs, bool eos);
    void SetFailure(int32_t code, const std::string &message);
    void ReleaseCodecsAndMuxer();
    static void ConvertRgbaToLetterboxedNv12(const std::vector<uint8_t> &rgba, int32_t sourceWidth,
        int32_t sourceHeight, std::vector<uint8_t> &nv12);

    mutable std::mutex stateMutex_;
    std::mutex videoMutex_;
    std::mutex audioMutex_;
    std::mutex muxerMutex_;
    std::condition_variable videoCondition_;
    std::condition_variable audioCondition_;
    std::deque<VideoFrame> videoQueue_;
    std::deque<std::vector<uint8_t>> audioQueue_;
    std::vector<uint8_t> audioPending_;

    OH_AVCodec *videoEncoder_ = nullptr;
    OH_AVCodec *audioEncoder_ = nullptr;
    OH_AVMuxer *muxer_ = nullptr;
    int32_t outputFd_ = -1;
    int32_t videoTrackId_ = -1;
    int32_t audioTrackId_ = -1;
    int32_t sourceWidth_ = 0;
    int32_t sourceHeight_ = 0;
    std::string filePath_;
    std::string message_ = "not prepared";

    std::atomic<bool> prepared_{false};
    std::atomic<bool> accepting_{false};
    std::atomic<bool> stopping_{false};
    std::atomic<bool> finalized_{false};
    std::atomic<bool> failed_{false};
    std::atomic<bool> videoInputDone_{false};
    std::atomic<bool> audioInputDone_{false};
    std::atomic<bool> videoOutputDone_{false};
    std::atomic<bool> audioOutputDone_{false};
    std::atomic<bool> audioEnabled_{true};
    std::atomic<uint64_t> videoInputFrames_{0};
    std::atomic<uint64_t> videoEncodedFrames_{0};
    std::atomic<uint64_t> videoDroppedFrames_{0};
    std::atomic<uint64_t> audioInputBuffers_{0};
    std::atomic<uint64_t> audioEncodedBuffers_{0};
    std::atomic<uint64_t> nonSilentSamples_{0};
    std::atomic<int32_t> audioPeak_{0};
    std::atomic<int64_t> durationUs_{0};
    std::atomic<int32_t> errorCode_{0};
    std::atomic<uint64_t> acceptedVideoSequence_{0};

    std::thread videoInputThread_;
    std::thread videoOutputThread_;
    std::thread audioInputThread_;
    std::thread audioOutputThread_;
    std::chrono::steady_clock::time_point startedAt_;
    std::chrono::steady_clock::time_point lastVideoAcceptedAt_;
};

} // namespace pc

#endif // PC_REPLAY_RECORDER_H
