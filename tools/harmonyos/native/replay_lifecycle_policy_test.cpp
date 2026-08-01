#include "replay_lifecycle_policy.h"

namespace {

int Check(bool condition)
{
    return condition ? 0 : 1;
}

} // namespace

extern "C" __attribute__((visibility("default"))) int RunReplayLifecyclePolicyTests()
{
    int failures = 0;
    volatile bool runtimeTrue = true;
    const bool yes = runtimeTrue;
    const bool no = !runtimeTrue;
    const pc::ReplayCleanupDecision success = pc::DecideReplayCleanup(
        { yes, no, yes, no, yes, yes, yes, no });
    failures += Check(success.publishAllowed && success.retainPrivateOutput && !success.removePrivateOutput);

    const pc::ReplayCleanupDecision canceled = pc::DecideReplayCleanup(
        { yes, no, no, no, yes, yes, yes, no });
    failures += Check(!canceled.publishAllowed && !canceled.retainPrivateOutput && canceled.removePrivateOutput);

    const pc::ReplayCleanupDecision encoderFailure = pc::DecideReplayCleanup(
        { yes, yes, no, yes, yes, no, yes, no });
    failures += Check(!encoderFailure.publishAllowed && encoderFailure.retainPrivateOutput &&
        !encoderFailure.removePrivateOutput);

    const pc::ReplayCleanupDecision muxerFailure = pc::DecideReplayCleanup(
        { yes, no, yes, no, no, yes, yes, no });
    failures += Check(!muxerFailure.publishAllowed && !muxerFailure.retainPrivateOutput &&
        muxerFailure.removePrivateOutput);

    const pc::ReplayCleanupDecision repeatedFailureCleanup = pc::DecideReplayCleanup(
        { no, yes, no, yes, no, no, yes, no });
    failures += Check(!repeatedFailureCleanup.publishAllowed && repeatedFailureCleanup.retainPrivateOutput &&
        !repeatedFailureCleanup.removePrivateOutput);

    const pc::ReplayCleanupDecision repeatedFinalize = pc::DecideReplayCleanup(
        { no, no, yes, no, no, no, yes, yes });
    failures += Check(repeatedFinalize.publishAllowed && repeatedFinalize.retainPrivateOutput &&
        !repeatedFinalize.removePrivateOutput);
    return failures;
}

extern "C" int mainCRTStartup()
{
    return RunReplayLifecyclePolicyTests();
}
