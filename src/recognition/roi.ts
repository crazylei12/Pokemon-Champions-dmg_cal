import type {
  GameViewport,
  KnownRecognitionSceneType,
  PixelRect,
  RecognitionSceneType,
  RoiConfig,
  RoiCropPlan,
  RoiRegion,
  Size,
} from './types';

const DEFAULT_ASPECT_RATIO = 16 / 9;

export function getScene(config: RoiConfig, sceneType: RecognitionSceneType) {
  if (sceneType === 'UNKNOWN') return undefined;
  return config.scenes[sceneType];
}

export function listRegions(
  config: RoiConfig,
  sceneType: KnownRecognitionSceneType,
  predicate: (region: RoiRegion) => boolean = () => true
) {
  return (config.scenes[sceneType]?.regions || []).filter(predicate);
}

export function isTextRecognitionRegion(region: RoiRegion) {
  return (
    region.role === 'ocr_text' ||
    region.role === 'number_text' ||
    region.match?.engine === 'ocr'
  );
}

export function isSceneMarkerRegion(region: RoiRegion) {
  return region.role === 'scene_marker';
}

export function buildRoiCropPlan(
  config: RoiConfig,
  sceneType: KnownRecognitionSceneType,
  imageSize: Size,
  options: {
    viewport?: GameViewport;
    includeSceneMarkers?: boolean;
  } = {}
): RoiCropPlan[] {
  const viewport = options.viewport || detectGameViewport(imageSize, config);
  const regions = listRegions(config, sceneType, (region) => {
    if (isTextRecognitionRegion(region)) return true;
    return Boolean(options.includeSceneMarkers && isSceneMarkerRegion(region));
  });

  return regions.map((region) => ({
    sceneType,
    region,
    viewport,
    rect: roiRegionToPixelRect(config, region, viewport),
  }));
}

export function detectGameViewport(imageSize: Size, config?: RoiConfig): GameViewport {
  const targetRatio = parseAspectRatio(config?.viewportDetection?.targetAspectRatio);
  const ratio = imageSize.width / imageSize.height;
  if (!Number.isFinite(ratio) || imageSize.width <= 0 || imageSize.height <= 0) {
    throw new Error(`Invalid image size: ${JSON.stringify(imageSize)}`);
  }

  if (Math.abs(ratio - targetRatio) < 0.001) {
    return {
      left: 0,
      top: 0,
      width: imageSize.width,
      height: imageSize.height,
      source: 'full_image',
    };
  }

  if (ratio > targetRatio) {
    const width = Math.round(imageSize.height * targetRatio);
    return {
      left: Math.max(0, Math.round((imageSize.width - width) / 2)),
      top: 0,
      width,
      height: imageSize.height,
      source: 'largest_16_9_game_content_area',
    };
  }

  const height = Math.round(imageSize.width / targetRatio);
  return {
    left: 0,
    top: Math.max(0, Math.round((imageSize.height - height) / 2)),
    width: imageSize.width,
    height,
    source: 'largest_16_9_game_content_area',
  };
}

export function roiRegionToPixelRect(
  config: RoiConfig,
  region: RoiRegion,
  viewport: GameViewport
): PixelRect {
  const unit = config.coordinateSpace.unit;
  const canonical = config.canonicalViewport;

  const left =
    unit === 'normalized'
      ? viewport.left + region.rect.x * viewport.width
      : viewport.left + region.rect.x * (viewport.width / canonical.width);
  const top =
    unit === 'normalized'
      ? viewport.top + region.rect.y * viewport.height
      : viewport.top + region.rect.y * (viewport.height / canonical.height);
  const width =
    unit === 'normalized'
      ? region.rect.width * viewport.width
      : region.rect.width * (viewport.width / canonical.width);
  const height =
    unit === 'normalized'
      ? region.rect.height * viewport.height
      : region.rect.height * (viewport.height / canonical.height);

  return clampPixelRect(
    {
      left: Math.floor(left),
      top: Math.floor(top),
      width: Math.max(1, Math.ceil(width)),
      height: Math.max(1, Math.ceil(height)),
    },
    viewport
  );
}

function parseAspectRatio(value?: string) {
  if (!value) return DEFAULT_ASPECT_RATIO;
  const match = value.match(/^(\d+(?:\.\d+)?):(\d+(?:\.\d+)?)$/);
  if (!match) return DEFAULT_ASPECT_RATIO;
  const width = Number(match[1]);
  const height = Number(match[2]);
  return width > 0 && height > 0 ? width / height : DEFAULT_ASPECT_RATIO;
}

function clampPixelRect(rect: PixelRect, viewport: GameViewport): PixelRect {
  const left = clamp(rect.left, viewport.left, viewport.left + viewport.width - 1);
  const top = clamp(rect.top, viewport.top, viewport.top + viewport.height - 1);
  const right = clamp(
    rect.left + rect.width,
    left + 1,
    viewport.left + viewport.width
  );
  const bottom = clamp(
    rect.top + rect.height,
    top + 1,
    viewport.top + viewport.height
  );
  return {
    left,
    top,
    width: right - left,
    height: bottom - top,
  };
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}
