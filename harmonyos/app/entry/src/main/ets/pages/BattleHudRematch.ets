import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudRematch {
  build() { Stack() { BattleHudElementContent({ element: 'REMATCH' }) }.width('100%').height('100%') }
}
