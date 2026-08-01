import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudOpponentLeft {
  build() { Stack() { BattleHudElementContent({ element: 'OPPONENT_LEFT' }) }.width('100%').height('100%') }
}
