import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudOwnRight {
  build() { Stack() { BattleHudElementContent({ element: 'OWN_RIGHT' }) }.width('100%').height('100%') }
}
