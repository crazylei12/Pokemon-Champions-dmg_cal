import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudOwnRecognition {
  build() { Stack() { BattleHudElementContent({ element: 'OWN_RECOGNITION' }) }.width('100%').height('100%') }
}
