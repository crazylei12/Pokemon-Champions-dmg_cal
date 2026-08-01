import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudDamage {
  build() { Stack() { BattleHudElementContent({ element: 'DAMAGE' }) }.width('100%').height('100%') }
}
