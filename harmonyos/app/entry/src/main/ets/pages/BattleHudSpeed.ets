import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudSpeed {
  build() { Stack() { BattleHudElementContent({ element: 'SPEED' }) }.width('100%').height('100%') }
}
