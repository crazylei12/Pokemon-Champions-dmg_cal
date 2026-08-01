import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudRecording {
  build() { Stack() { BattleHudElementContent({ element: 'RECORDING' }) }.width('100%').height('100%') }
}
