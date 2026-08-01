import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudToggle {
  build() { Stack() { BattleHudElementContent({ element: 'TOGGLE' }) }.width('100%').height('100%') }
}
