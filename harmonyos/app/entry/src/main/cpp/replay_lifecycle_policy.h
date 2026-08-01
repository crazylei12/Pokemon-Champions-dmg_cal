#ifndef PC_REPLAY_LIFECYCLE_POLICY_H
#define PC_REPLAY_LIFECYCLE_POLICY_H

namespace pc {

struct ReplayCleanupInput {
    bool hadResources;
    bool failed;
    bool keepOutputRequested;
    bool preserveFailedOutput;
    bool muxerStopped;
    bool hasMedia;
    bool privateFileExists;
    bool alreadyFinalized;
};

struct ReplayCleanupDecision {
    bool publishAllowed;
    bool retainPrivateOutput;
    bool removePrivateOutput;
};

constexpr ReplayCleanupDecision DecideReplayCleanup(const ReplayCleanupInput &input)
{
    const bool finalizedNow = input.hadResources && !input.failed && input.keepOutputRequested &&
        input.muxerStopped && input.hasMedia;
    const bool retainFinalized = input.keepOutputRequested && input.alreadyFinalized && input.privateFileExists;
    const bool retainFailure = input.failed && input.preserveFailedOutput && input.privateFileExists;
    const bool retain = finalizedNow || retainFinalized || retainFailure;
    return { finalizedNow || retainFinalized, retain, input.privateFileExists && !retain };
}

} // namespace pc

#endif // PC_REPLAY_LIFECYCLE_POLICY_H
