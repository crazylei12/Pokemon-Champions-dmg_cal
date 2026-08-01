import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudOpponentRight {
  build() { Stack() { BattleHudElementContent({ element: 'OPPONENT_RIGHT' }) }.width('100%').height('100%') }
}
