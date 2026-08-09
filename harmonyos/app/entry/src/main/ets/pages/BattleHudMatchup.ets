import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudMatchup {
  build() { Stack() { BattleHudElementContent({ element: 'MATCHUP' }) }.width('100%').height('100%') }
}
