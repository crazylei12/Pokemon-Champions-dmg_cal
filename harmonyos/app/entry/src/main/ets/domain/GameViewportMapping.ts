export interface GameViewportBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export const POKEMON_CHAMPIONS_GAME_ASPECT_RATIO: number = 16 / 9;

export function largestCenteredGameViewport(width: number, height: number,
  targetAspectRatio: number = POKEMON_CHAMPIONS_GAME_ASPECT_RATIO): GameViewportBounds {
  const displayWidth = Math.max(1, Math.round(width));
  const displayHeight = Math.max(1, Math.round(height));
  const displayAspectRatio = displayWidth / displayHeight;
  if (Math.abs(displayAspectRatio - targetAspectRatio) < 0.001) {
    return { x: 0, y: 0, width: displayWidth, height: displayHeight };
  }
  if (displayAspectRatio > targetAspectRatio) {
    const viewportWidth = Math.round(displayHeight * targetAspectRatio);
    return {
      x: Math.round((displayWidth - viewportWidth) / 2),
      y: 0,
      width: viewportWidth,
      height: displayHeight
    };
  }
  const viewportHeight = Math.round(displayWidth / targetAspectRatio);
  return {
    x: 0,
    y: Math.round((displayHeight - viewportHeight) / 2),
    width: displayWidth,
    height: viewportHeight
  };
}
