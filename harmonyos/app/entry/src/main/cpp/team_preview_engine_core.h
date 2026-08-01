#ifndef PC_TEAM_PREVIEW_ENGINE_CORE_H
#define PC_TEAM_PREVIEW_ENGINE_CORE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace pc {

struct TeamPreviewRegionBounds {
    double left;
    double top;
    double right;
    double bottom;
};

struct TeamPreviewPixelBounds {
    int32_t left;
    int32_t top;
    int32_t width;
    int32_t height;
};

bool IsValidTeamPreviewFrame(int32_t width, int32_t height, size_t byteCount);

bool TryMapTeamPreviewRegion(const TeamPreviewRegionBounds &region, int32_t width, int32_t height,
    TeamPreviewPixelBounds &mapped);

bool IsCurrentTeamPreviewSnapshot(bool prepared, bool running, bool contentVisible, bool invalidated,
    bool hasFrame, uint64_t frameGeneration, uint64_t currentGeneration, int32_t frameWidth,
    int32_t frameHeight, int32_t requestedWidth, int32_t requestedHeight);

bool TeamPreviewNeedsConfirmation(double score, double margin);

std::string RecognizeTeamPreviewRgba(const std::vector<uint8_t> &rgba, int32_t width, int32_t height,
    const std::vector<uint8_t> &templateBytes, const std::string &capturedAt);

} // namespace pc

#endif // PC_TEAM_PREVIEW_ENGINE_CORE_H
