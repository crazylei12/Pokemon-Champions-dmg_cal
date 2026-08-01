import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudOwnLeft {
  build() { Stack() { BattleHudElementContent({ element: 'OWN_LEFT' }) }.width('100%').height('100%') }
}
