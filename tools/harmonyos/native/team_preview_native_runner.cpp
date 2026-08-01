#include "team_preview_engine_core.h"

#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

std::vector<uint8_t> ReadBytes(const char *path)
{
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error(std::string("cannot open ") + path);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

int RunPolicyTests()
{
    int checks = 0;
    int failures = 0;
    const auto check = [&](bool condition) {
        ++checks;
        if (!condition) ++failures;
    };
    pc::TeamPreviewPixelBounds mapped{};
    check(pc::IsValidTeamPreviewFrame(2772, 1240, static_cast<size_t>(2772) * 1240 * 4));
    check(!pc::IsValidTeamPreviewFrame(-1, 1240, 0));
    check(!pc::IsValidTeamPreviewFrame(2772, 1240, 16));
    check(pc::TryMapTeamPreviewRegion({862, 528, 1065, 705}, 2772, 1240, mapped));
    check(mapped.left == 844 && mapped.top == 183 && mapped.width == 132 && mapped.height == 115);
    check(!pc::TryMapTeamPreviewRegion({862, 528, 862, 705}, 2772, 1240, mapped));
    check(!pc::TryMapTeamPreviewRegion({-1, 528, 1065, 705}, 2772, 1240, mapped));
    check(!pc::TryMapTeamPreviewRegion({862, 528, 1065, 705}, -1, 1240, mapped));
    check(pc::IsCurrentTeamPreviewSnapshot(
        true, true, true, false, true, 7, 7, 2772, 1240, 2772, 1240));
    check(!pc::IsCurrentTeamPreviewSnapshot(
        true, true, true, false, true, 7, 8, 2772, 1240, 2772, 1240));
    check(!pc::IsCurrentTeamPreviewSnapshot(
        true, true, true, false, true, 8, 8, 2772, 1240, 1240, 2772));
    check(!pc::IsCurrentTeamPreviewSnapshot(
        true, true, true, true, true, 8, 8, 2772, 1240, 2772, 1240));
    check(!pc::TeamPreviewNeedsConfirmation(0.90, 0.035));
    check(pc::TeamPreviewNeedsConfirmation(std::nextafter(0.90, 0.0), 0.035));
    check(pc::TeamPreviewNeedsConfirmation(0.90, std::nextafter(0.035, 0.0)));
    check(pc::TeamPreviewNeedsConfirmation(std::nan(""), 0.035));
    std::cout << "{\"kind\":\"TeamPreviewNativePolicyResult\",\"checks\":" << checks
        << ",\"failures\":" << failures
        << ",\"covers\":[\"frame-validation\",\"empty-roi\",\"negative-dimensions\","
        << "\"stale-generation\",\"rotation-dimensions\",\"invalidated-frame\","
        << "\"threshold-0.90-0.035\"]}" << '\n';
    return failures;
}

} // namespace

int main(int argc, char **argv)
{
    try {
        if (argc == 2 && std::string(argv[1]) == "--self-test") return RunPolicyTests();
        if (argc < 6 || std::string(argv[1]) != "--recognize") {
            std::cerr << "usage: team_preview_native_runner --self-test | "
                "--recognize TEMPLATE WIDTH HEIGHT RGBA...\n";
            return 64;
        }
        const std::vector<uint8_t> templates = ReadBytes(argv[2]);
        const int32_t width = std::stoi(argv[3]);
        const int32_t height = std::stoi(argv[4]);
        for (int index = 5; index < argc; ++index) {
            const std::vector<uint8_t> rgba = ReadBytes(argv[index]);
            std::cout << pc::RecognizeTeamPreviewRgba(
                rgba, width, height, templates, "2026-08-01T00:00:00Z") << '\n';
        }
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 2;
    }
}
