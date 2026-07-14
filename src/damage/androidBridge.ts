import {createDefaultDamageEngine} from './index';
import type {DamageRequest} from './types';

export const ANDROID_DAMAGE_ENGINE_VERSION = 'pokemon-champions-smogon-0.11.0-v1';

export function calculateDamage(requestJson: string): string {
  try {
    const request = JSON.parse(requestJson) as DamageRequest;
    return JSON.stringify({ok: true, result: createDefaultDamageEngine().calculate(request)});
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return JSON.stringify({
      ok: false,
      error: {
        name: error instanceof Error ? error.name : 'Error',
        message,
      },
    });
  }
}

export function getEngineInfo(): string {
  return JSON.stringify({
    name: 'Pokemon Champions Damage Engine',
    version: ANDROID_DAMAGE_ENGINE_VERSION,
    generation: 'Champions',
    offline: true,
  });
}

declare global {
  interface Window {
    PokemonChampionsDamageEngine?: {
      calculateDamage: typeof calculateDamage;
      getEngineInfo: typeof getEngineInfo;
    };
  }
}

if (typeof window !== 'undefined') {
  window.PokemonChampionsDamageEngine = {calculateDamage, getEngineInfo};
}
