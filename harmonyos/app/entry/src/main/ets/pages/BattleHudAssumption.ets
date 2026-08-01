import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudAssumption {
  build() { Stack() { BattleHudElementContent({ element: 'ASSUMPTION' }) }.width('100%').height('100%') }
}
