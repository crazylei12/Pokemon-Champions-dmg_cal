import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudFormat {
  build() { Stack() { BattleHudElementContent({ element: 'FORMAT' }) }.width('100%').height('100%') }
}
