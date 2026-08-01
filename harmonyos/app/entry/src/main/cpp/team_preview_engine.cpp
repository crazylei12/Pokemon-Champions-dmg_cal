#include "team_preview_engine.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

namespace {

using Clock = std::chrono::steady_clock;
constexpr int BASE_WIDTH = 3392;
constexpr int BASE_HEIGHT = 2400;
constexpr double TARGET_ASPECT = 16.0 / 9.0;
constexpr int COARSE_SPECIES_TOP_K = 24;
constexpr double ADAPTIVE_GRABCUT_MARGIN = 0.02;

struct Region {
    const char *id;
    const char *side;
    int slot;
    int left;
    int top;
    int right;
    int bottom;
};

constexpr Region REGIONS[] = {
    {"team_preview.own.slot0.pokemon_icon", "own", 0, 862, 528, 1065, 705},
    {"team_preview.own.slot1.pokemon_icon", "own", 1, 862, 750, 1065, 929},
    {"team_preview.own.slot2.pokemon_icon", "own", 2, 862, 974, 1065, 1152},
    {"team_preview.own.slot3.pokemon_icon", "own", 3, 862, 1197, 1065, 1378},
    {"team_preview.own.slot4.pokemon_icon", "own", 4, 862, 1421, 1065, 1602},
    {"team_preview.own.slot5.pokemon_icon", "own", 5, 862, 1647, 1065, 1825},
    {"team_preview.opponent.slot0.pokemon_icon", "opponent", 0, 2774, 512, 3050, 716},
    {"team_preview.opponent.slot1.pokemon_icon", "opponent", 1, 2774, 738, 3050, 941},
    {"team_preview.opponent.slot2.pokemon_icon", "opponent", 2, 2774, 962, 3050, 1166},
    {"team_preview.opponent.slot3.pokemon_icon", "opponent", 3, 2774, 1186, 3050, 1388},
    {"team_preview.opponent.slot4.pokemon_icon", "opponent", 4, 2774, 1411, 3050, 1611},
    {"team_preview.opponent.slot5.pokemon_icon", "opponent", 5, 2774, 1637, 3050, 1835},
};

double Millis(Clock::time_point started)
{
    return std::chrono::duration<double, std::milli>(Clock::now() - started).count();
}

double Rounded(double value, double scale = 1000000.0)
{
    return std::round(value * scale) / scale;
}

std::string JsonEscape(const std::string &value)
{
    std::ostringstream out;
    for (unsigned char ch : value) {
        switch (ch) {
            case '\"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (ch < 0x20) {
                    out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(ch)
                        << std::dec << std::setfill(' ');
                } else {
                    out << static_cast<char>(ch);
                }
        }
    }
    return out.str();
}

std::string Quote(const std::string &value)
{
    return "\"" + JsonEscape(value) + "\"";
}

class ByteReader {
public:
    ByteReader(const uint8_t *bytes, size_t length) : bytes_(bytes), length_(length) {}

    uint8_t U8()
    {
        Require(1);
        return bytes_[offset_++];
    }

    uint16_t U16()
    {
        Require(2);
        uint16_t value = static_cast<uint16_t>(bytes_[offset_]) << 8 | bytes_[offset_ + 1];
        offset_ += 2;
        return value;
    }

    uint32_t U32()
    {
        Require(4);
        uint32_t value = static_cast<uint32_t>(bytes_[offset_]) << 24 |
            static_cast<uint32_t>(bytes_[offset_ + 1]) << 16 |
            static_cast<uint32_t>(bytes_[offset_ + 2]) << 8 | bytes_[offset_ + 3];
        offset_ += 4;
        return value;
    }

    uint64_t U64()
    {
        uint64_t high = U32();
        uint64_t low = U32();
        return high << 32 | low;
    }

    float F32()
    {
        uint32_t raw = U32();
        float value;
        std::memcpy(&value, &raw, sizeof(value));
        return value;
    }

    std::string Utf()
    {
        const size_t length = U16();
        Require(length);
        std::string value(reinterpret_cast<const char *>(bytes_ + offset_), length);
        offset_ += length;
        return value;
    }

    std::vector<uint8_t> Bytes(size_t length)
    {
        Require(length);
        std::vector<uint8_t> value(bytes_ + offset_, bytes_ + offset_ + length);
        offset_ += length;
        return value;
    }

private:
    void Require(size_t count)
    {
        if (offset_ > length_ || count > length_ - offset_) throw std::runtime_error("truncated PTVFEAT2 asset");
    }

    const uint8_t *bytes_;
    size_t length_;
    size_t offset_ = 0;
};

struct Weights {
    double phash;
    double edge;
    double color;
    double templ;
};

struct TemplateFeature {
    std::string canonicalId;
    std::string showdownId;
    std::string displayName;
    std::string sideKey;
    std::string source;
    std::string visualVariant;
    bool shiny = false;
    double bonusScale = 1.0;
    cv::Mat gray;
    std::vector<uint8_t> coarseGray;
    std::vector<uint8_t> edgeBits;
    cv::Mat hist;
    std::vector<float> histValues;
    uint64_t phash = 0;
};

struct Candidate {
    const TemplateFeature *feature = nullptr;
    double score = 0.0;
    double margin = 0.0;
};

struct RankResult {
    std::vector<Candidate> candidates;
    int eligible = 0;
    int refined = 0;
};

struct Feature {
    cv::Mat gray;
    std::vector<uint8_t> coarseGray;
    std::vector<uint8_t> edgeBits;
    cv::Mat hist;
    std::vector<float> histValues;
    uint64_t phash = 0;
    double bonusScale = 1.0;
    double strictMs = 0.0;
    double relaxedMs = 0.0;
    double grabCutMs = 0.0;
    double selectionMs = 0.0;
    double colorQuality = -10.0;
};

double Pearson(const std::vector<uint8_t> &left, const std::vector<uint8_t> &right)
{
    if (left.size() != right.size()) throw std::runtime_error("coarse feature size mismatch");
    double sl = 0, sr = 0, sll = 0, srr = 0, slr = 0;
    for (size_t index = 0; index < left.size(); ++index) {
        double a = left[index], b = right[index];
        sl += a; sr += b; sll += a * a; srr += b * b; slr += a * b;
    }
    const double n = static_cast<double>(left.size());
    const double denominator = std::sqrt(std::max(0.0, n * sll - sl * sl) * std::max(0.0, n * srr - sr * sr));
    return denominator <= 1e-12 ? 0.0 : std::clamp((n * slr - sl * sr) / denominator, -1.0, 1.0);
}

double Pearson(const std::vector<float> &left, const std::vector<float> &right)
{
    if (left.size() != right.size()) throw std::runtime_error("histogram size mismatch");
    double sl = 0, sr = 0, sll = 0, srr = 0, slr = 0;
    for (size_t index = 0; index < left.size(); ++index) {
        double a = left[index], b = right[index];
        sl += a; sr += b; sll += a * a; srr += b * b; slr += a * b;
    }
    const double n = static_cast<double>(left.size());
    const double denominator = std::sqrt(std::max(0.0, n * sll - sl * sl) * std::max(0.0, n * srr - sr * sr));
    return denominator <= 1e-12 ? 0.0 : std::clamp((n * slr - sl * sr) / denominator, -1.0, 1.0);
}

double NormalizeCorrelation(double value)
{
    if (!std::isfinite(value)) return 0.0;
    return std::clamp((std::clamp(value, -1.0, 1.0) + 1.0) / 2.0, 0.0, 1.0);
}

double BitIou(const std::vector<uint8_t> &left, const std::vector<uint8_t> &right)
{
    int intersection = 0, joined = 0;
    for (size_t index = 0; index < left.size(); ++index) {
        intersection += __builtin_popcount(static_cast<unsigned>(left[index] & right[index]));
        joined += __builtin_popcount(static_cast<unsigned>(left[index] | right[index]));
    }
    return joined == 0 ? 0.0 : static_cast<double>(intersection) / joined;
}

class TemplateAsset {
public:
    TemplateAsset(const uint8_t *bytes, size_t length)
    {
        ByteReader input(bytes, length);
        std::string magic(reinterpret_cast<const char *>(bytes), std::min<size_t>(8, length));
        if (magic != "PTVFEAT2") throw std::runtime_error("unknown team-preview template format");
        for (int index = 0; index < 8; ++index) input.U8();
        if (input.U32() != 2) throw std::runtime_error("unsupported team-preview template version");
        featureSize = static_cast<int>(input.U32());
        coarseSize = static_cast<int>(input.U32());
        const int histSize = static_cast<int>(input.U32());
        const int count = static_cast<int>(input.U32());
        auto readWeights = [&input]() -> Weights {
            return {input.F32(), input.F32(), input.F32(), input.F32()};
        };
        defaultWeights = readWeights();
        opponentWeights = readWeights();
        labeledBonus = input.F32();
        const int graySize = featureSize * featureSize;
        const int edgeSize = (graySize + 7) / 8;
        templates.reserve(count);
        for (int index = 0; index < count; ++index) {
            TemplateFeature value;
            value.canonicalId = input.Utf();
            value.showdownId = input.Utf();
            value.displayName = input.Utf();
            input.Utf();
            value.sideKey = input.Utf();
            input.Utf();
            value.source = input.Utf();
            value.visualVariant = input.Utf();
            input.Utf();
            value.shiny = input.U8() == 1;
            value.bonusScale = input.F32();
            const std::vector<uint8_t> gray = input.Bytes(graySize);
            value.gray = cv::Mat(featureSize, featureSize, CV_8UC1, const_cast<uint8_t *>(gray.data())).clone();
            value.coarseGray = input.Bytes(coarseSize * coarseSize);
            value.edgeBits = input.Bytes(edgeSize);
            value.histValues.resize(histSize);
            for (float &entry : value.histValues) entry = input.F32();
            value.hist = cv::Mat(histSize, 1, CV_32F, value.histValues.data()).clone();
            value.phash = input.U64();
            templates.push_back(std::move(value));
        }
    }

    RankResult Rank(const Feature &query, const std::string &sideKey, int topK) const
    {
        const std::string side = sideKey.substr(0, sideKey.find('.'));
        const Weights &weights = side == "opponent" ? opponentWeights : defaultWeights;
        std::unordered_map<std::string, double> coarseBest;
        std::vector<const TemplateFeature *> eligible;
        for (const TemplateFeature &item : templates) {
            const std::string itemSide = item.sideKey.substr(0, item.sideKey.find('.'));
            if (!item.sideKey.empty() && itemSide != side) continue;
            eligible.push_back(&item);
            const double gray = NormalizeCorrelation(Pearson(query.coarseGray, item.coarseGray));
            const double color = NormalizeCorrelation(Pearson(query.histValues, item.histValues));
            const double phash = 1.0 - static_cast<double>(__builtin_popcountll(query.phash ^ item.phash)) / 64.0;
            const double bonus = item.sideKey == sideKey ? labeledBonus * std::min(query.bonusScale, item.bonusScale) : 0.0;
            const double score = phash * weights.phash + color * weights.color + gray * weights.templ + bonus;
            auto found = coarseBest.find(item.canonicalId);
            if (found == coarseBest.end() || score > found->second) coarseBest[item.canonicalId] = score;
        }
        std::vector<std::pair<std::string, double>> coarse(coarseBest.begin(), coarseBest.end());
        std::sort(coarse.begin(), coarse.end(), [](const auto &left, const auto &right) { return left.second > right.second; });
        std::unordered_set<std::string> shortlist;
        for (size_t index = 0; index < std::min<size_t>(COARSE_SPECIES_TOP_K, coarse.size()); ++index) {
            shortlist.insert(coarse[index].first);
        }
        std::unordered_map<std::string, Candidate> best;
        int refined = 0;
        for (const TemplateFeature *item : eligible) {
            if (shortlist.find(item->canonicalId) == shortlist.end()) continue;
            ++refined;
            cv::Mat matched;
            cv::matchTemplate(query.gray, item->gray, matched, cv::TM_CCOEFF_NORMED);
            const double templ = NormalizeCorrelation(matched.at<float>(0, 0));
            const double edge = BitIou(query.edgeBits, item->edgeBits);
            const double color = NormalizeCorrelation(cv::compareHist(query.hist, item->hist, cv::HISTCMP_CORREL));
            const double phash = 1.0 - static_cast<double>(__builtin_popcountll(query.phash ^ item->phash)) / 64.0;
            const double bonus = item->sideKey == sideKey ? labeledBonus * std::min(query.bonusScale, item->bonusScale) : 0.0;
            Candidate scored{item, phash * weights.phash + edge * weights.edge + color * weights.color + templ * weights.templ + bonus, 0.0};
            auto found = best.find(item->canonicalId);
            if (found == best.end() || scored.score > found->second.score) best[item->canonicalId] = scored;
        }
        std::vector<Candidate> ranked;
        for (const auto &entry : best) ranked.push_back(entry.second);
        std::sort(ranked.begin(), ranked.end(), [](const Candidate &left, const Candidate &right) { return left.score > right.score; });
        if (ranked.size() > static_cast<size_t>(topK)) ranked.resize(topK);
        for (size_t index = 0; index < ranked.size(); ++index) {
            ranked[index].margin = index == 0 ? std::max(0.0, ranked[index].score - (ranked.size() > 1 ? ranked[1].score : 0.0)) : 0.0;
        }
        return {std::move(ranked), static_cast<int>(eligible.size()), refined};
    }

    int featureSize = 0;
    int coarseSize = 0;

private:
    Weights defaultWeights{};
    Weights opponentWeights{};
    double labeledBonus = 0.0;
    std::vector<TemplateFeature> templates;
};

struct Viewport { double left; double top; double width; double height; };

Viewport CenteredViewport(int width, int height)
{
    const double aspect = static_cast<double>(width) / height;
    if (std::abs(aspect - TARGET_ASPECT) < 0.001) return {0, 0, static_cast<double>(width), static_cast<double>(height)};
    if (aspect > TARGET_ASPECT) {
        const double viewportWidth = height * TARGET_ASPECT;
        return {std::max(0.0, (width - viewportWidth) / 2.0), 0, viewportWidth, static_cast<double>(height)};
    }
    const double viewportHeight = width / TARGET_ASPECT;
    return {0, std::max(0.0, (height - viewportHeight) / 2.0), static_cast<double>(width), viewportHeight};
}

cv::Rect MapRegion(const Region &region, int width, int height)
{
    const Viewport base = CenteredViewport(BASE_WIDTH, BASE_HEIGHT);
    const Viewport target = CenteredViewport(width, height);
    const int boundLeft = static_cast<int>(std::lround(target.left));
    const int boundTop = static_cast<int>(std::lround(target.top));
    const int boundWidth = static_cast<int>(std::lround(target.width));
    const int boundHeight = static_cast<int>(std::lround(target.height));
    int x1 = static_cast<int>(std::lround(target.left + (region.left - base.left) * target.width / base.width));
    int y1 = static_cast<int>(std::lround(target.top + (region.top - base.top) * target.height / base.height));
    int x2 = static_cast<int>(std::lround(target.left + (region.right - base.left) * target.width / base.width));
    int y2 = static_cast<int>(std::lround(target.top + (region.bottom - base.top) * target.height / base.height));
    x1 = std::clamp(x1, boundLeft, boundLeft + boundWidth - 1);
    y1 = std::clamp(y1, boundTop, boundTop + boundHeight - 1);
    x2 = std::clamp(x2, x1 + 1, boundLeft + boundWidth);
    y2 = std::clamp(y2, y1 + 1, boundTop + boundHeight);
    return {x1, y1, x2 - x1, y2 - y1};
}

cv::Rect MaskBbox(const cv::Mat &mask)
{
    std::vector<cv::Point> points;
    cv::findNonZero(mask, points);
    return points.empty() ? cv::Rect() : cv::boundingRect(points);
}

int IntersectionCount(const cv::Mat &left, const cv::Mat &right)
{
    cv::Mat intersection;
    cv::bitwise_and(left, right, intersection);
    return cv::countNonZero(intersection);
}

cv::Mat EdgeBand(int height, int width)
{
    cv::Mat result = cv::Mat::zeros(height, width, CV_8UC1);
    const int left = std::max(2, static_cast<int>(std::lround(width * 0.08)));
    const int right = std::max(2, static_cast<int>(std::lround(width * 0.22)));
    const int top = std::max(2, static_cast<int>(std::lround(height * 0.08)));
    const int bottom = std::max(2, static_cast<int>(std::lround(height * 0.08)));
    result(cv::Rect(0, 0, left, height)).setTo(255);
    result(cv::Rect(width - right, 0, right, height)).setTo(255);
    result(cv::Rect(0, 0, width, top)).setTo(255);
    result(cv::Rect(0, height - bottom, width, bottom)).setTo(255);
    return result;
}

cv::Mat UiFrameSeed(const cv::Mat &bgr)
{
    cv::Mat hsv, seed;
    cv::cvtColor(bgr, hsv, cv::COLOR_BGR2HSV);
    cv::inRange(hsv, cv::Scalar(0, 0, 130), cv::Scalar(180, 130, 255), seed);
    cv::morphologyEx(seed, seed, cv::MORPH_CLOSE, cv::Mat::ones(3, 3, CV_8UC1));
    return seed;
}

cv::Mat RemoveUiFrameArtifacts(const cv::Mat &mask, const cv::Mat &bgr)
{
    cv::Mat seed = UiFrameSeed(bgr);
    cv::Mat band = EdgeBand(mask.rows, mask.cols);
    cv::bitwise_and(seed, band, seed);
    if (cv::countNonZero(seed) == 0) return mask.clone();
    cv::dilate(seed, seed, cv::Mat::ones(3, 3, CV_8UC1));
    cv::dilate(band, band, cv::Mat::ones(5, 5, CV_8UC1));
    cv::bitwise_and(seed, band, seed);
    cv::Mat cleaned = mask.clone();
    cleaned.setTo(0, seed);
    return cleaned;
}

cv::Mat SelectComponents(const cv::Mat &mask)
{
    cv::Mat labels, stats, centroids;
    const int count = cv::connectedComponentsWithStats(mask, labels, stats, centroids, 8, CV_32S);
    std::vector<std::pair<int, double>> components;
    const int total = mask.cols * mask.rows;
    for (int label = 1; label < count; ++label) {
        const int left = stats.at<int>(label, cv::CC_STAT_LEFT);
        const int top = stats.at<int>(label, cv::CC_STAT_TOP);
        const int width = stats.at<int>(label, cv::CC_STAT_WIDTH);
        const int height = stats.at<int>(label, cv::CC_STAT_HEIGHT);
        const int area = stats.at<int>(label, cv::CC_STAT_AREA);
        if (area < std::max(14, static_cast<int>(std::lround(total * 0.0015))) ||
            width < mask.cols * 0.06 || height < mask.rows * 0.06) continue;
        const double dx = (centroids.at<double>(label, 0) - mask.cols / 2.0) / std::max(1.0, static_cast<double>(mask.cols));
        const double dy = (centroids.at<double>(label, 1) - mask.rows / 2.0) / std::max(1.0, static_cast<double>(mask.rows));
        const double center = 1.0 / (1.0 + std::hypot(dx, dy) * 2.2);
        const bool touches = left == 0 || top == 0 || left + width >= mask.cols || top + height >= mask.rows;
        components.emplace_back(label, area * center * (touches ? 0.65 : 1.0));
    }
    if (components.empty()) return mask.clone();
    const double best = std::max_element(components.begin(), components.end(),
        [](const auto &left, const auto &right) { return left.second < right.second; })->second;
    cv::Mat selected = cv::Mat::zeros(mask.size(), CV_8UC1), component;
    for (const auto &entry : components) {
        if (entry.second < best * 0.20) continue;
        cv::compare(labels, entry.first, component, cv::CMP_EQ);
        cv::bitwise_or(selected, component, selected);
    }
    return selected;
}

std::array<double, 3> ChannelMedians(const cv::Mat &mat)
{
    std::array<std::vector<uint8_t>, 3> values;
    const size_t count = mat.total();
    for (auto &channel : values) channel.reserve(count);
    for (int y = 0; y < mat.rows; ++y) for (int x = 0; x < mat.cols; ++x) {
        const cv::Vec3b pixel = mat.at<cv::Vec3b>(y, x);
        for (int channel = 0; channel < 3; ++channel) values[channel].push_back(pixel[channel]);
    }
    std::array<double, 3> medians{};
    for (int channel = 0; channel < 3; ++channel) {
        auto &samples = values[channel];
        std::sort(samples.begin(), samples.end());
        medians[channel] = samples.size() % 2 == 1 ? samples[samples.size() / 2] :
            (samples[samples.size() / 2 - 1] + samples[samples.size() / 2]) / 2.0;
    }
    return medians;
}

struct BackgroundPrototype { std::array<double, 3> bgr; std::array<double, 3> hsv; };

std::vector<BackgroundPrototype> BackgroundPrototypes(const cv::Mat &bgr, const cv::Mat &hsv)
{
    const int width = bgr.cols, height = bgr.rows;
    std::vector<cv::Rect> boxes = {
        {0, 0, std::max(1, static_cast<int>(width * .18)), std::max(1, static_cast<int>(height * .18))},
        {static_cast<int>(width * .82), 0, std::max(1, static_cast<int>(width * .18)), std::max(1, static_cast<int>(height * .18))},
        {0, static_cast<int>(height * .82), std::max(1, static_cast<int>(width * .18)), std::max(1, static_cast<int>(height * .18))},
        {static_cast<int>(width * .82), static_cast<int>(height * .82), std::max(1, static_cast<int>(width * .18)), std::max(1, static_cast<int>(height * .18))},
        {0, 0, width, std::max(1, static_cast<int>(height * .05))},
        {0, static_cast<int>(height * .95), width, std::max(1, static_cast<int>(height * .05))},
    };
    std::vector<BackgroundPrototype> result;
    for (cv::Rect rect : boxes) {
        rect.width = std::min(rect.width, width - rect.x);
        rect.height = std::min(rect.height, height - rect.y);
        result.push_back({ChannelMedians(bgr(rect)), ChannelMedians(hsv(rect))});
    }
    return result;
}

double MaskQuality(const cv::Mat &mask)
{
    const int width = mask.cols, height = mask.rows, foreground = cv::countNonZero(mask);
    if (foreground < std::max(8, static_cast<int>(std::lround(width * height * .003)))) return -10.0;
    const cv::Rect bbox = MaskBbox(mask);
    if (bbox.empty()) return -10.0;
    const double ratio = static_cast<double>(foreground) / (width * height);
    const double widthRatio = static_cast<double>(bbox.width) / width;
    const double heightRatio = static_cast<double>(bbox.height) / height;
    const int border = std::max(1, static_cast<int>(std::lround(std::min(width, height) * .03)));
    cv::Mat borderMask = cv::Mat::zeros(height, width, CV_8UC1);
    borderMask(cv::Rect(0, 0, width, border)).setTo(1);
    borderMask(cv::Rect(0, height - border, width, border)).setTo(1);
    borderMask(cv::Rect(0, 0, border, height)).setTo(1);
    borderMask(cv::Rect(width - border, 0, border, height)).setTo(1);
    const double borderPixels = std::max(1, cv::countNonZero(borderMask));
    cv::Mat intersection;
    cv::bitwise_and(mask, borderMask, intersection);
    const double borderForeground = cv::countNonZero(intersection) / borderPixels;
    const bool touches = bbox.x <= 1 || bbox.y <= 1 || bbox.x + bbox.width >= width - 1 || bbox.y + bbox.height >= height - 1;
    double score = ratio >= .035 && ratio <= .65 ? 2.0 : -1.5;
    score += widthRatio <= .92 ? 1.0 : -1.0;
    score += heightRatio <= .92 ? 1.0 : -1.0;
    score -= borderForeground * 4.0;
    if (touches) score -= .8;
    score -= (std::abs(bbox.x + bbox.width * .5 - width * .5) / std::max(1.0, static_cast<double>(width)) +
        std::abs(bbox.y + bbox.height * .5 - height * .5) / std::max(1.0, static_cast<double>(height))) * .75;
    return score;
}

cv::Mat ColorDistanceMask(const cv::Mat &bgr, double distanceThreshold, double hsvThreshold)
{
    cv::Mat hsv;
    cv::cvtColor(bgr, hsv, cv::COLOR_BGR2HSV);
    const auto prototypes = BackgroundPrototypes(bgr, hsv);
    cv::Mat output = cv::Mat::zeros(bgr.rows, bgr.cols, CV_8UC1);
    const double bgrLimit = distanceThreshold * distanceThreshold;
    const double hsvLimit = hsvThreshold * hsvThreshold;
    for (int y = 0; y < bgr.rows; ++y) for (int x = 0; x < bgr.cols; ++x) {
        const cv::Vec3b b = bgr.at<cv::Vec3b>(y, x), h = hsv.at<cv::Vec3b>(y, x);
        double minBgr = std::numeric_limits<double>::max(), minHsv = minBgr;
        for (const auto &prototype : prototypes) {
            const double db0 = b[0] - prototype.bgr[0], db1 = b[1] - prototype.bgr[1], db2 = b[2] - prototype.bgr[2];
            minBgr = std::min(minBgr, db0 * db0 + db1 * db1 + db2 * db2);
            const double rawHue = std::abs(h[0] - prototype.hsv[0]);
            const double dh = std::min(rawHue, 180.0 - rawHue) * 1.6;
            const double ds = (h[1] - prototype.hsv[1]) * .45, dv = (h[2] - prototype.hsv[2]) * .45;
            minHsv = std::min(minHsv, dh * dh + ds * ds + dv * dv);
        }
        if (minBgr > bgrLimit && minHsv > hsvLimit) output.at<uint8_t>(y, x) = 255;
    }
    const cv::Mat kernel = cv::Mat::ones(3, 3, CV_8UC1);
    cv::morphologyEx(output, output, cv::MORPH_OPEN, kernel);
    cv::morphologyEx(output, output, cv::MORPH_CLOSE, kernel);
    return SelectComponents(RemoveUiFrameArtifacts(output, bgr));
}

cv::Mat GrabCutMask(const cv::Mat &bgr)
{
    const int width = bgr.cols, height = bgr.rows;
    if (width < 12 || height < 12) return cv::Mat::zeros(height, width, CV_8UC1);
    cv::Mat mask(height, width, CV_8UC1, cv::Scalar(cv::GC_PR_BGD));
    const int border = std::max(2, static_cast<int>(std::lround(std::min(width, height) * .035)));
    mask(cv::Rect(0, 0, width, border)).setTo(cv::GC_BGD);
    mask(cv::Rect(0, height - border, width, border)).setTo(cv::GC_BGD);
    mask(cv::Rect(0, 0, border, height)).setTo(cv::GC_BGD);
    mask(cv::Rect(width - border, 0, border, height)).setTo(cv::GC_BGD);
    const int marginX = std::min(width / 3, std::max(border + 1, static_cast<int>(std::lround(width * .12))));
    const int marginY = std::min(height / 3, std::max(border + 1, static_cast<int>(std::lround(height * .10))));
    mask(cv::Rect(marginX, marginY, width - marginX * 2, height - marginY * 2)).setTo(cv::GC_PR_FGD);
    cv::ellipse(mask, {width / 2, height / 2}, {std::max(2, static_cast<int>(width * .30)), std::max(2, static_cast<int>(height * .36))},
        0, 0, 360, cv::Scalar(cv::GC_PR_FGD), -1);
    cv::Mat bgd, fgd;
    try {
        cv::theRNG().state = 0;
        cv::grabCut(bgr, mask, cv::Rect(), bgd, fgd, 3, cv::GC_INIT_WITH_MASK);
    } catch (const cv::Exception &) {
        return cv::Mat::zeros(height, width, CV_8UC1);
    }
    cv::Mat definite, probable;
    cv::compare(mask, cv::GC_FGD, definite, cv::CMP_EQ);
    cv::compare(mask, cv::GC_PR_FGD, probable, cv::CMP_EQ);
    cv::bitwise_or(definite, probable, definite);
    const cv::Mat kernel = cv::Mat::ones(3, 3, CV_8UC1);
    cv::morphologyEx(definite, definite, cv::MORPH_OPEN, kernel);
    cv::morphologyEx(definite, definite, cv::MORPH_CLOSE, kernel);
    return SelectComponents(RemoveUiFrameArtifacts(definite, bgr));
}

double AddedEdgeDensity(const cv::Mat &bgr, const cv::Mat &base, const cv::Mat &expanded)
{
    const cv::Mat kernel = cv::Mat::ones(3, 3, CV_8UC1);
    cv::Mat dilated, notBase, added;
    cv::dilate(base, dilated, kernel);
    cv::bitwise_not(dilated, notBase);
    cv::bitwise_and(expanded, notBase, added);
    const int pixels = cv::countNonZero(added);
    if (pixels < std::max(8, static_cast<int>(std::lround(added.total() * .0005)))) return 0.0;
    cv::Mat gray, edge;
    cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);
    cv::Canny(gray, edge, 40, 110);
    cv::dilate(edge, edge, kernel);
    cv::bitwise_and(edge, added, edge);
    return static_cast<double>(cv::countNonZero(edge)) / pixels;
}

cv::Mat ChooseForeground(const cv::Mat &color, const cv::Mat &grabcut, const cv::Mat &bgr)
{
    const double colorScore = MaskQuality(color), grabScore = MaskQuality(grabcut);
    const int colorPixels = cv::countNonZero(color), grabPixels = cv::countNonZero(grabcut);
    if (colorPixels > 0 && grabPixels > 0) {
        const int intersection = IntersectionCount(color, grabcut);
        const double overlap = static_cast<double>(intersection) / std::max(1, grabPixels);
        const cv::Rect colorBox = MaskBbox(color), grabBox = MaskBbox(grabcut);
        if (overlap >= .80 && colorScore >= grabScore - .10 && !colorBox.empty() && !grabBox.empty() &&
            (static_cast<double>(grabBox.width) / grabcut.cols <= static_cast<double>(colorBox.width) / color.cols * .88 ||
             static_cast<double>(grabBox.height) / grabcut.rows <= static_cast<double>(colorBox.height) / color.rows * .88)) return color;
        if (overlap >= .65 && colorPixels >= grabPixels * 1.25 && colorScore >= grabScore - 1.25) return color;
        const double reverse = static_cast<double>(intersection) / std::max(1, colorPixels);
        if (reverse >= .65 && grabPixels >= colorPixels * 1.25 && grabScore >= colorScore - .30) {
            if (AddedEdgeDensity(bgr, color, grabcut) >= .12 && grabScore >= colorScore + .02) return grabcut;
            return color;
        }
    }
    return grabScore > colorScore ? grabcut : color;
}

struct ForegroundResult {
    cv::Mat mask;
    double strictMs;
    double relaxedMs;
    double grabMs;
    double selectionMs;
    double colorQuality;
};

ForegroundResult ForegroundMask(const cv::Mat &bgr, bool forceGrabCut)
{
    auto started = Clock::now();
    cv::Mat strict = ColorDistanceMask(bgr, 52, 32);
    const double strictMs = Millis(started);
    started = Clock::now();
    cv::Mat relaxed = ColorDistanceMask(bgr, 42, 26);
    const double relaxedMs = Millis(started);
    cv::Mat color;
    const int strictPixels = cv::countNonZero(strict), relaxedPixels = cv::countNonZero(relaxed);
    if (strictPixels == 0) color = relaxed;
    else if (relaxedPixels < strictPixels * 1.08) color = strict;
    else {
        const double overlap = static_cast<double>(IntersectionCount(strict, relaxed)) / std::max(1, strictPixels);
        const cv::Rect relaxedBox = MaskBbox(relaxed);
        color = overlap < .82 || MaskQuality(relaxed) < MaskQuality(strict) - .15 || relaxedBox.empty() ||
            relaxedBox.width >= relaxed.cols * .96 || relaxedBox.height >= relaxed.rows * .96 ? strict : relaxed;
    }
    started = Clock::now();
    const double quality = MaskQuality(color);
    if (!forceGrabCut && quality >= 3.5) return {color, strictMs, relaxedMs, 0.0, Millis(started), quality};
    auto grabStarted = Clock::now();
    cv::Mat grab = GrabCutMask(bgr);
    const double grabMs = Millis(grabStarted);
    started = Clock::now();
    cv::Mat chosen = ChooseForeground(color, grab, bgr);
    return {chosen, strictMs, relaxedMs, grabMs, Millis(started), quality};
}

double UiFrameArtifactRatio(const cv::Mat &mask, const cv::Mat &bgr)
{
    const int foreground = cv::countNonZero(mask);
    if (foreground == 0) return 0.0;
    cv::Mat seed = UiFrameSeed(bgr), band = EdgeBand(mask.rows, mask.cols);
    cv::bitwise_and(seed, band, seed);
    cv::bitwise_and(seed, mask, seed);
    return static_cast<double>(cv::countNonZero(seed)) / foreground;
}

double LabeledBonusScale(const cv::Mat &mask, const cv::Mat &bgr)
{
    const cv::Rect bbox = MaskBbox(mask);
    if (bbox.empty()) return 1.0;
    const double foregroundRatio = static_cast<double>(cv::countNonZero(mask)) / mask.total();
    const double widthRatio = static_cast<double>(bbox.width) / mask.cols;
    const double heightRatio = static_cast<double>(bbox.height) / mask.rows;
    if (UiFrameArtifactRatio(mask, bgr) >= .015) return 0.0;
    if (foregroundRatio >= .48 && widthRatio >= .72 && heightRatio >= .72) return 0.0;
    return 1.0;
}

cv::Rect PaddedBbox(const cv::Rect &rect, int width, int height)
{
    const int padding = std::max(2, static_cast<int>(std::max(rect.width, rect.height) * .06));
    const int left = std::max(0, rect.x - padding), top = std::max(0, rect.y - padding);
    const int right = std::min(width, rect.x + rect.width + padding), bottom = std::min(height, rect.y + rect.height + padding);
    return {left, top, right - left, bottom - top};
}

uint64_t PerceptualHash(const cv::Mat &gray)
{
    cv::Mat resized;
    cv::resize(gray, resized, {32, 32}, 0, 0, cv::INTER_AREA);
    resized.convertTo(resized, CV_32F);
    cv::dct(resized, resized);
    std::vector<float> medianValues;
    for (int y = 1; y < 8; ++y) for (int x = 1; x < 8; ++x) medianValues.push_back(resized.at<float>(y, x));
    std::sort(medianValues.begin(), medianValues.end());
    const float median = medianValues[medianValues.size() / 2];
    uint64_t hash = 0;
    for (int y = 0; y < 8; ++y) for (int x = 0; x < 8; ++x) {
        if (resized.at<float>(y, x) > median) hash |= uint64_t{1} << (63 - (y * 8 + x));
    }
    return hash;
}

std::vector<uint8_t> MatBits(const cv::Mat &mat)
{
    std::vector<uint8_t> result((mat.total() + 7) / 8, 0);
    for (size_t index = 0; index < mat.total(); ++index) {
        const int y = static_cast<int>(index / mat.cols), x = static_cast<int>(index % mat.cols);
        if (mat.at<uint8_t>(y, x) != 0) result[index / 8] |= static_cast<uint8_t>(1 << (7 - index % 8));
    }
    return result;
}

Feature CreateFeature(const cv::Mat &source, int featureSize, int coarseSize, bool forceGrabCut = false)
{
    const ForegroundResult foreground = ForegroundMask(source, forceGrabCut);
    cv::Mat mask = foreground.mask;
    if (cv::countNonZero(mask) < std::max(8, static_cast<int>(std::lround(mask.total() * .005)))) {
        mask = cv::Mat(source.rows, source.cols, CV_8UC1, cv::Scalar(255));
    }
    const double bonusScale = LabeledBonusScale(mask, source);
    cv::Rect bbox = MaskBbox(mask);
    if (bbox.empty()) bbox = {0, 0, source.cols, source.rows};
    bbox = PaddedBbox(bbox, source.cols, source.rows);
    cv::Mat croppedBgr = source(bbox).clone(), croppedMask = mask(bbox).clone();
    cv::Mat lab;
    cv::cvtColor(croppedBgr, lab, cv::COLOR_BGR2Lab);
    std::vector<cv::Mat> channels;
    cv::split(lab, channels);
    cv::createCLAHE(1.6, {4, 4})->apply(channels[0], channels[0]);
    cv::merge(channels, lab);
    cv::Mat normalized;
    cv::cvtColor(lab, normalized, cv::COLOR_Lab2BGR);
    const double scale = std::min(featureSize / std::max(1.0, static_cast<double>(normalized.cols)),
        featureSize / std::max(1.0, static_cast<double>(normalized.rows)));
    const int width = std::max(1, static_cast<int>(std::lround(normalized.cols * scale)));
    const int height = std::max(1, static_cast<int>(std::lround(normalized.rows * scale)));
    cv::Mat resizedBgr, resizedMask;
    cv::resize(normalized, resizedBgr, {width, height}, 0, 0, cv::INTER_AREA);
    cv::resize(croppedMask, resizedMask, {width, height}, 0, 0, cv::INTER_NEAREST);
    cv::Mat canvasBgr = cv::Mat::zeros(featureSize, featureSize, CV_8UC3);
    cv::Mat canvasMask = cv::Mat::zeros(featureSize, featureSize, CV_8UC1);
    const int left = (featureSize - width) / 2, top = (featureSize - height) / 2;
    resizedBgr.copyTo(canvasBgr(cv::Rect(left, top, width, height)));
    resizedMask.copyTo(canvasMask(cv::Rect(left, top, width, height)));
    cv::Mat gray, coarse;
    cv::cvtColor(canvasBgr, gray, cv::COLOR_BGR2GRAY);
    cv::resize(gray, coarse, {coarseSize, coarseSize}, 0, 0, cv::INTER_AREA);
    std::vector<uint8_t> coarseGray(coarse.total());
    std::memcpy(coarseGray.data(), coarse.data, coarseGray.size());
    cv::bitwise_and(gray, gray, gray, canvasMask);
    cv::Mat edge;
    cv::Canny(gray, edge, 60, 150);
    cv::bitwise_and(edge, edge, edge, canvasMask);
    cv::Mat hsv, hist;
    cv::cvtColor(canvasBgr, hsv, cv::COLOR_BGR2HSV);
    int channelsIndex[] = {0, 1}, bins[] = {24, 16};
    float hRange[] = {0, 180}, sRange[] = {0, 256};
    const float *ranges[] = {hRange, sRange};
    cv::calcHist(&hsv, 1, channelsIndex, canvasMask, hist, 2, bins, ranges, true, false);
    const double sum = cv::sum(hist)[0];
    if (sum > 0) hist *= 1.0 / sum;
    cv::Mat flatHist = hist.reshape(1, static_cast<int>(hist.total())).clone();
    std::vector<float> histValues(flatHist.total());
    std::memcpy(histValues.data(), flatHist.data, histValues.size() * sizeof(float));
    return {gray, std::move(coarseGray), MatBits(edge), flatHist, std::move(histValues), PerceptualHash(gray),
        bonusScale, foreground.strictMs, foreground.relaxedMs, foreground.grabMs, foreground.selectionMs,
        foreground.colorQuality};
}

std::string CandidateJson(const Candidate &candidate)
{
    const TemplateFeature &entry = *candidate.feature;
    const double score = Rounded(candidate.score);
    std::ostringstream out;
    out << "{\"entityType\":\"SPECIES\",\"canonicalId\":" << Quote(entry.canonicalId)
        << ",\"showdownId\":" << Quote(entry.showdownId) << ",\"displayName\":" << Quote(entry.displayName)
        << ",\"confidence\":" << Rounded(std::clamp(candidate.score, 0.0, 1.0))
        << ",\"score\":" << score << ",\"scoreMargin\":" << Rounded(candidate.margin)
        << ",\"source\":" << Quote(entry.source) << ",\"visualVariant\":" << Quote(entry.visualVariant)
        << ",\"isShiny\":" << (entry.shiny ? "true" : "false") << "}";
    return out.str();
}

struct SlotOutput {
    const Region *region;
    RankResult ranked;
    double cropMs;
    double featureMs;
    double strictMs;
    double relaxedMs;
    double grabMs;
    double selectionMs;
    double colorQuality;
    bool adaptive;
    double rankMs;
};

std::string SlotJson(const SlotOutput &slot)
{
    const bool requires = slot.ranked.candidates.empty() || slot.ranked.candidates[0].score < .90 ||
        slot.ranked.candidates[0].margin < .035;
    std::ostringstream out;
    out << "{\"side\":" << Quote(slot.region->side) << ",\"slotIndex\":" << slot.region->slot
        << ",\"roiId\":" << Quote(slot.region->id) << ",\"confirmed\":false,\"requiresConfirmation\":"
        << (requires ? "true" : "false");
    if (!slot.ranked.candidates.empty()) out << ",\"selectedCandidate\":" << CandidateJson(slot.ranked.candidates[0]);
    out << ",\"candidates\":[";
    for (size_t index = 0; index < slot.ranked.candidates.size(); ++index) {
        if (index) out << ',';
        out << CandidateJson(slot.ranked.candidates[index]);
    }
    out << "]}";
    return out.str();
}

std::mutex g_engineMutex;
std::unique_ptr<TemplateAsset> g_asset;

std::string Recognize(const std::vector<uint8_t> &rgba, int width, int height,
    const std::vector<uint8_t> &templateBytes, const std::string &capturedAt)
{
    const auto engineStarted = Clock::now();
    if (width <= 0 || height <= 0 || rgba.size() != static_cast<size_t>(width) * height * 4) {
        throw std::runtime_error("invalid RGBA team-preview frame");
    }
    std::lock_guard<std::mutex> guard(g_engineMutex);
    const bool loaded = g_asset == nullptr;
    auto loadStarted = Clock::now();
    if (!g_asset) g_asset = std::make_unique<TemplateAsset>(templateBytes.data(), templateBytes.size());
    const double templateLoadMs = Millis(loadStarted);
    cv::Mat rgbaMat(height, width, CV_8UC4, const_cast<uint8_t *>(rgba.data())), bgr;
    auto bitmapStarted = Clock::now();
    cv::cvtColor(rgbaMat, bgr, cv::COLOR_RGBA2BGR);
    const double bitmapMs = Millis(bitmapStarted);
    std::vector<SlotOutput> outputs;
    bool usable = false;
    for (const Region &region : REGIONS) {
        auto started = Clock::now();
        const cv::Rect rect = MapRegion(region, width, height);
        const cv::Mat crop = bgr(rect).clone();
        const double cropMs = Millis(started);
        started = Clock::now();
        Feature feature = CreateFeature(crop, g_asset->featureSize, g_asset->coarseSize);
        double featureMs = Millis(started);
        double strictMs = feature.strictMs, relaxedMs = feature.relaxedMs, grabMs = feature.grabCutMs;
        double selectionMs = feature.selectionMs;
        const double quality = feature.colorQuality;
        usable = usable || quality > -9.5;
        started = Clock::now();
        const std::string sideKey = std::string(region.side) + ".slot" + std::to_string(region.slot);
        RankResult ranked = g_asset->Rank(feature, sideKey, 3);
        double rankMs = Millis(started);
        bool adaptive = false;
        if (feature.grabCutMs == 0.0 && !ranked.candidates.empty() && ranked.candidates[0].margin < ADAPTIVE_GRABCUT_MARGIN) {
            started = Clock::now();
            Feature fallback = CreateFeature(crop, g_asset->featureSize, g_asset->coarseSize, true);
            featureMs += Millis(started);
            strictMs += fallback.strictMs; relaxedMs += fallback.relaxedMs; grabMs += fallback.grabCutMs;
            selectionMs += fallback.selectionMs;
            started = Clock::now();
            RankResult fallbackRanked = g_asset->Rank(fallback, sideKey, 3);
            rankMs += Millis(started);
            fallbackRanked.eligible += ranked.eligible;
            fallbackRanked.refined += ranked.refined;
            ranked = std::move(fallbackRanked);
            adaptive = true;
        }
        outputs.push_back({&region, std::move(ranked), cropMs, featureMs, strictMs, relaxedMs, grabMs,
            selectionMs, quality, adaptive, rankMs});
    }
    if (!usable) throw std::runtime_error("team-preview frame is blocked or invisible");
    const Viewport viewport = CenteredViewport(width, height);
    const double wallMs = Millis(engineStarted);
    double featureTotal = 0, rankTotal = 0;
    int eligibleTotal = 0, refinedTotal = 0;
    bool warnings = false;
    for (const auto &slot : outputs) {
        featureTotal += slot.featureMs; rankTotal += slot.rankMs;
        eligibleTotal += slot.ranked.eligible; refinedTotal += slot.ranked.refined;
        warnings = warnings || slot.ranked.candidates.empty() || slot.ranked.candidates[0].score < .90 ||
            slot.ranked.candidates[0].margin < .035;
    }
    std::ostringstream performance;
    performance << "{\"captureHideWaitMs\":0,\"frameCopyMs\":0,\"executorQueueMs\":0,\"openCvInitMs\":0"
        << ",\"templateLoadMs\":" << Rounded(templateLoadMs, 1000) << ",\"templateLoadPerformed\":" << (loaded ? "true" : "false")
        << ",\"roiConfigLoadMs\":0,\"bitmapToBgrMs\":" << Rounded(bitmapMs, 1000)
        << ",\"featureTotalMs\":" << Rounded(featureTotal, 1000) << ",\"rankTotalMs\":" << Rounded(rankTotal, 1000)
        << ",\"engineWallMs\":" << Rounded(wallMs, 1000) << ",\"engineThreadCpuMs\":0"
        << ",\"eligibleTemplateEvaluations\":" << eligibleTotal << ",\"refinedTemplateEvaluations\":" << refinedTotal
        << ",\"slots\":[";
    for (size_t index = 0; index < outputs.size(); ++index) {
        if (index) performance << ',';
        const auto &slot = outputs[index];
        performance << "{\"roiId\":" << Quote(slot.region->id) << ",\"cropMs\":" << Rounded(slot.cropMs, 1000)
            << ",\"featureMs\":" << Rounded(slot.featureMs, 1000) << ",\"strictColorMaskMs\":" << Rounded(slot.strictMs, 1000)
            << ",\"relaxedColorMaskMs\":" << Rounded(slot.relaxedMs, 1000) << ",\"grabCutMaskMs\":" << Rounded(slot.grabMs, 1000)
            << ",\"maskSelectionMs\":" << Rounded(slot.selectionMs, 1000) << ",\"colorMaskQuality\":" << Rounded(slot.colorQuality)
            << ",\"adaptiveGrabCutFallback\":" << (slot.adaptive ? "true" : "false") << ",\"rankMs\":" << Rounded(slot.rankMs, 1000)
            << ",\"eligibleTemplates\":" << slot.ranked.eligible << ",\"refinedTemplates\":" << slot.ranked.refined << "}";
    }
    performance << "]}";
    std::ostringstream result;
    result << "{\"schemaVersion\":1,\"kind\":\"TeamPreviewRecognitionResult\",\"sceneType\":\"TEAM_PREVIEW\""
        << ",\"capturedAt\":" << Quote(capturedAt) << ",\"imageSize\":{\"width\":" << width << ",\"height\":" << height << "}"
        << ",\"backend\":\"harmonyos_opencv_4.13.0\",\"templateAsset\":\"team-preview-templates-v2.bin\""
        << ",\"roiMapping\":{\"asset\":\"team-preview.safe-zone-roi.zh-Hans.v2.json\",\"mode\":\"largest_centered_aspect\""
        << ",\"gameViewport\":{\"left\":" << std::lround(viewport.left) << ",\"top\":" << std::lround(viewport.top)
        << ",\"width\":" << std::lround(viewport.width) << ",\"height\":" << std::lround(viewport.height) << "}}"
        << ",\"elapsedMs\":" << static_cast<long long>(wallMs) << ",\"performance\":" << performance.str()
        << ",\"confirmed\":false,\"ownTeamCandidates\":[";
    for (size_t index = 0; index < 6; ++index) { if (index) result << ','; result << SlotJson(outputs[index]); }
    result << "],\"opponentTeamCandidates\":[";
    for (size_t index = 6; index < 12; ++index) { if (index != 6) result << ','; result << SlotJson(outputs[index]); }
    result << "],\"warnings\":[";
    if (warnings) result << Quote("Low-confidence or low-margin candidates require user confirmation before damage calculation.");
    result << "]}";
    return result.str();
}

struct AsyncRecognition {
    napi_env env = nullptr;
    napi_async_work work = nullptr;
    napi_deferred deferred = nullptr;
    std::vector<uint8_t> rgba;
    std::vector<uint8_t> templates;
    int width = 0;
    int height = 0;
    std::string capturedAt;
    std::string result;
    std::string error;
};

std::string ReadString(napi_env env, napi_value value)
{
    size_t length = 0;
    napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    std::string result(length, '\0');
    napi_get_value_string_utf8(env, value, result.data(), length + 1, &length);
    return result;
}

std::vector<uint8_t> CopyArrayBuffer(napi_env env, napi_value value)
{
    void *data = nullptr;
    size_t length = 0;
    if (napi_get_arraybuffer_info(env, value, &data, &length) != napi_ok || data == nullptr) {
        throw std::runtime_error("ArrayBuffer argument required");
    }
    return {static_cast<uint8_t *>(data), static_cast<uint8_t *>(data) + length};
}

void ExecuteRecognition(napi_env, void *raw)
{
    auto *data = static_cast<AsyncRecognition *>(raw);
    try {
        data->result = Recognize(data->rgba, data->width, data->height, data->templates, data->capturedAt);
    } catch (const std::exception &error) {
        data->error = error.what();
    }
}

void CompleteRecognition(napi_env env, napi_status status, void *raw)
{
    std::unique_ptr<AsyncRecognition> data(static_cast<AsyncRecognition *>(raw));
    if (status != napi_ok && data->error.empty()) data->error = "native recognition task failed";
    napi_value value;
    if (data->error.empty()) {
        napi_create_string_utf8(env, data->result.c_str(), data->result.size(), &value);
        napi_resolve_deferred(env, data->deferred, value);
    } else {
        napi_value message;
        napi_create_string_utf8(env, data->error.c_str(), data->error.size(), &message);
        napi_create_error(env, nullptr, message, &value);
        napi_reject_deferred(env, data->deferred, value);
    }
    napi_delete_async_work(env, data->work);
}

} // namespace

napi_value RecognizeTeamPreview(napi_env env, napi_callback_info info)
{
    size_t count = 5;
    napi_value args[5]{};
    napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    napi_value promise;
    auto data = std::make_unique<AsyncRecognition>();
    data->env = env;
    napi_create_promise(env, &data->deferred, &promise);
    try {
        if (count < 5) throw std::runtime_error("five team-preview recognition arguments are required");
        data->rgba = CopyArrayBuffer(env, args[0]);
        napi_get_value_int32(env, args[1], &data->width);
        napi_get_value_int32(env, args[2], &data->height);
        data->templates = CopyArrayBuffer(env, args[3]);
        data->capturedAt = ReadString(env, args[4]);
        napi_value name;
        napi_create_string_utf8(env, "team-preview-recognition", NAPI_AUTO_LENGTH, &name);
        napi_create_async_work(env, nullptr, name, ExecuteRecognition, CompleteRecognition, data.get(), &data->work);
        napi_queue_async_work(env, data->work);
        data.release();
    } catch (const std::exception &error) {
        napi_value message, failure;
        napi_create_string_utf8(env, error.what(), NAPI_AUTO_LENGTH, &message);
        napi_create_error(env, nullptr, message, &failure);
        napi_reject_deferred(env, data->deferred, failure);
    }
    return promise;
}
